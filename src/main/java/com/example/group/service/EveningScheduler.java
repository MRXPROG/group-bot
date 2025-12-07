package com.example.group.service;

import com.example.group.config.BotConfig;
import com.example.group.controllers.MainBotApiClient;
import com.example.group.dto.SlotDTO;
import com.example.group.repository.GroupShiftMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EveningScheduler {

    private final MainBotApiClient api;
    private final BotConfig config;
    private final SlotPostService slotPostService;
    private final GroupShiftMessageRepository shiftMsgRepo;

    private TelegramBot bot;

    @Scheduled(cron = "0 0 21 * * *")
    public void run() {
        if (bot == null) {
            log.warn("EveningScheduler: bot is not set yet");
            return;
        }

        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);

        List<SlotDTO> tomorrowSlots = api.getSlotsForDate(tomorrow);
        if (tomorrowSlots.isEmpty()) {
            return;
        }

        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.atTime(23, 59, 59);

        Set<Long> morningPosted = shiftMsgRepo
                .findAllByPostedAtBetweenAndMorningPostIsTrue(startOfDay, endOfDay)
                .stream()
                .map(msg -> msg.getSlotId())
                .collect(Collectors.toSet());

        tomorrowSlots.stream()
                .filter(slot -> !morningPosted.contains(slot.getId()))
                .forEach(slot -> {
                    try {
                        slotPostService.publishSlotPost(bot, config.getGroupChatId(), slot, false, true);
                    } catch (Exception e) {
                        log.error("Failed to publish evening slot {}: {}", slot.getId(), e.getMessage());
                    }
                });
    }

    public void start(TelegramBot bot) {
        this.bot = bot;
    }
}
