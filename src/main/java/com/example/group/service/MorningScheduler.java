package com.example.group.service;

import com.example.group.controllers.MainBotApiClient;
import com.example.group.dto.SlotDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MorningScheduler {

    private final MainBotApiClient api;
    private final GroupConfig groupConfig; // где хранится chatId

    @Scheduled(cron = "0 0 7 * * *")
    public void run() {
        List<SlotDTO> slots = api.getUpcomingSlots();
        slots.forEach(slot -> bot.publishSlotPost(groupConfig.getChatId(), slot));
    }

    public void start(TelegramBot bot) {
        this.bot = bot;
    }

    private TelegramBot bot;
}