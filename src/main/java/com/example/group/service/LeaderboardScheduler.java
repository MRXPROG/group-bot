package com.example.group.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderboardScheduler {

    private final LeaderboardUpdater leaderboardUpdater;

    private TelegramBot bot;

    public void start(TelegramBot bot) {
        this.bot = bot;
    }

    @Scheduled(cron = "0 */1 * * * *")
    public void refreshPinnedMessage() {
        if (bot == null) {
            log.warn("LeaderboardScheduler: bot is not set yet");
            return;
        }
        leaderboardUpdater.updatePinnedLeaderboard(bot);
    }
}
