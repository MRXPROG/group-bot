package com.example.group.controllers;

import com.example.group.config.BotConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerNotifier {

    private final BotConfig cfg; // добавь baseUrl, например worker.apiUrl

    private WebClient client() {
        return WebClient.builder()
                .baseUrl(cfg.getApiUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public void notifyDecision(TransferManagerDecisionDTO dto) {
        try {
            client().post()
                    .uri("/manager-decision")
                    .bodyValue(dto)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("Pushed decision to worker bot: {}", dto);
        } catch (Exception e) {
            log.warn("Push to worker bot failed: {}", e.toString());
        }
    }
}
