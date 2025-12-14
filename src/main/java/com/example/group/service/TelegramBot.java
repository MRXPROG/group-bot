package com.example.group.service;

import com.example.group.config.BotConfig;
import com.example.group.controllers.MainBotApiClient;
import com.example.group.dto.ParsedShiftRequest;
import com.example.group.dto.SlotDTO;
import com.example.group.service.BotSettingsService;
import com.example.group.service.BookingRequestCache;
import com.example.group.repository.GroupShiftMessageRepository;
import com.example.group.service.util.MessageCleaner;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.format.DateTimeFormatter;
import java.util.List;

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
    private final BookingRequestCache requestCache;
    private final GroupShiftMessageRepository shiftMsgRepo;
    private final MainBotApiClient mainApi;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

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

        if (msg.getReplyToMessage() != null && tryHandleReplyFlow(msg)) {
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
                    "Працівники доступні тільки в групі"
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

        askForBookingIntent(msg, req.getUserFullName(), matchResult.slots(), null);
    }

    private boolean hasValidName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String[] parts = name.trim().split("\\s+");
        return parts.length >= 2;
    }

    @SneakyThrows
    private boolean ensureValidNamePresent(BookingRequestCache.BookingRequestState state) {
        String name = state.getUserFullName();
        if (!hasValidName(name)) {
            name = patternParser.extractNameOnly(state.getUserMessage().getText()).orElse(null);
        }

        if (!hasValidName(name)) {
            Message reply = execute(new SendMessage(
                    state.getChatId().toString(),
                    "ℹ️ Вкажи, будь ласка, ім'я та прізвище (два слова) у своєму повідомленні."
            ));
            cleaner.deleteLater(this, state.getChatId(), reply.getMessageId(), 5);
            cleaner.deleteLater(this, state.getChatId(), state.getUserMessage().getMessageId(), 5);
            requestCache.remove(state.getToken());
            return false;
        }

        state.setUserFullName(name.trim());
        requestCache.update(state);
        return true;
    }

    @SneakyThrows
    private void handleCallback(CallbackQuery cbq) {

        String data = cbq.getData();
        Long userId = cbq.getFrom().getId();

        if (data.startsWith("INT:")) {
            handleIntentDecision(cbq, data);
            return;
        }

        if (data.startsWith("SLT:")) {
            handleSlotSelection(cbq, data);
            return;
        }

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
    private boolean tryHandleReplyFlow(Message msg) {
        Long chatId = msg.getChatId();
        Integer replyId = msg.getReplyToMessage().getMessageId();

        var shiftMessageOpt = shiftMsgRepo.findByChatIdAndMessageId(chatId, replyId);
        if (shiftMessageOpt.isEmpty()) {
            return false;
        }

        Long slotId = shiftMessageOpt.get().getSlotId();
        SlotDTO slot = mainApi.getSlotById(slotId);
        if (slot == null) {
            Message reply = execute(new SendMessage(
                    chatId.toString(),
                    "⚠️ Не можу знайти цю зміну. Спробуй іншу."
            ));
            cleaner.deleteLater(this, chatId, reply.getMessageId(), 15);
            cleaner.deleteLater(this, chatId, msg.getMessageId(), 15);
            return true;
        }

        String name = patternParser.extractNameOnly(msg.getText()).orElse(null);
        askForBookingIntent(msg, name, List.of(slot), msg.getReplyToMessage().getMessageId());
        return true;
    }

    @SneakyThrows
    private void askForBookingIntent(Message msg, String userFullName, List<SlotDTO> slots, Integer replyToMessageId) {
        if (slots == null || slots.isEmpty()) {
            Message reply = execute(new SendMessage(
                    msg.getChatId().toString(),
                    "⚠️ Не знайшов такої зміни. Перевір, чи все ввів правильно"
            ));
            cleaner.deleteLater(this, msg.getChatId(), reply.getMessageId(), 15);
            return;
        }

        String token = requestCache.store(msg, userFullName, slots);
        SendMessage prompt = new SendMessage(
                msg.getChatId().toString(),
                "Хочете записатися на зміну?"
        );
        prompt.setReplyToMessageId(replyToMessageId != null ? replyToMessageId : msg.getMessageId());
        prompt.setReplyMarkup(buildIntentKeyboard(token));

        execute(prompt);
    }

    @SneakyThrows
    private void handleIntentDecision(CallbackQuery cbq, String data) {
        String[] parts = data.split(":");
        if (parts.length != 3) {
            answer(cbq.getId(), "Хибна команда");
            return;
        }

        String token = parts[1];
        String decision = parts[2];
        var stateOpt = requestCache.get(token);

        if (stateOpt.isEmpty()) {
            answer(cbq.getId(), "⏳ Час вийшов. Створи нову заявку.");
            cleaner.deleteNow(this, cbq.getMessage().getChatId(), cbq.getMessage().getMessageId());
            return;
        }

        BookingRequestCache.BookingRequestState state = stateOpt.get();
        if (!state.getUserId().equals(cbq.getFrom().getId())) {
            answer(cbq.getId(), "❌ Ця кнопка не для тебе");
            return;
        }

        cleaner.deleteNow(this, cbq.getMessage().getChatId(), cbq.getMessage().getMessageId());

        if ("NO".equalsIgnoreCase(decision)) {
            requestCache.remove(token);
            answer(cbq.getId(), "Добре, нічого не роблю.");
            return;
        }

        if (!ensureValidNamePresent(state)) {
            answer(cbq.getId(), "ℹ️ Додай ім'я та прізвище у повідомленні");
            return;
        }

        state.setControlMessageId(null);
        requestCache.update(state);

        if (state.getSlots().size() == 1) {
            requestCache.remove(token);
            try {
                startBookingFlow(state, state.getSlots().get(0), state.getUserMessage());
                answer(cbq.getId(), "✅ Створюю заявку");
            } catch (Exception e) {
                log.error("Failed to start booking flow: {}", e.getMessage());
                answer(cbq.getId(), "❌ Не вийшло створити заявку. Спробуй пізніше.");
            }
            return;
        }

        showSlotChoice(cbq, state);
        answer(cbq.getId(), "✅");
    }

    @SneakyThrows
    private void handleSlotSelection(CallbackQuery cbq, String data) {
        String[] parts = data.split(":");
        if (parts.length != 3) {
            answer(cbq.getId(), "Хибна команда");
            return;
        }

        String token = parts[1];
        String action = parts[2];
        var stateOpt = requestCache.get(token);

        if (stateOpt.isEmpty()) {
            answer(cbq.getId(), "⏳ Час вийшов. Створи нову заявку.");
            cleaner.deleteNow(this, cbq.getMessage().getChatId(), cbq.getMessage().getMessageId());
            return;
        }

        BookingRequestCache.BookingRequestState state = stateOpt.get();
        if (!state.getUserId().equals(cbq.getFrom().getId())) {
            answer(cbq.getId(), "❌ Ця кнопка не для тебе");
            return;
        }

        if ("BOOK".equalsIgnoreCase(action)) {
            requestCache.remove(token);
            SlotDTO slot = state.getSlots().get(state.getCurrentIndex());
            try {
                startBookingFlow(state, slot, state.getUserMessage());
                answer(cbq.getId(), "✅ Створюю заявку");
            } catch (Exception e) {
                log.error("Failed to start booking flow: {}", e.getMessage());
                answer(cbq.getId(), "❌ Не вийшло створити заявку. Спробуй пізніше.");
            } finally {
                cleaner.deleteNow(this, state.getChatId(), cbq.getMessage().getMessageId());
                cleaner.deleteNow(this, state.getChatId(), state.getUserMessage().getMessageId());
            }
            return;
        }

        if ("CANCEL".equalsIgnoreCase(action)) {
            requestCache.remove(token);
            answer(cbq.getId(), "Скасовано");
            cleaner.deleteNow(this, state.getChatId(), cbq.getMessage().getMessageId());
            cleaner.deleteNow(this, state.getChatId(), state.getUserMessage().getMessageId());
            return;
        }

        int total = state.getSlots().size();
        if ("NEXT".equalsIgnoreCase(action)) {
            state.setCurrentIndex((state.getCurrentIndex() + 1) % total);
        } else if ("PREV".equalsIgnoreCase(action)) {
            state.setCurrentIndex((state.getCurrentIndex() - 1 + total) % total);
        }

        requestCache.update(state);
        showSlotChoice(cbq, state);
        answer(cbq.getId(), "✅");
    }

    @SneakyThrows
    private void startBookingFlow(BookingRequestCache.BookingRequestState state, SlotDTO slot, Message sourceMessage) {
        bookingFlow.startFlowInGroup(this, sourceMessage, slot, state.getUserFullName());
        cleaner.deleteNow(this, state.getChatId(), sourceMessage.getMessageId());
    }

    @SneakyThrows
    private void showSlotChoice(CallbackQuery cbq, BookingRequestCache.BookingRequestState state) {
        SlotDTO slot = state.getSlots().get(state.getCurrentIndex());
        int total = state.getSlots().size();
        int index = state.getCurrentIndex() + 1;

        String text = formatSlot(slot, index, total, state.getUserFullName());

        if (state.getControlMessageId() == null) {
            SendMessage message = new SendMessage(state.getChatId().toString(), text);
            message.setReplyMarkup(buildSlotNavigationKeyboard(state.getToken(), total));
            Message sent = execute(message);
            state.setControlMessageId(sent.getMessageId());
        } else {
            EditMessageText edit = new EditMessageText();
            edit.setChatId(state.getChatId().toString());
            edit.setMessageId(state.getControlMessageId());
            edit.setText(text);
            edit.setReplyMarkup(buildSlotNavigationKeyboard(state.getToken(), total));

            Message edited = execute(edit);
            state.setControlMessageId(edited.getMessageId());
        }

        requestCache.update(state);
    }

    private String formatSlot(SlotDTO slot, int index, int total, String userFullName) {
        String displayName = hasValidName(userFullName) ? userFullName : "—";
        String innLine = slot.isInnRequired() ? " • ІПН обов'язковий" : "";
        return ("""
                Знайшлось %d змін за твоїм запитом.
                Сторінка %d/%d
                📍 %s
                📅 %s • %s - %s%s
                👤 Ім'я в заявці: %s
                """)
                .formatted(
                        total,
                        index,
                        total,
                        slot.getPlaceName(),
                        slot.getStart().toLocalDate().format(DATE),
                        slot.getStart().toLocalTime().format(TIME),
                        slot.getEnd().toLocalTime().format(TIME),
                        innLine,
                        displayName
                ).trim();
    }

    private InlineKeyboardMarkup buildIntentKeyboard(String token) {
        InlineKeyboardButton yes = new InlineKeyboardButton();
        yes.setText("✅ Так");
        yes.setCallbackData("INT:" + token + ":YES");

        InlineKeyboardButton no = new InlineKeyboardButton();
        no.setText("❌ Ні");
        no.setCallbackData("INT:" + token + ":NO");

        return new InlineKeyboardMarkup(List.of(List.of(yes, no)));
    }

    private InlineKeyboardMarkup buildSlotNavigationKeyboard(String token, int total) {
        InlineKeyboardButton prev = new InlineKeyboardButton();
        prev.setText("◀️");
        prev.setCallbackData("SLT:" + token + ":PREV");

        InlineKeyboardButton next = new InlineKeyboardButton();
        next.setText("▶️");
        next.setCallbackData("SLT:" + token + ":NEXT");

        InlineKeyboardButton book = new InlineKeyboardButton();
        book.setText("✅ Обрати");
        book.setCallbackData("SLT:" + token + ":BOOK");

        InlineKeyboardButton cancel = new InlineKeyboardButton();
        cancel.setText("✖️ Скасувати");
        cancel.setCallbackData("SLT:" + token + ":CANCEL");

        return new InlineKeyboardMarkup(
                List.of(
                        List.of(prev, next),
                        List.of(book, cancel)
                )
        );
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