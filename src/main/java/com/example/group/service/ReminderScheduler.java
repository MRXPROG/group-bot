package com.example.group.service;

import com.example.group.controllers.MainBotApiClient;
import com.example.group.dto.SlotDTO;
import com.example.group.model.GroupShiftMessage;
import com.example.group.model.SlotReminderHistory;
import com.example.group.repository.GroupShiftMessageRepository;
import com.example.group.repository.SlotReminderHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderScheduler {

    private final GroupShiftMessageRepository shiftMsgRepo;
    private final SlotReminderHistoryRepository reminderRepo;
    private final MainBotApiClient api;

    private TelegramBot bot;

    private static final List<Integer> REMINDER_HOURS = List.of(24, 12, 6);

    public void start(TelegramBot bot) {
        this.bot = bot;
    }

    @Scheduled(fixedDelay = 60_000)
    public void checkReminders() {
        if (bot == null) {
            log.warn("ReminderScheduler: bot is not set yet");
            return;
        }

        List<GroupShiftMessage> messages = shiftMsgRepo.findAll();
        if (messages.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();

        for (GroupShiftMessage msg : messages) {
            SlotDTO slot = api.getSlotById(msg.getSlotId());
            if (slot == null) continue;

            long hoursUntil = Duration.between(now, slot.getStartTime()).toHours();

            for (Integer mark : REMINDER_HOURS) {
                if (hoursUntil == mark) {

                    boolean alreadySent =
                            reminderRepo.existsBySlotIdAndHoursBefore(slot.getId(), mark);

                    if (!alreadySent) {
                        sendReminder(msg, slot, mark);

                        reminderRepo.save(
                                SlotReminderHistory.builder()
                                        .slotId(slot.getId())
                                        .hoursBefore(mark)
                                        .sentAt(now)
                                        .build()
                        );
                    }
                }
            }
        }
    }

    private void sendReminder(GroupShiftMessage msg, SlotDTO slot, int hoursBefore) {
        String prefix = switch (hoursBefore) {
            case 24 -> "⏰ До зміни залишилася доба!";
            case 12 -> "⏰ До зміни залишилось 12 годин!";
            case 6 -> "⏰ До зміни залишилось 6 годин!";
            default -> "Нагадування:";
        };

        try {
            bot.sendReminder(msg.getChatId(), Long.valueOf(msg.getMessageId()), slot, prefix);
        } catch (Exception e) {
            log.error("Failed to send reminder for slot {}: {}", slot.getId(), e.getMessage());
        }
    }
}
