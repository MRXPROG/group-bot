package com.example.group.service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlotPostService {

    private final GroupShiftMessageRepository shiftMsgRepo;
    private final BotConfig config;

    private static final Locale UA = Locale.forLanguageTag("uk");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", UA);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", UA);

    /**
     * Публикация сообщения о слоте в группу.
     * Вызывается утренним планировщиком.
     */
    public Message publishSlotPost(TelegramLongPollingBot bot, Long chatId, SlotDTO s) throws Exception {
        String date = s.getStartTime().toLocalDate().format(DATE);
        String time = s.getStartTime().toLocalTime().format(TIME) + " — " +
                s.getEndTime().toLocalTime().format(TIME);

        String text = """
                📍 %s
                🏙️ %s
                📅 %s
                🕒 %s
                👥 %d/%d зайнято
                """.formatted(
                s.getPlaceName(),
                s.getCityName(),
                date,
                time,
                s.getBookedCount(),
                s.getCapacity()
        );

        InlineKeyboardButton join = new InlineKeyboardButton();
        join.setText("🟢 Записатись через бота");
        join.setUrl("https://t.me/" + config.getMainBotUsername() + "?start=slot_" + s.getId());

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup();
        kb.setKeyboard(List.of(List.of(join)));

        SendMessage sm = new SendMessage(chatId.toString(), text);
        sm.setReplyMarkup(kb);

        Message sent = bot.execute(sm);

        // сохранить связь slot ↔ message
        GroupShiftMessage gsm = GroupShiftMessage.builder()
                .chatId(chatId)
                .messageId(sent.getMessageId())
                .slotId(s.getId())
                .postedAt(LocalDateTime.now())
                .build();

        shiftMsgRepo.save(gsm);

        return sent;
    }

    /**
     * Напоминание к уже опубликованной смене (reply на исходный пост).
     */
    public void sendReminder(TelegramLongPollingBot bot,
                             Long chatId,
                             Integer messageId,
                             SlotDTO s,
                             String prefix) throws Exception {

        int free = s.getCapacity() - s.getBookedCount();
        if (free <= 0) {
            log.info("Slot {} has no free places, skip reminder", s.getId());
            return;
        }

        String text = prefix + "\n" +
                "📍 " + s.getPlaceName() + "\n" +
                "🕒 " + s.getStartTime().toLocalTime() + " – " + s.getEndTime().toLocalTime() + "\n" +
                "Залишилось місць: " + free;

        SendMessage sm = new SendMessage(chatId.toString(), text);
        sm.setReplyToMessageId(messageId);
        bot.execute(sm);
    }
}