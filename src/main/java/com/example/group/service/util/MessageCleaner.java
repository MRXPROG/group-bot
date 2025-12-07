package com.example.group.service.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class MessageCleaner {

    public void deleteLater(TelegramLongPollingBot bot, Long chatId, Integer messageId, int seconds) {
        CompletableFuture
                .delayedExecutor(seconds, TimeUnit.SECONDS)
                .execute(() -> deleteNow(bot, chatId, messageId));
    }

    public void deleteNow(TelegramLongPollingBot bot, Long chatId, Integer messageId) {
        try {
            DeleteMessage dm = new DeleteMessage(chatId.toString(), messageId);
            bot.execute(dm);
        } catch (Exception e) {
            log.warn("Failed to delete message chatId={}, msgId={}, err={}",
                    chatId, messageId, e.getMessage());
        }
    }
}
