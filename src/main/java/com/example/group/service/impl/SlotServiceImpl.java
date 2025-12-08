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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlotServiceImpl implements SlotService {

    private final MainBotApiClient api; // сервис общения с основным ботом

    private static final Map<String, String> PLACE_ALIASES = new LinkedHashMap<>() {{
        put("\\bнп\\b", "нова пошта");
        put("\\bnp\\b", "нова пошта");
        put("\\bновая?\\s+пошта\\b", "нова пошта");
    }};

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

        // 2) Фильтрация по месту (нестрогая): ищем максимально похожий слот
        slots = filterByPlace(slots, placeText);
        if (slots.isEmpty()) {
            log.info("No slots match place '{}'", placeText);
            return Optional.empty();
        }

        // 3) Фильтрация по времени (разница = точное совпадение)
        slots = filterByTime(slots, start, end);
        if (slots.isEmpty()) {
            log.info("Slots found by place, but no matching time");
            return Optional.empty();
        }

        // 4) Если несколько — выбираем первый
        // (нормально, потому что реальные данные обычно не дублируются)
        return Optional.of(slots.get(0));
    }

    // ------------------ Вспомогательные методы ------------------

    /** Упрощённая нормализация текста для сравнения */
    private String normalize(String s) {
        if (s == null) return "";

        String normalized = s.toLowerCase();
        for (Map.Entry<String, String> entry : PLACE_ALIASES.entrySet()) {
            normalized = normalized.replaceAll(entry.getKey(), entry.getValue());
        }

        return normalized
                .replace('№', ' ')
                .replaceAll("[^\\p{L}\\p{N}]+", " ") // оставляем только буквы (всех языков) и цифры
                .replaceAll("\\s+", " ")            // схлопываем пробелы
                .trim();
    }
    /** Фильтруем по названию места. match = частичное совпадение */
    private List<SlotDTO> filterByPlace(List<SlotDTO> slots, String placeText) {

        if (placeText == null || placeText.isBlank()) return slots;

        List<String> tokens = Arrays.stream(normalize(placeText).split("\\s+"))
                .filter(t -> t.length() > 1)
                .toList();

        if (tokens.isEmpty()) {
            return slots;
        }

        double bestScore = -1.0;

        List<SlotDTO> scored = slots.stream()
                .map(slot -> new SlotScore(slot, similarityScore(tokens, normalize(slot.getPlaceName()))))
                .filter(scoredSlot -> scoredSlot.score > 0)
                .toList();

        for (SlotScore score : scored) {
            if (score.score > bestScore) {
                bestScore = score.score;
            }
        }

        if (bestScore <= 0) {
            return List.of();
        }

        double threshold = bestScore - 0.01; // оставляем все лучшие варианты с одинаковым счётом

        return scored.stream()
                .filter(scoredSlot -> scoredSlot.score >= threshold)
                .map(scoredSlot -> scoredSlot.slot)
                .toList();
    }

    /** Фильтрация по времени */
    private List<SlotDTO> filterByTime(List<SlotDTO> list, LocalTime start, LocalTime end) {
        return list.stream()
                .filter(s ->
                        s.getStart().toLocalTime().equals(start)
                                && s.getEnd().toLocalTime().equals(end)
                )
                .toList();
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

    private record SlotScore(SlotDTO slot, double score) {}
}
