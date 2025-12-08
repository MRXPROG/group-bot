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
import java.util.Locale;
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

        // 2) Фильтрация по месту (нестрогая)
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
        return s
                .toLowerCase()
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

        return slots.stream()
                .filter(slot -> {
                    String slotNorm = normalize(slot.getPlaceName());
                    boolean ok = tokens.stream().allMatch(slotNorm::contains);
                    return ok;
                })
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
}
