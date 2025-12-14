package com.example.group.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import java.time.ZoneId;
import java.time.ZonedDateTime;
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
    private static final ZoneId KYIV_ZONE = ZoneId.of("Europe/Kiev");

    private final BotSettingsService botSettingsService;

    public void upsertLeaderboard(TelegramLongPollingBot bot, Long chatId, List<UserScorePoints> leaderboard) {
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
                    .parseMode("HTML")
                    .build();
            bot.execute(edit);
            ensurePinned(bot, chatId, pinnedMessageId);
        } catch (TelegramApiRequestException e) {
            if (isMessageNotModified(e)) {
                log.debug("Pinned message unchanged; skipping recreation");
                ensurePinned(bot, chatId, pinnedMessageId);
                return;
            }

            log.warn("Pinned message update failed ({}). Recreating...", e.getMessage());
            createAndPin(bot, chatId, text);
        } catch (Exception e) {
            log.error("Unexpected error during pinned message update", e);
        }
    }

    private boolean isMessageNotModified(TelegramApiRequestException exception) {
        String apiResponse = Optional.ofNullable(exception.getApiResponse()).orElse("");
        String message = Optional.ofNullable(exception.getMessage()).orElse("");

        return apiResponse.toLowerCase(Locale.ROOT).contains("message is not modified")
                || message.toLowerCase(Locale.ROOT).contains("message is not modified");
    }

    private void createAndPin(TelegramLongPollingBot bot, Long chatId, String text) {
        try {
            Message msg = bot.execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .parseMode("HTML")
                    .build());
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

    private String formatLeaderboard(List<UserScorePoints> leaderboard) {
        List<UserScorePoints> sorted = Optional.ofNullable(leaderboard)
                .orElse(List.of())
                .stream()
                .filter(row -> row != null && row.scorePoints() > 0)
                .sorted(Comparator.comparingInt(UserScorePoints::scorePoints).reversed())
                .toList();

        return buildFormattedMessage(sorted);
    }

    private String buildFormattedMessage(List<UserScorePoints> sorted) {
        if (sorted.isEmpty()) {
            return """
                    \n 🏆 <u><b>Рейтинг активності учасників</b></u>

                    Будь першим! 💪

                    🕒 Оновлено: %s
                    """.formatted(formattedNow()).trim();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🏆 <u><b>Рейтинг активності учасників</b></u>\n\n");

        appendTopThree(sorted, sb);
        sb.append("\n\n");

        int upperBound = Math.min(sorted.size(), 10);
        if (upperBound > 3) {
            sb.append("\n<b>4–10 місця</b>\n\n");
            appendPlaces(sorted, sb, 3, upperBound);
            sb.append("\n\n");
        }

        if (sorted.size() > 10) {
            sb.append("<b>📘 Повний рейтинг</b>\n");
            sb.append("<blockquote expandable>");
            appendPlaces(sorted, sb, 10, sorted.size());
            sb.append("\n</blockquote>");
        }

        sb.append("\n🕒 Оновлено: ").append(formattedNow());

        return sb.toString().trim();
    }


    private void appendTopThree(List<UserScorePoints> sorted, StringBuilder sb) {
        String[] medals = {"🥇", "🥈", "🥉"};
        int top = Math.min(sorted.size(), 3);

        for (int idx = 0; idx < top; idx++) {
            UserScorePoints row = sorted.get(idx);
            sb.append(medals[idx])
                    .append(" ")
                    .append("<b>")
                    .append(idx + 1)
                    .append("</b>")
                    .append(". ")
                    .append(formatName(row))
                    .append(" — ")
                    .append(row.scorePoints())
                    .append(" поінтів");
            if (idx < top - 1) {
                sb.append("\n");
            }
        }
    }

    private void appendPlaces(List<UserScorePoints> sorted, StringBuilder sb, int fromInclusive, int toExclusive) {
        String[] placeIcons = {"4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟"};
        for (int idx = fromInclusive; idx < toExclusive; idx++) {
            UserScorePoints row = sorted.get(idx);
            String prefix;
            if (idx < 10) {
                prefix = placeIcons[idx - 3];
            } else {
                prefix = (idx + 1) + ".";
            }

            sb.append(prefix)
                    .append(" ")
                    .append(formatName(row))
                    .append(" — ")
                    .append(row.scorePoints())
                    .append(" поінтів");

            if (idx < toExclusive - 1) {
                sb.append("\n");
            }
        }
    }

    private String formatName(UserScorePoints row) {
        String fullName = (Optional.ofNullable(row.firstName()).orElse("") + " " + Optional.ofNullable(row.lastName()).orElse("")).trim();
        if (fullName.isBlank()) {
            fullName = "Невідомий";
        }
        return escapeHtml(fullName);
    }

    private String escapeHtml(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String formattedNow() {
        return ZonedDateTime.now(KYIV_ZONE).format(TS);
    }
}
