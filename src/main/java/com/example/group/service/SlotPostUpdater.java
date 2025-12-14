package com.example.group.service;

import com.example.group.controllers.MainBotApiClient;
import com.example.group.dto.SlotDTO;
import com.example.group.model.GroupShiftMessage;
import com.example.group.repository.GroupShiftMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlotPostUpdater {

    private final BotSettingsService settingsService;
    private final GroupShiftMessageRepository shiftMsgRepo;
    private final MainBotApiClient api;
    private final SlotPostService slotPostService;

    private TelegramBot bot;

    public void start(TelegramBot bot) {
        this.bot = bot;
    }

    @Scheduled(cron = "0 */3 * * * *")
    public void refreshSlotPosts() {
        if (bot == null) {
            log.warn("SlotPostUpdater: bot is not set yet");
            return;
        }

        Long chatId = settingsService.getGroupChatId();
        if (chatId == null || chatId == 0) {
            log.warn("SlotPostUpdater: group chat is not bound yet");
            return;
        }

        List<GroupShiftMessage> messages = shiftMsgRepo.findAllByChatId(chatId);
        if (messages.isEmpty()) {
            return;
        }

        messages.forEach(msg -> refreshSingle(chatId, msg));
    }

    private void refreshSingle(Long chatId, GroupShiftMessage msg) {
        SlotDTO slot = api.getSlotById(msg.getSlotId());
        if (slot == null) {
            log.info("SlotPostUpdater: slot {} not found, keeping post {} for potential re-open", msg.getSlotId(), msg.getMessageId());
            return;
        }

        if (isSlotFinished(slot)) {
            log.info("SlotPostUpdater: slot {} is finished, cleaning up post {}", msg.getSlotId(), msg.getMessageId());
            cleanupSlotPost(chatId, msg);
            return;
        }

        try {
            slotPostService.publishSlotPost(bot, chatId, slot, msg.isMorningPost(), msg.isEveningPost());
        } catch (Exception e) {
            log.error("SlotPostUpdater: failed to refresh slot {}: {}", slot.getId(), e.getMessage());
        }
    }

    private boolean isSlotFinished(SlotDTO slot) {
        LocalDateTime end = slot.getEnd();
        return end != null && end.isBefore(LocalDateTime.now());
    }

    private void cleanupSlotPost(Long chatId, GroupShiftMessage msg) {
        markAsServiceMessage(chatId, msg.getMessageId());
        shiftMsgRepo.delete(msg);
    }

    private void markAsServiceMessage(Long chatId, Integer messageId) {
        try {
            EditMessageText edit = EditMessageText.builder()
                    .chatId(chatId.toString())
                    .messageId(messageId)
                    .text("ℹ️ Зміна завершена. Пост архівовано.")
                    .build();
            bot.execute(edit);
        } catch (TelegramApiException e) {
            log.warn("SlotPostUpdater: failed to convert expired slot message {} into service message: {}", messageId, e.getMessage());
        }
    }
}
