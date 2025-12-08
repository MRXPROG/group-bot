package com.example.group.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class PinnedMessageService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("uk"));

    private final BotSettingsService botSettingsService;

    public void upsertLeaderboard(TelegramLongPollingBot bot, Long chatId, List<UserShiftCount> leaderboard) {
        if (bot == null) {
            log.warn("PinnedMessageService: bot instance is null");
            return;
        }
        if (chatId == null || chatId == 0) {
            log.warn("PinnedMessageService: group chat is not bound yet");
            return;
        }

        String text = formatLeaderboard(leaderboard);
        Integer pinnedMessageId = botSettingsService.getPinnedMessageId();

        if (pinnedMessageId == null) {
            createAndPin(bot, chatId, text);
            return;
        }

        try {
            EditMessageText edit = EditMessageText.builder()
                    .chatId(chatId.toString())
                    .messageId(pinnedMessageId)
                    .text(text)
                    .build();
            bot.execute(edit);
            ensurePinned(bot, chatId, pinnedMessageId);
        } catch (TelegramApiRequestException e) {
            log.warn("Pinned message update failed ({}). Recreating...", e.getMessage());
            createAndPin(bot, chatId, text);
        } catch (Exception e) {
            log.error("Unexpected error during pinned message update", e);
        }
    }

    private void createAndPin(TelegramLongPollingBot bot, Long chatId, String text) {
        try {
            Message msg = bot.execute(new SendMessage(chatId.toString(), text));
            botSettingsService.savePinnedMessageId(msg.getMessageId());
            ensurePinned(bot, chatId, msg.getMessageId());
        } catch (TelegramApiRequestException e) {
            log.warn("Failed to create or pin leaderboard message: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error while creating pinned message", e);
        }
    }

    private void ensurePinned(TelegramLongPollingBot bot, Long chatId, Integer messageId) {
        PinChatMessage pin = PinChatMessage.builder()
                .chatId(chatId.toString())
                .messageId(messageId)
                .disableNotification(true)
                .build();

        try {
            bot.execute(pin);
        } catch (TelegramApiRequestException e) {
            log.warn("Unable to pin leaderboard message ({}). Check bot permissions.", e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error while pinning message", e);
        }
    }

    private String formatLeaderboard(List<UserShiftCount> leaderboard) {
        List<UserShiftCount> sorted = Optional.ofNullable(leaderboard)
                .orElse(List.of())
                .stream()
                .sorted(Comparator.comparingInt(UserShiftCount::count).reversed())
                .toList();

        String body = sorted.isEmpty()
                ? "Поки немає даних по змінах."
                : buildLines(sorted);

        return """
                🏆 Топ виконавців змін (за весь час)

                %s

                Оновлено: %s
                """.formatted(body, LocalDateTime.now().format(TS)).trim();
    }

    private String buildLines(List<UserShiftCount> sorted) {
        AtomicBoolean firstLine = new AtomicBoolean(true);
        StringBuilder sb = new StringBuilder();

        IntStream.range(0, sorted.size()).forEach(idx -> {
            UserShiftCount row = sorted.get(idx);
            String fullName = ((Optional.ofNullable(row.firstName()).orElse("") + " " + Optional.ofNullable(row.lastName()).orElse("")).trim());
            if (fullName.isBlank()) {
                fullName = "Невідомий";
            }
            if (!firstLine.getAndSet(false)) {
                sb.append("\n");
            }
            sb.append(idx + 1)
                    .append(". ")
                    .append(fullName)
                    .append(" — ")
                    .append(row.count())
                    .append(" змін");
        });

        return sb.toString();
    }
}
