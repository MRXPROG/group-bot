package com.example.group.service;

import com.example.group.config.BotConfig;
import com.example.group.dto.ParsedShiftRequest;
import com.example.group.dto.SlotDTO;
import com.example.group.service.BotSettingsService;
import com.example.group.service.util.MessageCleaner;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramBot extends TelegramLongPollingBot {

    private final BotConfig config;

    private final MorningScheduler morningScheduler;
    private final EveningScheduler eveningScheduler;
    private final FlowCleanerScheduler flowCleanerScheduler;
    private final LeaderboardScheduler leaderboardScheduler;
    private final PatternParser patternParser;
    private final SlotService slotService;
    private final BookingFlowService bookingFlow;
    private final MessageCleaner cleaner;
    private final SlotPostService slotPostService;
    private final BotSettingsService settingsService;
    private final LeaderboardUpdater leaderboardUpdater;
    private final SlotPostUpdater slotPostUpdater;

    @Override
    public String getBotUsername() {
        return config.getBotName();
    }

    @Override
    public String getBotToken() {
        return config.getToken();
    }

    @PostConstruct
    public void init() {
        log.info("GroupShiftBot '{}' started", config.getBotName());

        morningScheduler.start(this);
        eveningScheduler.start(this);
        flowCleanerScheduler.start(this);
        leaderboardScheduler.start(this);
        slotPostUpdater.start(this);

        leaderboardUpdater.updatePinnedLeaderboard(this);
    }

    @Override
    @SneakyThrows
    public void onUpdateReceived(Update update) {

        if (update.hasMessage() && update.getMessage().hasText()) {
            handleMessage(update.getMessage());
            return;
        }
        if (update.hasCallbackQuery()) {
            handleCallback(update.getCallbackQuery());
        }
    }

    // ============================================================
    // ОБЩАЯ ОБРАБОТКА ТЕКСТОВЫХ СООБЩЕНИЙ
    // ============================================================
    @SneakyThrows
    private void handleMessage(Message msg) {
        String text = msg.getText().trim();

        if ("/bind".equalsIgnoreCase(text)) {
            handleBindCommand(msg);
            return;
        }

        handlePatternMessage(msg);
    }

    // ============================================================
    // /bind — привязка бота к группе
    // ============================================================
    @SneakyThrows
    private void handleBindCommand(Message msg) {
        Chat chat = msg.getChat();
        String chatType = chat.getType();
        boolean isGroup = "group".equalsIgnoreCase(chatType) || "supergroup".equalsIgnoreCase(chatType);

        if (!isGroup) {
            execute(new SendMessage(
                    msg.getChatId().toString(),
                    "Команда працює тільки в групі"
            ));
            return;
        }

        Long chatId = chat.getId();
        settingsService.bindGroupChat(chatId);

        leaderboardUpdater.updatePinnedLeaderboard(this);

        execute(new SendMessage(
                chatId.toString(),
                "✅ Прив'язано. Бот працює тут"
        ));
    }

    @SneakyThrows
    private void handlePatternMessage(Message msg) {

        Long chatId = msg.getChatId();
        Long userId = msg.getFrom().getId();
        String text = msg.getText().trim();

        var parsedOpt = patternParser.parse(text);
        if (parsedOpt.isEmpty()) return; // сообщение не по шаблону

        var req = parsedOpt.get();
        log.info("Pattern recognized from {} => {}", userId, req);

        Runnable cleanupUserMessage = () -> cleaner.deleteLater(this, chatId, msg.getMessageId(), 15);

        if (!hasValidName(req)) {
            Message reply = execute(new SendMessage(
                    chatId.toString(),
                    "ℹ️ Вкажи, будь ласка, ім'я та прізвище (два слова) у своєму повідомленні."
            ));
            cleaner.deleteLater(this, chatId, reply.getMessageId(), 15);
            cleanupUserMessage.run();
            return;
        }

        var matchResult = slotService.findMatchingSlot(req);
        if (!matchResult.found()) {
            Message reply = execute(new SendMessage(
                    chatId.toString(),
                    "⚠️ Не знайшов такої зміни. Перевір, чи все ввів правильно"
            ));
            cleaner.deleteLater(this, chatId, reply.getMessageId(), 15);
            cleanupUserMessage.run();
            return;
        }

        if (matchResult.ambiguous()) {
            Message reply = execute(new SendMessage(
                    chatId.toString(),
                    "ℹ️ Знайшлось кілька схожих змін. Напиши повідомлення ще раз з уточненням місця чи часу."
            ));
            cleaner.deleteLater(this, chatId, reply.getMessageId(), 15);
            cleanupUserMessage.run();
            return;
        }

        bookingFlow.startFlowInGroup(this, msg, matchResult.slot(), req.getUserFullName());
    }

    private boolean hasValidName(ParsedShiftRequest req) {
        String name = req.getUserFullName();
        if (name == null || name.isBlank()) {
            return false;
        }
        String[] parts = name.trim().split("\\s+");
        return parts.length >= 2;
    }

    @SneakyThrows
    private void handleCallback(CallbackQuery cbq) {

        String data = cbq.getData();
        Long userId = cbq.getFrom().getId();

        if (!data.startsWith("CFM:")) {
            answer(cbq.getId(), "Невідома дія");
            return;
        }

        String[] p = data.split(":");
        if (p.length != 4) {
            answer(cbq.getId(), "Хибна команда");
            return;
        }

        Long slotId = Long.parseLong(p[1]);
        Long initiatorId = Long.parseLong(p[2]);
        String decision = p[3];

        if (!userId.equals(initiatorId)) {
            answer(cbq.getId(), "❌ Ця кнопка не для тебе");
            return;
        }

        bookingFlow.handleDecision(this, cbq, slotId, decision);
    }

    @SneakyThrows
    public void publishSlotPost(Long chatId, SlotDTO slot) {
        slotPostService.publishSlotPost(this, chatId, slot);
    }

    private void answer(String callbackId, String text) {
        try {
            execute(
                    AnswerCallbackQuery.builder()
                            .callbackQueryId(callbackId)
                            .text(text)
                            .showAlert(false)
                            .build()
            );
        } catch (Exception ignored) {}
    }
}