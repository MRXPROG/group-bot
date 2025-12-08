package com.example.group.service.impl;

import com.example.group.controllers.MainBotApiClient;
import com.example.group.dto.SlotDTO;
import com.example.group.model.UserFlowState;
import com.example.group.repository.UserFlowStateRepository;
import com.example.group.service.BookingFlowService;
import com.example.group.service.util.MessageCleaner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingFlowServiceImpl implements BookingFlowService {

    private final UserFlowStateRepository stateRepo;
    private final MainBotApiClient mainApi;
    private final MessageCleaner cleaner;

    @Override
    public void startFlowInGroup(TelegramLongPollingBot bot, Message msg, SlotDTO slot, String userFullName) {
        Long chatId = msg.getChatId();
        Long userId = msg.getFrom().getId();
        NameParts names = resolveNames(msg, userFullName);

        stateRepo.findByUserId(userId)
                .ifPresent(state -> expireFlow(bot, state, null));

        String innLine = slot.isInnRequired()
                ? "\nℹ️ Для цієї локації потрібен ІПН."
                : "";

        String text = ("""
                Ви хочете записатись на зміну?
                📍 %s
                📅 %s
                🕒 %s – %s%s
                """
        ).formatted(
                slot.getPlaceName(),
                slot.getStart().toLocalDate(),
                slot.getStart().toLocalTime(),
                slot.getEnd().toLocalTime(),
                innLine
        );

        SendMessage sm = new SendMessage(chatId.toString(), text);
        sm.setReplyToMessageId(msg.getMessageId());
        sm.setReplyMarkup(buildKeyboard(slot.getId(), userId));

        try {
            Message botMsg = bot.execute(sm);

            UserFlowState state = UserFlowState.builder()
                    .userId(userId)
                    .chatId(chatId)
                    .firstName(names.firstName())
                    .lastName(names.lastName())
                    .userMessageId(msg.getMessageId())
                    .botMessageId(botMsg.getMessageId())
                    .slotId(slot.getId())
                    .expiresAt(LocalDateTime.now().plusSeconds(30))
                    .build();

            stateRepo.save(state);
        } catch (Exception e) {
            log.error("Failed to send confirmation message", e);
        }
    }

    @Override
    public void handleDecision(TelegramLongPollingBot bot, CallbackQuery cbq, Long slotId, String decision) {
        Long userId = cbq.getFrom().getId();

        UserFlowState state = stateRepo.findByUserId(userId).orElse(null);
        if (state == null || !state.getSlotId().equals(slotId)) {
            answer(bot, cbq, "⏳ Час підтвердження минув. Повторіть запис.");
            return;
        }

        if ("NO".equalsIgnoreCase(decision)) {
            expireFlow(bot, state, cbq);
            return;
        }

        if ("YES".equalsIgnoreCase(decision)) {
            try {
                String firstName = state.getFirstName();
                String lastName = state.getLastName();

                // fallback in case user changed name during the flow or we didn't capture it
                if (firstName == null && cbq.getFrom() != null) {
                    firstName = cbq.getFrom().getFirstName();
                }
                if (lastName == null && cbq.getFrom() != null) {
                    lastName = cbq.getFrom().getLastName();
                }

                mainApi.createBooking(userId, slotId, firstName, lastName);

                SendMessage done = new SendMessage(
                        state.getChatId().toString(),
                        "✅ Ваша заявка в обробці. Ви можете перевірити статус у головному боті."
                );
                done.setReplyToMessageId(state.getUserMessageId());

                Message m = bot.execute(done);
                cleaner.deleteLater(bot, state.getChatId(), m.getMessageId(), 15);
            } catch (Exception e) {
                log.error("Failed to create booking: {}", e.getMessage());
                answer(bot, cbq, "❌ Помилка створення заявки. Спробуйте пізніше.");
            }

            expireFlow(bot, state, cbq);
        }
    }

    @Override
    public void expireFlow(TelegramLongPollingBot bot, UserFlowState state, CallbackQuery cbqOrNull) {
        Long chatId = state.getChatId();

        if (cbqOrNull == null) {
            try {
                SendMessage timeoutMsg = new SendMessage(
                        chatId.toString(),
                        "⏰ Час на підтвердження минув. Повторіть запис пізніше."
                );
                timeoutMsg.setReplyToMessageId(state.getUserMessageId());
                Message m = bot.execute(timeoutMsg);
                cleaner.deleteLater(bot, chatId, m.getMessageId(), 15);
            } catch (Exception e) {
                log.warn("Failed to send timeout message: {}", e.getMessage());
            }
        }

        cleaner.deleteNow(bot, chatId, state.getUserMessageId());
        cleaner.deleteNow(bot, chatId, state.getBotMessageId());

        stateRepo.delete(state);

        if (cbqOrNull != null) {
            answer(bot, cbqOrNull, "✅ Операція завершена.");
        }
    }

    private NameParts resolveNames(Message msg, String userFullName) {
        String firstName = trimToNull(msg.getFrom().getFirstName());
        String lastName = trimToNull(msg.getFrom().getLastName());

        NameParts parsed = splitFullName(userFullName);

        if (parsed.firstName() != null && parsed.lastName() != null) {
            firstName = parsed.firstName();
            lastName = parsed.lastName();
        } else {
            if (firstName == null) firstName = parsed.firstName();
            if (lastName == null) lastName = parsed.lastName();
        }

        if (firstName == null) firstName = "Невідомо";
        if (lastName == null) lastName = "Невідомо";

        return new NameParts(firstName, lastName);
    }

    private NameParts splitFullName(String fullName) {
        if (fullName == null) {
            return new NameParts(null, null);
        }

        String[] parts = Arrays.stream(fullName.trim().split("\\s+")).filter(s -> !s.isBlank()).toArray(String[]::new);
        if (parts.length < 2) {
            return new NameParts(null, null);
        }

        String firstName = parts[0];
        String lastName = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));

        return new NameParts(firstName, lastName);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private InlineKeyboardMarkup buildKeyboard(Long slotId, Long userId) {
        InlineKeyboardButton yes = new InlineKeyboardButton();
        yes.setText("✅ Так");
        yes.setCallbackData("CFM:" + slotId + ":" + userId + ":YES");

        InlineKeyboardButton no = new InlineKeyboardButton();
        no.setText("❌ Ні");
        no.setCallbackData("CFM:" + slotId + ":" + userId + ":NO");

        return new InlineKeyboardMarkup(List.of(List.of(yes, no)));
    }

    private void answer(TelegramLongPollingBot bot, CallbackQuery cbq, String text) {
        try {
            bot.execute(
                    AnswerCallbackQuery.builder()
                            .callbackQueryId(cbq.getId())
                            .text(text)
                            .showAlert(false)
                            .build()
            );
        } catch (Exception ignored) {
        }
    }

    private record NameParts(String firstName, String lastName) {}
}
