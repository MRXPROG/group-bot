package com.example.group.service.impl;
import com.example.group.dto.SlotDTO;
import com.example.group.entity.UserFlowState;
import com.example.group.repository.UserFlowStateRepository;
import com.example.group.service.BookingFlowService;
import com.example.group.service.MainBotApiClient;
import com.example.group.service.MessageCleaner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

import java.time.LocalDateTime;

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

        // если слот требует ІПН — проверяем
        if (slot.isInnRequired() && !mainApi.userHasInn(userId)) {
            // сообщение в группу (эпhemeral)
            try {
                Message warn = bot.execute(new SendMessage(
                        chatId.toString(),
                        "⚠️ Для цієї локації потрібен ІПН. Зверніться до адміністратора або додайте ІПН у головному боті."
                ));
                cleaner.deleteLater(bot, chatId, warn.getMessageId(), 20);
            } catch (Exception e) {
                log.warn("Failed to send INN warning: {}", e.getMessage());
            }
            return;
        }

        // если у юзера уже есть активный флоу — завершаем его
        stateRepo.findByUserId(userId)
                .ifPresent(state -> expireFlow(bot, state, null));

        // текст подтверждения
        String text = """
                Ви хочете записатись на зміну?
                📍 %s
                📅 %s
                🕒 %s – %s
                """.formatted(
                slot.getPlaceName(),
                slot.getStartTime().toLocalDate(),
                slot.getStartTime().toLocalTime(),
                slot.getEndTime().toLocalTime()
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
            answer(cbq, "⏳ Час підтвердження минув. Повторіть запис.");
            return;
        }

        if ("NO".equalsIgnoreCase(decision)) {
            // пользователь отказался
            expireFlow(bot, state, cbq);
            return;
        }

        if ("YES".equalsIgnoreCase(decision)) {
            // создаём запись через основной сервис
            try {
                mainApi.createBooking(userId, slotId);

                SendMessage done = new SendMessage(
                        state.getChatId().toString(),
                        "✅ Ваша заявка в обробці. Ви можете перевірити статус у головному боті."
                );
                done.setReplyToMessageId(state.getUserMessageId());

                Message m = bot.execute(done);
                // удалим уведомление через 15 секунд
                cleaner.deleteLater(bot, state.getChatId(), m.getMessageId(), 15);
            } catch (Exception e) {
                log.error("Failed to create booking: {}", e.getMessage());
                answer(cbq, "❌ Помилка створення заявки. Спробуйте пізніше.");
            }

            expireFlow(bot, state, cbq);
        }
    }

    @Override
    public void expireFlow(TelegramLongPollingBot bot, UserFlowState state, CallbackQuery cbqOrNull) {

        Long chatId = state.getChatId();

        // если это таймаут (cbqOrNull == null) — сообщаем юзеру
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

        // удаляем исходное сообщение юзера и наше подтверждение
        cleaner.deleteNow(bot, chatId, state.getUserMessageId());
        cleaner.deleteNow(bot, chatId, state.getBotMessageId());

        stateRepo.delete(state);

        // если есть callback — отвечаем, чтобы убрать "часики"
        if (cbqOrNull != null) {
            answer(cbqOrNull, "✅ Операція завершена.");
        }
    }

    // ========================= PRIVATE =========================

    private InlineKeyboardMarkup buildKeyboard(Long slotId, Long userId) {
        InlineKeyboardButton yes = new InlineKeyboardButton();
        yes.setText("✅ Так");
        yes.setCallbackData("CFM:" + slotId + ":" + userId + ":YES");

        InlineKeyboardButton no = new InlineKeyboardButton();
        no.setText("❌ Ні");
        no.setCallbackData("CFM:" + slotId + ":" + userId + ":NO");

        return new InlineKeyboardMarkup(List.of(List.of(yes, no)));
    }

    private void answer(CallbackQuery cbq, String text) {
        try {
            cbq.getBot().execute(
                    AnswerCallbackQuery.builder()
                            .callbackQueryId(cbq.getId())
                            .text(text)
                            .showAlert(false)
                            .build()
            );
        } catch (Exception ignored) {}
    }
}
