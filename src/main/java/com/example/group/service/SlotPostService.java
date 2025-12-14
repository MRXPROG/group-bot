package com.example.group.service;

import com.example.group.config.BotConfig;
import com.example.group.dto.SlotBookingDTO;
import com.example.group.dto.SlotDTO;
import com.example.group.model.Booking;
import com.example.group.model.GroupShiftMessage;
import com.example.group.repository.GroupShiftMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

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

    private static final Locale UA = Locale.forLanguageTag("uk");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", UA);
    private static final DateTimeFormatter DAY_OF_WEEK = DateTimeFormatter.ofPattern("EEEE", UA);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", UA);

    public Message publishSlotPost(TelegramLongPollingBot bot, Long chatId, SlotDTO s) throws Exception {
        return publishSlotPost(bot, chatId, s, false, false);
    }

    public synchronized Message publishSlotPost(TelegramLongPollingBot bot,
                                                Long chatId,
                                                SlotDTO s,
                                                boolean morningPost,
                                                boolean eveningPost) throws Exception {
        return publishSlotPost(bot, chatId, s, morningPost, eveningPost, false);
    }

    public synchronized Message publishSlotPost(TelegramLongPollingBot bot,
                                                Long chatId,
                                                SlotDTO s,
                                                boolean morningPost,
                                                boolean eveningPost,
                                                boolean forceNewPost) throws Exception {
        String date = s.getStart().toLocalDate().format(DATE);
        String day = s.getStart().toLocalDate().format(DAY_OF_WEEK);
        String time = s.getStart().toLocalTime().format(TIME) + " - " +
                s.getEnd().toLocalTime().format(TIME);

        String innLine = s.isInnRequired() ? " • ІПН обов'язковий" : "";

        List<SlotBookingDTO> safeBookings = Optional.ofNullable(s.getBookings()).orElse(Collections.emptyList());
        List<SlotBookingDTO> activeBookings = filterActiveBookings(safeBookings);
        int activeCount = activeBookings.size();

        String employees = buildEmployeeBlock(activeBookings);
        String fullNotice = activeCount >= s.getCapacity()
                ? "\n\n⚠️ Зміна поки повна. Слідкуй за оновленнями — щойно звільниться місце, пост оновиться."
                : "";

        String text = """
                📢 Нова зміна - запис відкрито!

                📍 %s
                🏙️ %s
                📅 %s (%s)
                🕒 %s
                👥 %d/%d зайнято%s

                %s
                """.formatted(
                escapeHtml(s.getPlaceName()),
                escapeHtml(s.getCityName()),
                date,
                day,
                time,
                activeCount,
                s.getCapacity(),
                innLine,
                employees + fullNotice
        ).trim();

        InlineKeyboardButton join = new InlineKeyboardButton();
        join.setText("\uD83D\uDD17  Записатись на цю зміну у боті  \uD83D\uDD17 ");
        join.setUrl("https://t.me/" + config.getMainBotUsername() + "?start=slot_" + s.getId());

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup();
        kb.setKeyboard(List.of(List.of(join)));

        Optional<GroupShiftMessage> existingOpt = shiftMsgRepo.findByChatIdAndSlotId(chatId, s.getId());

        if (existingOpt.isEmpty() || forceNewPost) {
            return sendAndStore(bot, chatId, s, morningPost, eveningPost, text, kb, existingOpt.orElse(null));
        }

        GroupShiftMessage record = existingOpt.get();
        try {
            Message edited = executeEdit(bot, chatId, record.getMessageId(), text, kb);
            storeUpdated(record, edited.getMessageId(), morningPost, eveningPost);
            return edited;
        } catch (TelegramApiException e) {
            if (isMessageMissing(e)) {
                log.warn("SlotPostService: message {} for slot {} was removed, re-publishing", record.getMessageId(), s.getId());
                return sendAndStore(bot, chatId, s, morningPost, eveningPost, text, kb, record);
            }
            log.error("SlotPostService: failed to edit message {} for slot {}: {}", record.getMessageId(), s.getId(), e.getMessage());
            throw e;
        }
    }

    private String buildEmployeeBlock(List<SlotBookingDTO> activeBookings) {
        if (activeBookings.isEmpty()) {
            return "Працівники:\n" + wrapInCollapsedComment("Наразі учасників немає.");
        }

        String list = activeBookings.stream()
                .map(this::formatBookingLine)
                .collect(Collectors.joining("\n"));

        return "Працівники:\n" + wrapInCollapsedComment(list);
    }

    private List<SlotBookingDTO> filterActiveBookings(List<SlotBookingDTO> bookings) {
        return bookings.stream()
                .filter(b -> Optional.ofNullable(b.getStatus())
                        .map(status -> status != Booking.BookingStatus.CANCELLED)
                        .orElse(true))
                .toList();
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

        return statusIcon + " " + escapeHtml(name);
    }

    private String escapeHtml(String value) {
        String safe = Optional.ofNullable(value).orElse("");
        return safe
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String wrapInCollapsedComment(String text) {
        return "<blockquote expandable>" + text + "</blockquote>";
    }

    private Message sendAndStore(TelegramLongPollingBot bot,
                                 Long chatId,
                                 SlotDTO slot,
                                 boolean morningPost,
                                 boolean eveningPost,
                                 String text,
                                 InlineKeyboardMarkup kb) throws Exception {
        return sendAndStore(bot, chatId, slot, morningPost, eveningPost, text, kb, null);
    }

    private Message sendAndStore(TelegramLongPollingBot bot,
                                 Long chatId,
                                 SlotDTO slot,
                                 boolean morningPost,
                                 boolean eveningPost,
                                 String text,
                                 InlineKeyboardMarkup kb,
                                 GroupShiftMessage existing) throws Exception {
        SendMessage sm = new SendMessage(chatId.toString(), text);
        sm.setReplyMarkup(kb);
        sm.setParseMode("HTML");

        Message sent = bot.execute(sm);

        GroupShiftMessage record = existing != null ? existing : GroupShiftMessage.builder()
                .chatId(chatId)
                .slotId(slot.getId())
                .build();

        storeUpdated(record, sent.getMessageId(), morningPost, eveningPost);
        return sent;
    }

    private Message executeEdit(TelegramLongPollingBot bot,
                                Long chatId,
                                Integer messageId,
                                String newText,
                                InlineKeyboardMarkup markup) throws TelegramApiException {
        EditMessageText edit = EditMessageText.builder()
                .chatId(chatId.toString())
                .messageId(messageId)
                .text(newText)
                .replyMarkup(markup)
                .parseMode("HTML")
                .build();

        return (Message) bot.execute(edit);
    }

    private void storeUpdated(GroupShiftMessage existing,
                              Integer newMessageId,
                              boolean morningPost,
                              boolean eveningPost) {
        existing.setMessageId(newMessageId);
        existing.setPostedAt(LocalDateTime.now());
        existing.setMorningPost(morningPost);
        existing.setEveningPost(eveningPost);

        shiftMsgRepo.save(existing);
    }

    private boolean isMessageMissing(TelegramApiException e) {
        Integer code = e.hashCode();
        String response = Optional.ofNullable(e.getMessage()).orElse("");
        String description = Optional.ofNullable(e.getMessage()).orElse("");
        String payload = (response + " " + description).toLowerCase();
        return Objects.equals(code, 400) && (payload.contains("message to edit not found") || payload.contains("message to delete not found") || payload.contains("message is not modified"));
    }
}
