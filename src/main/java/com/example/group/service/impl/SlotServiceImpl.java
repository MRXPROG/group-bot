package com.example.group.service.impl;

import com.example.group.controllers.MainBotApiClient;
import com.example.group.dto.ParsedShiftRequest;
import com.example.group.dto.SlotDTO;
import com.example.group.service.SlotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlotServiceImpl implements SlotService {

    private final MainBotApiClient api; // сервис общения с основным ботом

    @Override
    public Optional<SlotDTO> findMatchingSlot(ParsedShiftRequest req) {

        LocalDate date = req.getDate();
        LocalTime start = req.getStartTime();
        LocalTime end = req.getEndTime();
        String placeText = normalize(req.getPlaceText());

        log.info("Searching slot: date={}, time={}–{}, place='{}'",
                date, start, end, placeText);

        // 1) Загружаем ВСЕ слоты на эту дату от основного микросервиса
        List<SlotDTO> slots = api.getSlotsForDate(date);
        if (slots.isEmpty()) {
            log.info("No slots available for date {}", date);
            return Optional.empty();
        }

        // 2) Оцениваем похожесть по месту и времени и выбираем лучший слот
        SlotScore bestMatch = slots.stream()
                .map(slot -> scoreSlot(slot, placeText, start, end))
                .max((a, b) -> Double.compare(a.score(), b.score()))
                .orElse(null);

        if (bestMatch == null || bestMatch.score() <= 0.15) {
            log.info("No sufficiently similar slot found for place='{}' time={}–{}", placeText, start, end);
            return Optional.empty();
        }

        // 3) Возвращаем лучший найденный вариант
        return Optional.of(bestMatch.slot());
    }

    // ------------------ Вспомогательные методы ------------------

    /** Упрощённая нормализация текста для сравнения */
    private String normalize(String s) {
        if (s == null) return "";

        String normalized = s.toLowerCase();

        return normalized
                .replace('№', ' ')
                .replaceAll("[^\\p{L}\\p{N}]+", " ") // оставляем только буквы (всех языков) и цифры
                .replaceAll("\\s+", " ")            // схлопываем пробелы
                .trim();
    }

    private boolean isSimilar(String a, String b) {
        if (a.contains(b) || b.contains(a)) {
            return true;
        }

        return levenshteinDistance(a, b) <= 1;
    }

    private int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];

        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }

        return dp[a.length()][b.length()];
    }

    private double similarityScore(List<String> userTokens, String slotPlace) {
        List<String> slotTokens = Arrays.stream(slotPlace.split("\\s+"))
                .filter(t -> t.length() > 1)
                .toList();

        if (slotTokens.isEmpty()) {
            return 0;
        }

        long matched = userTokens.stream()
                .filter(token -> slotTokens.stream().anyMatch(slotToken -> isSimilar(slotToken, token)))
                .count();

        return matched / (double) userTokens.size();
    }

    private SlotScore scoreSlot(SlotDTO slot, String placeText, LocalTime expectedStart, LocalTime expectedEnd) {
        List<String> userTokens = Arrays.stream(placeText.split("\\s+"))
                .filter(t -> t.length() > 1)
                .toList();

        double placeScore;
        if (userTokens.isEmpty()) {
            placeScore = 0.5; // без локации опираемся на время
        } else {
            placeScore = similarityScore(userTokens, normalize(slot.getPlaceName()));
        }

        double timeScore = timeSimilarity(expectedStart, expectedEnd, slot.getStart().toLocalTime(), slot.getEnd().toLocalTime());

        // вес локации чуть выше, чтобы совпадающая локация с небольшой ошибкой времени выигрывала
        double total = placeScore * 0.6 + timeScore * 0.4;

        log.debug("Slot {} placeScore={} timeScore={} total={}", slot.getId(), placeScore, timeScore, total);

        return new SlotScore(slot, total);
    }

    private double timeSimilarity(LocalTime expectedStart, LocalTime expectedEnd, LocalTime slotStart, LocalTime slotEnd) {
        if (expectedStart == null || expectedEnd == null) {
            return 0.0;
        }

        long startDiff = Math.abs(java.time.Duration.between(expectedStart, slotStart).toMinutes());
        long endDiff = Math.abs(java.time.Duration.between(expectedEnd, slotEnd).toMinutes());

        // Принимаем небольшие расхождения до 3 часов, дальше считаем совсем неподходящим
        double startScore = 1.0 - Math.min(startDiff, 180) / 180.0;
        double endScore = 1.0 - Math.min(endDiff, 180) / 180.0;

        // если временные интервалы пересекаются — бонус
        boolean overlaps = !(slotEnd.isBefore(expectedStart) || slotStart.isAfter(expectedEnd));
        double overlapBonus = overlaps ? 0.1 : 0.0;

        double base = (startScore + endScore) / 2.0 + overlapBonus;
        return Math.min(base, 1.0);
    }

    private record SlotScore(SlotDTO slot, double score) {}
}
