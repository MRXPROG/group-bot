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

        if ((placeText == null || placeText.isBlank()) && start == null && end == null && date == null) {
            log.info("No usable parameters in shift request: {}", req);
            return Optional.empty();
        }

        log.info("Searching slot: date={}, time={}–{}, place='{}'",
                date, start, end, placeText);

        // 1) Загружаем слоты: по конкретной дате, либо все ближайшие, если дата не указана
        List<SlotDTO> slots = date != null ? api.getSlotsForDate(date) : api.getUpcomingSlots();
        if (slots.isEmpty()) {
            log.info("No slots available for {}", date != null ? "date " + date : "upcoming schedule");
            return Optional.empty();
        }

        // 2) Оцениваем похожесть по месту, времени и (если есть) дате и выбираем лучший слот
        SlotScore bestMatch = slots.stream()
                .map(slot -> scoreSlot(slot, placeText, start, end, date))
                .max((a, b) -> Double.compare(a.score(), b.score()))
                .orElse(null);

        if (bestMatch == null) {
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

    private SlotScore scoreSlot(SlotDTO slot, String placeText, LocalTime expectedStart, LocalTime expectedEnd, LocalDate expectedDate) {
        List<String> userTokens = Arrays.stream(placeText.split("\\s+"))
                .filter(t -> t.length() > 1)
                .toList();

        double placeWeight = userTokens.isEmpty() ? 0.0 : 0.5;
        double timeWeight = (expectedStart != null || expectedEnd != null) ? 0.4 : 0.0;
        double dateWeight = 0.1; // дата всегда влияет слегка, чтобы выбирать ближайшую смену

        double weightSum = placeWeight + timeWeight + dateWeight;
        if (weightSum == 0) {
            return new SlotScore(slot, 0.0);
        }

        double placeScore = placeWeight > 0 ? similarityScore(userTokens, normalize(slot.getPlaceName())) : 0.0;
        double timeScore = timeWeight > 0 ? timeSimilarity(expectedStart, expectedEnd, slot.getStart().toLocalTime(), slot.getEnd().toLocalTime()) : 0.0;
        double dateScore = dateSimilarity(expectedDate, slot.getStart().toLocalDate());

        double total = (placeScore * placeWeight + timeScore * timeWeight + dateScore * dateWeight) / weightSum;

        log.debug("Slot {} placeScore={} timeScore={} dateScore={} total={}", slot.getId(), placeScore, timeScore, dateScore, total);

        return new SlotScore(slot, total);
    }

    private double timeSimilarity(LocalTime expectedStart, LocalTime expectedEnd, LocalTime slotStart, LocalTime slotEnd) {
        if (expectedStart == null && expectedEnd == null) {
            return 0.0;
        }

        double scoreSum = 0.0;
        int parts = 0;

        if (expectedStart != null) {
            long startDiff = Math.abs(java.time.Duration.between(expectedStart, slotStart).toMinutes());
            scoreSum += 1.0 - Math.min(startDiff, 240) / 240.0;
            parts++;
        }

        if (expectedEnd != null) {
            long endDiff = Math.abs(java.time.Duration.between(expectedEnd, slotEnd).toMinutes());
            scoreSum += 1.0 - Math.min(endDiff, 240) / 240.0;
            parts++;
        }

        double base = scoreSum / parts;
        if (expectedStart != null && expectedEnd != null) {
            boolean overlaps = !(slotEnd.isBefore(expectedStart) || slotStart.isAfter(expectedEnd));
            if (overlaps) {
                base = Math.min(1.0, base + 0.1);
            }
        }

        return base;
    }

    private double dateSimilarity(LocalDate expectedDate, LocalDate slotDate) {
        if (expectedDate == null) {
            long diffDays = Math.abs(java.time.Duration.between(slotDate.atStartOfDay(), LocalDate.now().atStartOfDay()).toDays());
            double freshness = 1.0 - Math.min(diffDays, 14) / 14.0; // от 1.0 (сегодня) до 0.0 (две недели и дальше)
            return Math.max(freshness, 0.0);
        }

        return expectedDate.equals(slotDate) ? 1.0 : 0.0;
    }

    private record SlotScore(SlotDTO slot, double score) {}
}
