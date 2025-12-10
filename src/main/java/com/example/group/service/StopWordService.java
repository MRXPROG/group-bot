package com.example.group.service;

import com.example.group.model.City;
import com.example.group.model.Place;
import com.example.group.repository.CityRepository;
import com.example.group.repository.PlaceRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class StopWordService {

    private final CityRepository cityRepository;
    private final PlaceRepository placeRepository;

    private final Set<String> stopWords = ConcurrentHashMap.newKeySet();

    @PostConstruct
    @Scheduled(fixedDelay = 300_000, initialDelay = 0)
    public void refreshStopWords() {
        try {
            Set<String> updated = new HashSet<>();

            cityRepository.findByVisibleTrue()
                    .stream()
                    .map(City::getName)
                    .forEach(name -> addTokens(updated, name));

            placeRepository.findByVisibleTrue()
                    .forEach(place -> {
                        addTokens(updated, place.getName());
                        if (place.getCity() != null) {
                            addTokens(updated, place.getCity().getName());
                        }
                    });

            stopWords.clear();
            stopWords.addAll(updated);
            log.info("Stop-words refreshed: {} entries", stopWords.size());
        } catch (Exception e) {
            log.warn("Failed to refresh stop-words: {}", e.getMessage());
        }
    }

    public boolean isStopWordToken(String rawToken) {
        if (rawToken == null) {
            return false;
        }

        String normalized = normalize(rawToken);
        if (normalized.isEmpty()) {
            return false;
        }

        if (stopWords.contains(normalized)) {
            return true;
        }

        // treat partial matches as stop-words too: any overlapping token counts
        return stopWords.stream().anyMatch(sw -> sw.contains(normalized) || normalized.contains(sw));
    }

    public Set<String> snapshot() {
        return Collections.unmodifiableSet(stopWords);
    }

    private void addTokens(Set<String> target, String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        for (String token : normalize(text).split(" ")) {
            if (!token.isBlank()) {
                target.add(token);
            }
        }
    }

    private String normalize(String text) {
        return text.toLowerCase()
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }
}
