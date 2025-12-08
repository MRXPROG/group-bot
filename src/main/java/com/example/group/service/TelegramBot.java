package com.example.group.service;

import com.example.group.config.BotConfig;
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
    private final ReminderScheduler reminderScheduler;
    private final FlowCleanerScheduler flowCleanerScheduler;
    private final LeaderboardScheduler leaderboardScheduler;
    private final PatternParser patternParser;
    private final SlotService slotService;
    private final BookingFlowService bookingFlow;
    private final MessageCleaner cleaner;
    private final SlotPostService slotPostService;
    private final BotSettingsService settingsService;
    private final LeaderboardUpdater leaderboardUpdater;

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
        reminderScheduler.start(this);
        flowCleanerScheduler.start(this);
        leaderboardScheduler.start(this);

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
                    "Команда доступна лише в групі"
            ));
            return;
        }

        Long chatId = chat.getId();
        settingsService.bindGroupChat(chatId);

        execute(new SendMessage(
                chatId.toString(),
                "✅ Бот доданий у групу та готовий працювати тут"
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

        var slotOpt = slotService.findMatchingSlot(req);
        if (slotOpt.isEmpty()) {
            Message reply = execute(new SendMessage(
                    chatId.toString(),
                    "⚠️ Не знайдено відповідної зміни. Перевірте дані."
            ));
            cleaner.deleteLater(this, chatId, reply.getMessageId(), 15);
            return;
        }

        bookingFlow.startFlowInGroup(this, msg, slotOpt.get(), req.getUserFullName());
    }

    @SneakyThrows
    private void handleCallback(CallbackQuery cbq) {

        String data = cbq.getData();
        Long userId = cbq.getFrom().getId();

        if (!data.startsWith("CFM:")) {
            answer(cbq.getId(), "Невідома команда");
            return;
        }

        String[] p = data.split(":");
        if (p.length != 4) {
            answer(cbq.getId(), "Невірна команда");
            return;
        }

        Long slotId = Long.parseLong(p[1]);
        Long initiatorId = Long.parseLong(p[2]);
        String decision = p[3];

        if (!userId.equals(initiatorId)) {
            answer(cbq.getId(), "❌ Ця кнопка не для вас.");
            return;
        }

        bookingFlow.handleDecision(this, cbq, slotId, decision);
    }

    @SneakyThrows
    public void publishSlotPost(Long chatId, SlotDTO slot) {
        slotPostService.publishSlotPost(this, chatId, slot);
    }

    @SneakyThrows
    public void sendReminder(Long chatId, Long messageId, SlotDTO slot, String prefix) {
        slotPostService.sendReminder(this, chatId, Math.toIntExact(messageId), slot, prefix);
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