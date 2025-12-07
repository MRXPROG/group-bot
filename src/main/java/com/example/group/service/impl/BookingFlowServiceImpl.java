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
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingFlowServiceImpl implements BookingFlowService {

    private final UserFlowStateRepository stateRepo;
    private final MainBotApiClient mainApi;
    private final MessageCleaner cleaner;

    @Override
    public void startFlowInGroup(TelegramLongPollingBot bot, Message msg, SlotDTO slot) {
        Long chatId = msg.getChatId();
        Long userId = msg.getFrom().getId();

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
                slot.getStartTime().toLocalDate(),
                slot.getStartTime().toLocalTime(),
                slot.getEndTime().toLocalTime(),
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
                mainApi.createBooking(userId, slotId);

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
}
