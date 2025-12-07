package com.example.group.service;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderScheduler {

    private final GroupShiftMessageRepository shiftMsgRepo;
    private final SlotReminderHistoryRepository reminderRepo;
    private final MainBotApiClient api;

    private TelegramBot bot; // сюда главный бот себе “инжектит” себя через start()

    private static final List<Integer> REMINDER_HOURS = List.of(24, 12, 6);

    public void start(TelegramBot bot) {
        this.bot = bot;
    }

    @Scheduled(fixedDelay = 60_000) // раз в минуту, можно 10_000 (каждые 10 сек)
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
            case 6  -> "⏰ До зміни залишилось 6 годин!";
            default -> "Нагадування:";
        };

        try {
            bot.sendReminder(msg.getChatId(), msg.getMessageId(), slot, prefix);
        } catch (Exception e) {
            log.error("Failed to send reminder for slot {}: {}", slot.getId(), e.getMessage());
        }
    }
}

