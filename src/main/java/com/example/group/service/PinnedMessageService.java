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

@Slf4j
@Service
@RequiredArgsConstructor
public class PinnedMessageService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("uk"));
    private static final int LEADERBOARD_SIZE = 10;
    private static final String VACANT_PLACE = "Порожньо — місце для тебе";

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
                .filter(row -> row != null && row.count() > 0)
                .sorted(Comparator.comparingInt(UserShiftCount::count).reversed())
                .limit(LEADERBOARD_SIZE)
                .toList();

        String body = buildLines(sorted);

        return """
                🏆 Топ-10 активних (за змінами)

                %s

                Оновлено • %s
                """.formatted(body, LocalDateTime.now().format(TS)).trim();
    }

    private String buildLines(List<UserShiftCount> sorted) {
        StringBuilder sb = new StringBuilder();

        for (int idx = 0; idx < LEADERBOARD_SIZE; idx++) {
            if (idx > 0) {
                sb.append("\n");
            }

            if (idx < sorted.size()) {
                UserShiftCount row = sorted.get(idx);
                String fullName = (Optional.ofNullable(row.firstName()).orElse("") + " " + Optional.ofNullable(row.lastName()).orElse("")).trim();
                if (fullName.isBlank()) {
                    fullName = "Невідомий";
                }
                sb.append(idx + 1)
                        .append(". ")
                        .append(fullName)
                        .append(" — ")
                        .append(row.count())
                        .append(" змін");
            } else {
                sb.append(idx + 1)
                        .append(". ")
                        .append(VACANT_PLACE);
            }
        }

        return sb.toString();
    }
}
