package com.example.group.service;

import com.example.group.controllers.MainBotApiClient;
import com.example.group.dto.SlotBookingDTO;
import com.example.group.dto.SlotDTO;
import com.example.group.model.Booking;
import com.example.group.model.GroupShiftMessage;
import com.example.group.repository.GroupShiftMessageRepository;
import com.example.group.service.util.SlotAvailabilityCalculator;
import com.example.group.service.util.SlotAvailabilityCalculator.SlotAvailability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlotPostUpdater {

    private final BotSettingsService settingsService;
    private final GroupShiftMessageRepository shiftMsgRepo;
    private final MainBotApiClient api;
    private final SlotPostService slotPostService;

    private final Map<Long, SlotSnapshot> slotSnapshots = new ConcurrentHashMap<>();

    private TelegramBot bot;

    public void start(TelegramBot bot) {
        this.bot = bot;
    }

    @Scheduled(cron = "0 */1 * * * *")
    public void refreshSlotPosts() {
        if (bot == null) {
            log.warn("SlotPostUpdater: bot is not set yet");
            return;
        }

        Long chatId = settingsService.getGroupChatId();
        if (chatId == null || chatId == 0) {
            log.warn("SlotPostUpdater: group chat is not bound yet");
            return;
        }

        List<GroupShiftMessage> messages = shiftMsgRepo.findAllByChatId(chatId);
        if (messages.isEmpty()) {
            return;
        }

        messages.forEach(msg -> refreshSingle(chatId, msg));
    }

    private void refreshSingle(Long chatId, GroupShiftMessage msg) {
        SlotDTO slot = fetchSlot(msg.getSlotId());
        if (slot == null) {
            if (handleMissingSlot(chatId, msg)) {
                return;
            }
            log.info("SlotPostUpdater: slot {} not found, keeping post {} for potential re-open", msg.getSlotId(), msg.getMessageId());
            return;
        }

        if (isSlotFinished(slot)) {
            log.info("SlotPostUpdater: slot {} is finished, updating post {}", msg.getSlotId(), msg.getMessageId());
            cleanupSlotPost(chatId, msg, slot);
            return;
        }

        SlotSnapshot previous = slotSnapshots.get(slot.getId());
        boolean started = isSlotStarted(slot);
        if (started && previous != null && previous.started()) {
            return;
        }

        SlotSnapshot current = captureSnapshot(slot, started);
        if (current.equals(previous)) {
            return;
        }

        try {
            slotPostService.publishSlotPost(bot, chatId, slot, msg.isMorningPost(), msg.isEveningPost());
            slotSnapshots.put(slot.getId(), current);
        } catch (Exception e) {
            log.error("SlotPostUpdater: failed to refresh slot {}: {}", slot.getId(), e.getMessage());
        }
    }

    private SlotDTO fetchSlot(Long slotId) {
        SlotDTO slot = api.getSlotById(slotId);
        if (slot != null) {
            return slot;
        }

        try {
            return api.getUpcomingSlots().stream()
                    .filter(it -> slotId.equals(it.getId()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("SlotPostUpdater: failed to reload slot {} from upcoming list: {}", slotId, e.getMessage());
            return null;
        }
    }

    private boolean handleMissingSlot(Long chatId, GroupShiftMessage msg) {
        Long slotId = msg.getSlotId();
        SlotDTO expired = api.getExpiredSlotById(slotId);
        if (expired != null) {
            log.info("SlotPostUpdater: slot {} is expired, updating post {}", slotId, msg.getMessageId());
            cleanupSlotPost(chatId, msg, expired);
            return true;
        }

        log.info("SlotPostUpdater: slot {} is removed, deleting post {}", slotId, msg.getMessageId());
        cleanupCancelledSlotPost(chatId, msg, null);
        return true;
    }

    private boolean isSlotFinished(SlotDTO slot) {
        LocalDateTime end = slot.getEnd();
        return end != null && end.isBefore(LocalDateTime.now());
    }

    private boolean isSlotStarted(SlotDTO slot) {
        LocalDateTime start = slot.getStart();
        return start != null && start.isBefore(LocalDateTime.now());
    }

    private void cleanupSlotPost(Long chatId, GroupShiftMessage msg, SlotDTO slot) {
        try {
            slotPostService.markFinishedPost(bot, chatId, msg.getMessageId(), slot);
        } catch (TelegramApiException e) {
            log.warn("SlotPostUpdater: failed to mark finished slot message {}: {}", msg.getMessageId(), e.getMessage());
        }
        shiftMsgRepo.delete(msg);
        slotSnapshots.remove(msg.getSlotId());
    }

    private void cleanupCancelledSlotPost(Long chatId, GroupShiftMessage msg, SlotDTO slot) {
        boolean deleted = false;
        try {
            slotPostService.deleteSlotPost(bot, chatId, msg.getMessageId());
            deleted = true;
        } catch (TelegramApiException e) {
            log.warn("SlotPostUpdater: failed to delete cancelled slot message {}: {}", msg.getMessageId(), e.getMessage());
        }

        if (!deleted && slot != null) {
            try {
                slotPostService.markCancelledPost(bot, chatId, msg.getMessageId(), slot);
            } catch (TelegramApiException e) {
                log.warn("SlotPostUpdater: failed to archive cancelled slot message {}: {}", msg.getMessageId(), e.getMessage());
            }
        }

        shiftMsgRepo.delete(msg);
        slotSnapshots.remove(msg.getSlotId());
    }

    private SlotSnapshot captureSnapshot(SlotDTO slot, boolean started) {
        int activeCount = countActiveBookings(slot);
        SlotAvailability availability = SlotAvailabilityCalculator.calculate(slot.getCapacity(), activeCount);

        List<String> participants = Optional.ofNullable(slot.getBookings())
                .orElse(List.of())
                .stream()
                .filter(this::isActiveBooking)
                .map(this::bookingSignature)
                .toList();

        return new SlotSnapshot(
                slot.getCapacity(),
                activeCount,
                availability.availablePlaces(),
                participants,
                resolveStatus(slot),
                started
        );
    }

    private int countActiveBookings(SlotDTO slot) {
        List<SlotBookingDTO> bookings = Optional.ofNullable(slot.getBookings()).orElse(List.of());
        if (!bookings.isEmpty()) {
            return (int) bookings.stream()
                    .filter(this::isActiveBooking)
                    .count();
        }

        return slot.getBookedCount();
    }

    private boolean isActiveBooking(SlotBookingDTO booking) {
        Booking.BookingStatus status = Optional.ofNullable(booking.getStatus())
                .orElse(Booking.BookingStatus.PENDING);
        return status == Booking.BookingStatus.CONFIRMED || status == Booking.BookingStatus.PENDING;
    }

    private SlotDTO.SlotStatus resolveStatus(SlotDTO slot) {
        if (slot == null) {
            return SlotDTO.SlotStatus.READY;
        }

        return Optional.ofNullable(slot.getStatus()).orElse(SlotDTO.SlotStatus.READY);
    }

    private String bookingSignature(SlotBookingDTO booking) {
        String first = Optional.ofNullable(booking.getFirstName()).orElse("").trim();
        String last = Optional.ofNullable(booking.getLastName()).orElse("").trim();
        String status = Optional.ofNullable(booking.getStatus()).map(Enum::name).orElse("PENDING");
        return status + ":" + first + ":" + last;
    }

    private record SlotSnapshot(int capacity,
                                int activeBookings,
                                int freePlaces,
                                List<String> participants,
                                SlotDTO.SlotStatus status,
                                boolean started) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SlotSnapshot that)) return false;
            return capacity == that.capacity
                    && activeBookings == that.activeBookings
                    && freePlaces == that.freePlaces
                    && started == that.started
                    && status == that.status
                    && Objects.equals(participants, that.participants);
        }

        @Override
        public int hashCode() {
            return Objects.hash(capacity, activeBookings, freePlaces, participants, status, started);
        }
    }
}
