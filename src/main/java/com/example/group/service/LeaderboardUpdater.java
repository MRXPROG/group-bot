package com.example.group.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderboardUpdater {

    private final PinnedMessageService pinnedMessageService;
    private final ShiftStatsService shiftStatsService;
    private final BotSettingsService botSettingsService;

    public void updatePinnedLeaderboard(TelegramLongPollingBot bot) {
        Long chatId = botSettingsService.getGroupChatId();
        if (chatId == null || chatId == 0) {
            log.warn("LeaderboardUpdater: group chat is not bound yet");
            return;
        }

        pinnedMessageService.upsertLeaderboard(bot, chatId, shiftStatsService.getLeaderboard());
    }
}
