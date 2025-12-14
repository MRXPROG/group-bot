package com.example.group.service;

import com.example.group.controllers.MainBotApiClient;
import com.example.group.dto.SlotDTO;
import com.example.group.service.BotSettingsService;
import com.example.group.repository.GroupShiftMessageRepository;
import com.example.group.model.GroupShiftMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class EveningScheduler {

    private final MainBotApiClient api;
    private final BotSettingsService settingsService;
    private final SlotPostService slotPostService;
    private final GroupShiftMessageRepository shiftMsgRepo;

    private TelegramBot bot;

    @Scheduled(cron = "0 */4 * * * *")
    public void run() {
        if (bot == null) {
            log.warn("EveningScheduler: bot is not set yet");
            return;
        }

        Long groupChatId = settingsService.getGroupChatId();
        if (groupChatId == null || groupChatId == 0) {
            log.warn("EveningScheduler: group chat is not bound yet");
            return;
        }

        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);

        List<SlotDTO> todaySlots = api.getSlotsForDate(today);
        List<SlotDTO> tomorrowSlots = api.getSlotsForDate(tomorrow);

        Set<Long> postedSlotIds = new HashSet<>(
                shiftMsgRepo.findAllByChatId(groupChatId).stream()
                        .map(GroupShiftMessage::getSlotId)
                        .toList()
        );

        Stream.concat(todaySlots.stream(), tomorrowSlots.stream())
                .filter(slot -> postedSlotIds.add(slot.getId()))
                .forEach(slot -> {
                    try {
                        slotPostService.publishSlotPost(bot, groupChatId, slot, false, true);
                    } catch (Exception e) {
                        log.error("Failed to publish evening slot {}: {}", slot.getId(), e.getMessage());
                    }
                });
    }

    public void start(TelegramBot bot) {
        this.bot = bot;
    }
}
