package com.example.group.service;

import com.example.group.dto.ParsedShiftRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class PatternParser {

    // Обнаружение времени: "18-23", "09:00 — 17:00", "з 8 до 12"
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(?iu)(\\d{1,2})(?:[:.-](\\d{1,2}))?\\s*(?:до|[-–])\\s*(\\d{1,2})(?:[:.-](\\d{1,2}))?"
    );

    // Обнаружение даты: 9.12, 09/12, 9.12.2025
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?"
    );

    // Имя: минимум 2 слова, без цифр
    private static boolean looksLikeName(String s) {
        if (s == null) return false;
        String trimmed = s.trim();
        if (trimmed.split("\\s+").length < 2) return false;
        if (trimmed.matches(".*\\d.*")) return false;
        return true;
    }

    public Optional<ParsedShiftRequest> parse(String rawText) {

        if (rawText == null || rawText.isBlank()) {
            return Optional.empty();
        }

        String[] lines = Arrays.stream(rawText.split("\\r?\\n"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toArray(String[]::new);

        if (lines.length == 0) {
            return Optional.empty();
        }

        LocalDate date = null;
        LocalTime start = null;
        LocalTime end = null;
        String name = null;
        List<String> placeParts = new  ArrayList<>();

        for (String line : lines) {

            // 1) ПРОВЕРКА НА ДАТУ + ВРЕМЯ в 1 строке
            Matcher dt = DATE_PATTERN.matcher(line);
            Matcher tm = TIME_PATTERN.matcher(line);

            if (dt.find() && tm.find()) {
                date = parseDate(dt);
                var range = parseTime(tm);
                start = range.start();
                end = range.end();
                continue;
            }

            // 2) ПРОВЕРКА НА ДАТУ
            if (date == null) {
                Matcher m = DATE_PATTERN.matcher(line);
                if (m.find()) {
                    date = parseDate(m);
                    continue;
                }
            }

            // 3) ПРОВЕРКА НА ВРЕМЯ
            if (start == null || end == null) {
                Matcher m = TIME_PATTERN.matcher(line);
                if (m.find()) {
                    var range = parseTime(m);
                    start = range.start();
                    end = range.end();
                    continue;
                }
            }

            // 4) ПРОВЕРКА НА ИМЯ
            if (name == null && looksLikeName(line)) {
                name = line;
                continue;
            }

            // 5) Остальное → локация
            placeParts.add(line);
        }

        if (date == null || start == null || end == null || name == null) {
            log.debug("Failed to detect required fields: date={}, time={}, name={}", date, start, name);
            return Optional.empty();
        }

        String place = String.join(", ", placeParts);

        return Optional.of(new ParsedShiftRequest(
                date,
                start,
                end,
                place,
                name
        ));
    }

    // --------------------- DATE ---------------------

    private LocalDate parseDate(Matcher m) {
        int day = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int year;

        if (m.group(3) != null) {
            year = Integer.parseInt(m.group(3));
            if (year < 100) year += 2000;
        } else {
            year = Year.now().getValue();
        }

        return LocalDate.of(year, month, day);
    }

    // --------------------- TIME ---------------------

    private TimeRange parseTime(Matcher m) {
        int sh = Integer.parseInt(m.group(1));
        int sm = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));

        int eh = Integer.parseInt(m.group(3));
        int em = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));

        return new TimeRange(LocalTime.of(sh, sm), LocalTime.of(eh, em));
    }

    private record TimeRange(LocalTime start, LocalTime end) {}
}
