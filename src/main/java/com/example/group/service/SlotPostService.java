package com.example.group.service;

import com.example.group.config.BotConfig;
import com.example.group.dto.SlotBookingDTO;
import com.example.group.dto.SlotDTO;
import com.example.group.model.Booking;
import com.example.group.model.GroupShiftMessage;
import com.example.group.repository.GroupShiftMessageRepository;
import com.example.group.service.util.SlotAvailabilityCalculator;
import com.example.group.service.util.SlotAvailabilityCalculator.SlotAvailability;
import com.example.group.service.util.TimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

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
        int activeBookings = countActiveBookings(s);
        SlotAvailability availability = SlotAvailabilityCalculator.calculate(s.getCapacity(), activeBookings);

        boolean isFull = availability.isFull();
        boolean isReserved = resolveStatus(s) == SlotDTO.SlotStatus.RESERVED;
        boolean isStarted = isSlotStarted(s);
        boolean isFinished = isSlotFinished(s);

        String employees = buildEmployeeBlock(s.getBookings());

        String fullNotice = isFinished
                ? "\n\n✅ Зміна завершилась."
                : isStarted
                ? "\n\n⏱ Зміна вже почалась. Запис закрито."
                : isFull
                ? "\n\n⚠️ Зміна повна. Слідкуй за оновленнями — щойно звільниться місце, пост оновиться."
                : isReserved
                ? "\n\n⏸ Запис призупинено. Слідкуй за оновленнями."
                : "";

        String title = isFinished
                ? "ℹ️ Зміна завершена"
                : isStarted
                ? "⏱ Зміна вже почалась"
                : isFull
                ? "⚠️ Зміна поки повна"
                : (isReserved ?  "⏸ Зміна у резерві" : "📢 Нова зміна - запис відкрито!");

        PostContent content = buildPostContent(
                s,
                title,
                fullNotice,
                !isFinished && !isFull && !isReserved && !isStarted
        );

        Optional<GroupShiftMessage> existingOpt = shiftMsgRepo.findByChatIdAndSlotId(chatId, s.getId());

        if (existingOpt.isEmpty() || forceNewPost) {
            return sendAndStore(bot, chatId, s, morningPost, eveningPost, content.text(), content.keyboard(), existingOpt.orElse(null));
        }

        GroupShiftMessage record = existingOpt.get();
        try {
            Message edited = executeEdit(bot, chatId, record.getMessageId(), content.text(), content.keyboard());
            storeUpdated(record, edited.getMessageId(), morningPost, eveningPost);
            return edited;
        } catch (TelegramApiException e) {
            if (isMessageMissing(e)) {
                log.warn("SlotPostService: message {} for slot {} was removed, re-publishing", record.getMessageId(), s.getId());
                return sendAndStore(bot, chatId, s, morningPost, eveningPost, content.text(), content.keyboard(), record);
            }
            log.error("SlotPostService: failed to edit message {} for slot {}: {}", record.getMessageId(), s.getId(), e.getMessage());
            throw e;
        }
    }

    public void markFinishedPost(TelegramLongPollingBot bot, Long chatId, Integer messageId, SlotDTO slot)
            throws TelegramApiException {
        PostContent content = buildPostContent(slot, "ℹ️ Зміна завершена", "", false);
        executeEdit(bot, chatId, messageId, content.text(), null);
    }

    public void markCancelledPost(TelegramLongPollingBot bot, Long chatId, Integer messageId, SlotDTO slot)
            throws TelegramApiException {
        PostContent content = buildPostContent(slot, "❌ Зміна скасована", "", false);
        executeEdit(bot, chatId, messageId, content.text(), null);
    }

    public void deleteSlotPost(TelegramLongPollingBot bot, Long chatId, Integer messageId)
            throws TelegramApiException {
        DeleteMessage delete = DeleteMessage.builder()
                .chatId(chatId.toString())
                .messageId(messageId)
                .build();
        bot.execute(delete);
    }

    private String buildEmployeeBlock(List<SlotBookingDTO> bookings) {
        List<SlotBookingDTO> safeBookings = Optional.ofNullable(bookings).orElse(Collections.emptyList());
        List<SlotBookingDTO> activeBookings = filterActiveBookings(safeBookings);

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

    private SlotDTO.SlotStatus resolveStatus(SlotDTO slot) {
        if (slot == null) {
            return SlotDTO.SlotStatus.READY;
        }

        return Optional.ofNullable(slot.getStatus()).orElse(SlotDTO.SlotStatus.READY);
    }

    private boolean isSlotStarted(SlotDTO slot) {
        if (slot == null || slot.getStart() == null) {
            return false;
        }
        return slot.getStart().isBefore(LocalDateTime.now(TimeUtil.UKR));
    }

    private boolean isSlotFinished(SlotDTO slot) {
        if (slot == null || slot.getEnd() == null) {
            return false;
        }
        return slot.getEnd().isBefore(LocalDateTime.now(TimeUtil.UKR));
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

    private PostContent buildPostContent(SlotDTO slot, String title, String fullNotice, boolean allowJoinButton) {
        String date = slot.getStart().toLocalDate().format(DATE);
        String day = slot.getStart().toLocalDate().format(DAY_OF_WEEK);
        String time = slot.getStart().toLocalTime().format(TIME) + " - " +
                slot.getEnd().toLocalTime().format(TIME);

        String innLine = slot.isInnRequired() ? " • ІПН обов'язковий" : "";

        int activeBookings = countActiveBookings(slot);
        SlotAvailability availability = SlotAvailabilityCalculator.calculate(slot.getCapacity(), activeBookings);

        int totalPlaces = availability.totalPlaces();

        String employees = buildEmployeeBlock(slot.getBookings());

        String text = """
                %s

                📍 %s
                🏙️ %s
                📅 %s (%s)
                🕒 %s
                👥 %d/%d зайнято%s

                %s
                """.formatted(
                title,
                escapeHtml(slot.getPlaceName()),
                escapeHtml(slot.getCityName()),
                date,
                day,
                time,
                availability.activeBookings(),
                totalPlaces,
                innLine,
                employees + fullNotice
        ).trim();

        InlineKeyboardMarkup kb = null;
        if (allowJoinButton) {
            InlineKeyboardButton join = new InlineKeyboardButton();
            join.setText("\uD83D\uDD17  Записатись на цю зміну у боті  \uD83D\uDD17 ");
            join.setUrl("https://t.me/" + config.getMainBotUsername() + "?start=slot_" + slot.getId());

            kb = new InlineKeyboardMarkup();
            kb.setKeyboard(List.of(List.of(join)));
        }

        return new PostContent(text, kb);
    }

    private record PostContent(String text, InlineKeyboardMarkup keyboard) {}

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
        if (e instanceof TelegramApiRequestException requestException) {
            Integer code = requestException.getErrorCode();
            String apiResponse = Optional.ofNullable(requestException.getApiResponse()).orElse("");
            String description = Optional.ofNullable(requestException.getMessage()).orElse("");
            String payload = (apiResponse + " " + description).toLowerCase();
            return Objects.equals(code, 400)
                    && (payload.contains("message to edit not found") || payload.contains("message to delete not found"));
        }
        return false;
    }
}
