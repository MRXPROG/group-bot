package com.example.group.service;

import com.example.group.repository.UserFlowStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class FlowCleanerScheduler {

    private final UserFlowStateRepository stateRepo;
    private final BookingFlowService bookingFlow;

    private TelegramBot bot;

    public void start(TelegramBot bot) {
        this.bot = bot;
    }

    @Scheduled(fixedDelay = 10_000)
    public void cleanupExpired() {
        if (bot == null) {
            return;
        }
        var expired = stateRepo.findExpired(LocalDateTime.now());
        expired.forEach(state -> bookingFlow.expireFlow(bot, state, null));
    }
}
