package com.example.group.service;

import com.example.group.controllers.MainBotApiClient;
import com.example.group.dto.SlotBookingDTO;
import com.example.group.dto.SlotDTO;
import com.example.group.model.Booking;
import com.example.group.model.GroupShiftMessage;
import com.example.group.repository.GroupShiftMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
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

    @Scheduled(cron = "0 */3 * * * *")
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
            log.info("SlotPostUpdater: slot {} not found, keeping post {} for potential re-open", msg.getSlotId(), msg.getMessageId());
            return;
        }

        if (isSlotFinished(slot)) {
            log.info("SlotPostUpdater: slot {} is finished, cleaning up post {}", msg.getSlotId(), msg.getMessageId());
            cleanupSlotPost(chatId, msg);
            return;
        }

        SlotSnapshot current = captureSnapshot(slot);
        SlotSnapshot previous = slotSnapshots.get(slot.getId());
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

    private boolean isSlotFinished(SlotDTO slot) {
        LocalDateTime end = slot.getEnd();
        return end != null && end.isBefore(LocalDateTime.now());
    }

    private void cleanupSlotPost(Long chatId, GroupShiftMessage msg) {
        if (!deleteSlotPost(chatId, msg.getMessageId())) {
            markAsServiceMessage(chatId, msg.getMessageId());
        }
        shiftMsgRepo.delete(msg);
        slotSnapshots.remove(msg.getSlotId());
    }

    private boolean deleteSlotPost(Long chatId, Integer messageId) {
        try {
            DeleteMessage delete = new DeleteMessage(chatId.toString(), messageId);
            bot.execute(delete);
            return true;
        } catch (TelegramApiException e) {
            log.warn("SlotPostUpdater: failed to delete expired slot message {}: {}", messageId, e.getMessage());
            return false;
        }
    }

    private void markAsServiceMessage(Long chatId, Integer messageId) {
        try {
            EditMessageText edit = EditMessageText.builder()
                    .chatId(chatId.toString())
                    .messageId(messageId)
                    .text("ℹ️ Зміна завершена. Пост архівовано.")
                    .build();
            bot.execute(edit);
        } catch (TelegramApiException e) {
            log.warn("SlotPostUpdater: failed to convert expired slot message {} into service message: {}", messageId, e.getMessage());
        }
    }

    private SlotSnapshot captureSnapshot(SlotDTO slot) {
        List<SlotBookingDTO> bookings = Optional.ofNullable(slot.getBookings()).orElse(List.of());
        List<SlotBookingDTO> activeBookings = bookings.stream()
                .filter(this::isActiveBooking)
                .toList();

        int activeCount = !activeBookings.isEmpty() ? activeBookings.size() : slot.getBookedCount();
        List<String> participants = activeBookings.stream()
                .map(this::bookingSignature)
                .toList();

        return new SlotSnapshot(slot.getCapacity(), activeCount, participants);
    }

    private boolean isActiveBooking(SlotBookingDTO booking) {
        Booking.BookingStatus status = Optional.ofNullable(booking.getStatus())
                .orElse(Booking.BookingStatus.PENDING);
        return status == Booking.BookingStatus.CONFIRMED || status == Booking.BookingStatus.PENDING;
    }

    private String bookingSignature(SlotBookingDTO booking) {
        String first = Optional.ofNullable(booking.getFirstName()).orElse("").trim();
        String last = Optional.ofNullable(booking.getLastName()).orElse("").trim();
        String status = Optional.ofNullable(booking.getStatus()).map(Enum::name).orElse("PENDING");
        return status + ":" + first + ":" + last;
    }

    private record SlotSnapshot(int capacity, int activeBookings, List<String> participants) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SlotSnapshot that)) return false;
            return capacity == that.capacity
                    && activeBookings == that.activeBookings
                    && Objects.equals(participants, that.participants);
        }

        @Override
        public int hashCode() {
            return Objects.hash(capacity, activeBookings, participants);
        }
    }
}
