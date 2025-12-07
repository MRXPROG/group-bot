package com.example.group.service;

import com.example.group.config.BotConfig;
import com.example.group.controllers.MainBotApiClient;
import com.example.group.dto.SlotDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MorningScheduler {

    private final MainBotApiClient api;
    private final BotConfig config;
    private final SlotPostService slotPostService;

    private TelegramBot bot;

    @Scheduled(cron = "0 0 9 * * *")
    public void run() {
        if (bot == null) {
            log.warn("MorningScheduler: bot is not set yet");
            return;
        }
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        List<SlotDTO> slots = api.getUpcomingSlots();
        slots.stream()
                .filter(slot -> !slot.getStartTime().toLocalDate().isBefore(tomorrow))
                .forEach(slot -> {
                    try {
                        slotPostService.publishSlotPost(bot, config.getGroupChatId(), slot, true, false);
                    } catch (Exception e) {
                        log.error("Failed to publish slot {}: {}", slot.getId(), e.getMessage());
                    }
                });
    }

    public void start(TelegramBot bot) {
        this.bot = bot;
    }
}
