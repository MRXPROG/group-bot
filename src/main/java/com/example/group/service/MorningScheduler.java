package com.example.group.service;

import com.example.group.controllers.MainBotApiClient;
import com.example.group.dto.SlotDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MorningScheduler {

    private final MainBotApiClient api;
    private final BotSettingsService settingsService;
    private final SlotPostService slotPostService;

    private TelegramBot bot;

    @Scheduled(cron = "0 */2 * * * *")
    public void run() {
        if (bot == null) {
            log.warn("MorningScheduler: bot is not set yet");
            return;
        }
        Long groupChatId = settingsService.getGroupChatId();
        if (groupChatId == null || groupChatId == 0) {
            log.warn("MorningScheduler: group chat is not bound yet");
            return;
        }

        LocalDate today = LocalDate.now();
        Set<LocalDate> datesToPost = new LinkedHashSet<>();
        datesToPost.add(today);
        datesToPost.add(today.plusDays(1));

        DayOfWeek dayOfWeek = today.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.FRIDAY) {
            datesToPost.add(today.plusDays(2));
            datesToPost.add(today.plusDays(3));
        } else if (dayOfWeek == DayOfWeek.SATURDAY) {
            datesToPost.add(today.plusDays(2));
        }

        Set<Long> postedSlotIds = new HashSet<>();

        datesToPost.stream()
                .flatMap(date -> api.getSlotsForDate(date).stream())
                .filter(slot -> postedSlotIds.add(slot.getId()))
                .forEach(slot -> {
                    try {
                        slotPostService.publishSlotPost(bot, groupChatId, slot, true, false, true);
                    } catch (Exception e) {
                        log.error("Failed to publish slot {}: {}", slot.getId(), e.getMessage());
                    }
                });
    }

    public void start(TelegramBot bot) {
        this.bot = bot;
    }
}
