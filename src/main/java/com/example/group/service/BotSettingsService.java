package com.example.group.service;

import com.example.group.config.BotConfig;
import com.example.group.model.BotSettings;
import com.example.group.repository.BotSettingsRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class BotSettingsService {

    public static final long SINGLETON_ID = 1L;

    private final BotSettingsRepository repository;
    private final BotConfig config;
    private final AtomicLong cachedGroupChatId = new AtomicLong();

    @PostConstruct
    public void loadInitialValue() {
        Long stored = repository.findById(SINGLETON_ID)
                .map(BotSettings::getGroupChatId)
                .orElse(config.getGroupChatId());
        if (stored != null) {
            cachedGroupChatId.set(stored);
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

    public Long bindGroupChat(Long chatId) {
        BotSettings settings = repository.findById(SINGLETON_ID)
                .orElse(BotSettings.builder()
                        .id(SINGLETON_ID)
                        .build());
        settings.setGroupChatId(chatId);
        repository.save(settings);
        cachedGroupChatId.set(chatId);
        log.info("Group chat bound to {}", chatId);
        return chatId;
    }
}
