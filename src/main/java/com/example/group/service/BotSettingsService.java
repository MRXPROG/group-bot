package com.example.group.service;

import com.example.group.config.BotConfig;
import com.example.group.model.BotSettings;
import com.example.group.repository.BotSettingsRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class BotSettingsService {

    public static final long SINGLETON_ID = 1L;

    private final BotSettingsRepository repository;
    private final BotConfig config;
    private final AtomicLong cachedGroupChatId = new AtomicLong();
    private final AtomicReference<Integer> cachedPinnedMessageId = new AtomicReference<>();

    @PostConstruct
    public void loadInitialValue() {
        repository.findById(SINGLETON_ID).ifPresent(settings -> {
            if (settings.getGroupChatId() != null) {
                cachedGroupChatId.set(settings.getGroupChatId());
            }
            if (settings.getPinnedMessageId() != null) {
                cachedPinnedMessageId.set(settings.getPinnedMessageId());
            }
        });

        if (cachedGroupChatId.get() == 0 && config.getGroupChatId() != null) {
            cachedGroupChatId.set(config.getGroupChatId());
        }
    }

    public Long getGroupChatId() {
        long value = cachedGroupChatId.get();
        if (value != 0) {
            return value;
        }
        return repository.findById(SINGLETON_ID)
                .map(BotSettings::getGroupChatId)
                .orElse(config.getGroupChatId());
    }

    public Integer getPinnedMessageId() {
        Integer cached = cachedPinnedMessageId.get();
        if (cached != null) {
            return cached;
        }
        return repository.findById(SINGLETON_ID)
                .map(BotSettings::getPinnedMessageId)
                .orElse(null);
    }

    public Long bindGroupChat(Long chatId) {
        BotSettings settings = loadOrCreate();
        settings.setGroupChatId(chatId);
        repository.save(settings);
        cachedGroupChatId.set(chatId);
        log.info("Group chat bound to {}", chatId);
        return chatId;
    }

    public Integer savePinnedMessageId(Integer pinnedMessageId) {
        BotSettings settings = loadOrCreate();
        settings.setPinnedMessageId(pinnedMessageId);
        if (settings.getGroupChatId() == null && config.getGroupChatId() != null) {
            settings.setGroupChatId(config.getGroupChatId());
        }
        repository.save(settings);
        cachedPinnedMessageId.set(pinnedMessageId);
        log.info("Pinned message id saved: {}", pinnedMessageId);
        return pinnedMessageId;
    }

    private BotSettings loadOrCreate() {
        return repository.findById(SINGLETON_ID)
                .orElse(BotSettings.builder()
                        .id(SINGLETON_ID)
                        .build());
    }
}
