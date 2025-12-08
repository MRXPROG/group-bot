package com.example.group.service;

import com.example.group.config.BotConfig;
import com.example.group.dto.SlotBookingDTO;
import com.example.group.dto.SlotDTO;
import com.example.group.model.Booking;
import com.example.group.model.GroupShiftMessage;
import com.example.group.repository.GroupShiftMessageRepository;
import com.example.group.service.util.MessageCleaner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlotPostService {

    private final GroupShiftMessageRepository shiftMsgRepo;
    private final BotConfig config;
    private final MessageCleaner cleaner;

    private static final Locale UA = Locale.forLanguageTag("uk");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", UA);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", UA);

    public Message publishSlotPost(TelegramLongPollingBot bot, Long chatId, SlotDTO s) throws Exception {
        return publishSlotPost(bot, chatId, s, false, false);
    }

    public Message publishSlotPost(TelegramLongPollingBot bot,
                                   Long chatId,
                                   SlotDTO s,
                                   boolean morningPost,
                                   boolean eveningPost) throws Exception {
        String date = s.getStart().toLocalDate().format(DATE);
        String time = s.getStart().toLocalTime().format(TIME) + " — " +
                s.getEnd().toLocalTime().format(TIME);

        String innLine = s.isInnRequired() ? "\nℹ️ Для цієї локації потрібен ІПН." : "";

        int activeBookings = countActiveBookings(s);
        String employees = buildEmployeeBlock(s.getBookings());

        String text = """
                📍 %s
                🏙️ %s
                📅 %s
                🕒 %s
                👥 %d/%d зайнято%s

                %s
                """.formatted(
                s.getPlaceName(),
                s.getCityName(),
                date,
                time,
                activeBookings,
                s.getCapacity(),
                innLine,
                employees
        );

        InlineKeyboardButton join = new InlineKeyboardButton();
        join.setText("🟢 Записатись через бота");
        join.setUrl("https://t.me/" + config.getMainBotUsername() + "?start=slot_" + s.getId());

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup();
        kb.setKeyboard(List.of(List.of(join)));

        var existingOpt = shiftMsgRepo.findFirstByChatIdAndSlotIdOrderByPostedAtDesc(chatId, s.getId());
        Integer oldMessageId = existingOpt.map(GroupShiftMessage::getMessageId).orElse(null);

        if (oldMessageId != null) {
            Message edited = tryEditExistingPost(bot, chatId, oldMessageId, text, kb);
            if (edited != null) {
                GroupShiftMessage updated = updateExisting(existingOpt.get(), oldMessageId, morningPost, eveningPost);
                shiftMsgRepo.save(updated);
                return edited;
            }
            log.warn("Failed to edit message {} for slot {}, sending a new one", oldMessageId, s.getId());
        }

        SendMessage sm = new SendMessage(chatId.toString(), text);
        sm.setReplyMarkup(kb);

        Message sent = bot.execute(sm);

        GroupShiftMessage gsm = existingOpt
                .map(existing -> updateExisting(existing, sent.getMessageId(), morningPost, eveningPost))
                .orElseGet(() -> GroupShiftMessage.builder()
                        .chatId(chatId)
                        .messageId(sent.getMessageId())
                        .slotId(s.getId())
                        .postedAt(LocalDateTime.now())
                        .morningPost(morningPost)
                        .eveningPost(eveningPost)
                        .build()
                );

        shiftMsgRepo.save(gsm);

        cleanupOldPost(bot, chatId, oldMessageId, sent.getMessageId());

        return sent;
    }

    public void sendReminder(TelegramLongPollingBot bot,
                             Long chatId,
                             Integer messageId,
                             SlotDTO s,
                             String prefix) throws Exception {

        int free = s.getCapacity() - countActiveBookings(s);
        if (free <= 0) {
            log.info("Slot {} has no free places, skip reminder", s.getId());
            return;
        }

        String text = prefix + "\n" +
                "📍 " + s.getPlaceName() + "\n" +
                "🕒 " + s.getStart().toLocalTime() + " – " + s.getEnd().toLocalTime() + "\n" +
                "Залишилось місць: " + free;

        SendMessage sm = new SendMessage(chatId.toString(), text);
        sm.setReplyToMessageId(messageId);
        bot.execute(sm);
    }

    private String buildEmployeeBlock(List<SlotBookingDTO> bookings) {
        List<SlotBookingDTO> safeBookings = Optional.ofNullable(bookings).orElse(Collections.emptyList());
        List<SlotBookingDTO> activeBookings = filterActiveBookings(safeBookings);

        if (activeBookings.isEmpty()) {
            return "Наразі немає записів.";
        }

        String list = activeBookings.stream()
                .map(this::formatBookingLine)
                .collect(Collectors.joining("\n"));

        return "Записані учасники:\n" + list;
    }

    private List<SlotBookingDTO> filterActiveBookings(List<SlotBookingDTO> bookings) {
        return bookings.stream()
                .filter(b -> {
                    Booking.BookingStatus status = Optional.ofNullable(b.getStatus())
                            .orElse(Booking.BookingStatus.PENDING);
                    return status == Booking.BookingStatus.PENDING || status == Booking.BookingStatus.CONFIRMED;
                })
                .toList();
    }

    private int countActiveBookings(SlotDTO slot) {
        List<SlotBookingDTO> bookings = Optional.ofNullable(slot.getBookings()).orElse(Collections.emptyList());
        if (!bookings.isEmpty()) {
            return filterActiveBookings(bookings).size();
        }
        return slot.getBookedCount();
    }

    private String formatBookingLine(SlotBookingDTO booking) {
        Booking.BookingStatus status = Optional.ofNullable(booking.getStatus()).orElse(Booking.BookingStatus.PENDING);
        String statusIcon = switch (status) {
            case CONFIRMED -> "✅";
            case PENDING -> "⏳";
            default -> "⏳";
        };

        String name = (Optional.ofNullable(booking.getFirstName()).orElse("") + " " +
                Optional.ofNullable(booking.getLastName()).orElse("")).trim();
        if (name.isBlank()) name = "Невідомий";

        return statusIcon + " " + name;
    }

    private GroupShiftMessage updateExisting(GroupShiftMessage existing,
                                             Integer newMessageId,
                                             boolean morningPost,
                                             boolean eveningPost) {
        existing.setMessageId(newMessageId);
        existing.setPostedAt(LocalDateTime.now());
        existing.setMorningPost(morningPost);
        existing.setEveningPost(eveningPost);
        return existing;
    }

    private Message tryEditExistingPost(TelegramLongPollingBot bot,
                                        Long chatId,
                                        Integer messageId,
                                        String newText,
                                        InlineKeyboardMarkup markup) {
        try {
            EditMessageText edit = EditMessageText.builder()
                    .chatId(chatId.toString())
                    .messageId(messageId)
                    .text(newText)
                    .replyMarkup(markup)
                    .build();

            return bot.execute(edit);
        } catch (Exception e) {
            log.warn("Failed to edit existing slot post {}: {}", messageId, e.getMessage());
            return null;
        }
    }

    private void cleanupOldPost(TelegramLongPollingBot bot,
                                Long chatId,
                                Integer oldMessageId,
                                Integer newMessageId) {
        if (oldMessageId == null || Objects.equals(oldMessageId, newMessageId)) {
            return;
        }

        cleaner.deleteNow(bot, chatId, oldMessageId);
    }
}
