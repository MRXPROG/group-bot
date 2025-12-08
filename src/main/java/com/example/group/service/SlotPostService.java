package com.example.group.service;

import com.example.group.config.BotConfig;
import com.example.group.dto.BookingStatusDTO;
import com.example.group.dto.SlotBookingDTO;
import com.example.group.dto.SlotDTO;
import com.example.group.model.GroupShiftMessage;
import com.example.group.repository.GroupShiftMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
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
                s.getBookedCount(),
                s.getCapacity(),
                innLine,
                employees
        );

        InlineKeyboardButton join = new InlineKeyboardButton();
        join.setText("🟢 Записатись через бота");
        join.setUrl("https://t.me/" + config.getMainBotUsername() + "?start=slot_" + s.getId());

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup();
        kb.setKeyboard(List.of(List.of(join)));

        SendMessage sm = new SendMessage(chatId.toString(), text);
        sm.setReplyMarkup(kb);

        Message sent = bot.execute(sm);

        GroupShiftMessage gsm = GroupShiftMessage.builder()
                .chatId(chatId)
                .messageId(sent.getMessageId())
                .slotId(s.getId())
                .postedAt(LocalDateTime.now())
                .morningPost(morningPost)
                .eveningPost(eveningPost)
                .build();

        shiftMsgRepo.save(gsm);

        return sent;
    }

    public void sendReminder(TelegramLongPollingBot bot,
                             Long chatId,
                             Integer messageId,
                             SlotDTO s,
                             String prefix) throws Exception {

        int free = s.getCapacity() - s.getBookedCount();
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
        if (safeBookings.isEmpty()) {
            return "Наразі немає записів.";
        }

        String list = safeBookings.stream()
                .map(this::formatBookingLine)
                .collect(Collectors.joining("\n"));

        return "Записані учасники:\n" + list;
    }

    private String formatBookingLine(SlotBookingDTO booking) {
        String statusIcon = switch (Optional.ofNullable(booking.getStatus()).orElse(BookingStatus.PENDING)) {
            case CONFIRMED, COMPLETED -> "✅";
            case CANCELLED -> "⏹️";
            case PENDING -> "⏳";
        };

        String name = booking.getFullName();
        if (name == null || name.isBlank()) {
            name = booking.getUserId() != null ? "Користувач " + booking.getUserId() : "Невідомий";
        }

        return statusIcon + " " + name;
    }
}
