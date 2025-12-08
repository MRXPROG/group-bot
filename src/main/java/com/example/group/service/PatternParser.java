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

    // Обнаружение времени: "18-23", "09:00 — 17:00", "з 8 до 12", "з 18:00 до 24"
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(?iu)(?:[ззcс]\s*)?(\\d{1,2})(?:[:.-](\\d{1,2}))?\\s*(?:до|[-–—])\\s*(\\d{1,2})(?:[:.-](\\d{1,2}))?"
    );

    // Обнаружение даты: 9.12, 09/12, 9.12.2025
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?"
    );

    // Имя: минимум 2 слова, начинаются с букв (чтобы избежать эмодзи и пунктуации)
    private static final Pattern NAME_PATTERN = Pattern.compile(
            "(?u)^[\\p{L}][\\p{L}\\p{M}'’-]*\\s+[\\p{L}][\\p{L}\\p{M}'’-]*(?:\\s+[\\p{L}][\\p{L}\\p{M}'’-]*)?$"
    );

    private static final Pattern NAME_INLINE_PATTERN = Pattern.compile(
            "(?u)([\\p{Lu}\\p{Lt}][\\p{L}\\p{M}'’-]*\\s+[\\p{Lu}\\p{Lt}][\\p{L}\\p{M}'’-]*(?:\\s+[\\p{L}\\p{M}'’-]+)?)"
    );

    private static final Pattern WEEKDAY_NOTE_PATTERN = Pattern.compile(
            "(?iu)^\\(?\\s*(понеділок|вівторок|середа|четвер|п['’]ятниця|субота|неділя|понедельник|вторник|среда|четверг|пятница|суббота|воскресенье)\\s*\\)?$"
    );

    private static boolean looksLikeName(String s) {
        if (s == null) return false;
        String trimmed = s.trim();
        if (trimmed.split("\\s+").length < 2) return false;
        if (trimmed.matches(".*\\d.*")) return false;
        return NAME_PATTERN.matcher(trimmed).matches();
    }

    private String normalizeLine(String line) {
        if (line == null) return "";

        String withoutEmoji = line.replaceAll("[\\p{So}\\p{Sk}\\p{Sc}\\p{Sm}]+", " ");
        return withoutEmoji.replaceAll("\\s+", " ").trim();
    }

    private static String extractNameFromText(String text) {
        if (text == null || text.isBlank()) return null;
        var matcher = NAME_INLINE_PATTERN.matcher(text);
        String best = null;
        while (matcher.find()) {
            String candidate = matcher.group(1).trim();
            if (candidate.split("\\s+").length < 2) continue;
            if (candidate.matches(".*\\d.*")) continue;
            // выбираем самую длинную комбинацию слов
            if (best == null || candidate.length() > best.length()) {
                best = candidate;
            }
        }
        return best;
    }

    public Optional<ParsedShiftRequest> parse(String rawText) {

        if (rawText == null || rawText.isBlank()) {
            return Optional.empty();
        }

        String[] lines = Arrays.stream(rawText.split("\\r?\\n"))
                .map(this::normalizeLine)
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

                String leftover = extractRemainder(line, List.of(dt, tm));
                name = removeNameAndCapture(leftover, placeParts, name);
                continue;
            }

            // 2) ПРОВЕРКА НА ДАТУ
            if (date == null) {
                Matcher m = DATE_PATTERN.matcher(line);
                if (m.find()) {
                    date = parseDate(m);
                    String leftover = extractRemainder(line, List.of(m));
                    name = removeNameAndCapture(leftover, placeParts, name);
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
                    String leftover = extractRemainder(line, List.of(m));
                    name = removeNameAndCapture(leftover, placeParts, name);
                    continue;
                }
            }

            // 4) ПРОВЕРКА НА ИМЯ
            if (looksLikeName(line)) {
                name = line;
                continue;
            }

            name = removeNameAndCapture(line, placeParts, name);
            continue;
        }

        if (name == null) {
            name = extractNameFromText(String.join(" ", lines));
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

        return new TimeRange(normalizeTime(sh, sm), normalizeTime(eh, em));
    }

    private LocalTime normalizeTime(int hour, int minute) {
        int safeHour = Math.min(hour, 24);
        int safeMinute = Math.min(minute, 59);

        if (safeHour == 24) {
            return LocalTime.of(23, 59);
        }

        return LocalTime.of(safeHour, safeMinute);
    }

    private String extractRemainder(String line, List<Matcher> matchers) {
        if (line == null || line.isBlank()) return "";

        boolean[] masked = new boolean[line.length()];
        for (Matcher matcher : matchers) {
            for (int i = matcher.start(); i < matcher.end() && i < masked.length; i++) {
                masked[i] = true;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            if (!masked[i]) {
                sb.append(line.charAt(i));
            }
        }

        return sb.toString()
                .replaceAll("[.,;:—–-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void removeNameAndCapture(String text, List<String> placeParts) {
        removeNameAndCapture(text, placeParts, null);
    }

    private String removeNameAndCapture(String text, List<String> placeParts, String currentName) {
        if (text == null || text.isBlank()) {
            return currentName;
        }

        String foundName = extractNameFromText(text);
        String cleaned = text;

        if (foundName != null) {
            cleaned = text.replace(foundName, " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (currentName == null) {
                currentName = foundName;
            }
        }

        if (!cleaned.isBlank() && !isWeekdayNote(cleaned)) {
            placeParts.add(cleaned);
        }

        return currentName;
    }

    private record TimeRange(LocalTime start, LocalTime end) {}

    private boolean isWeekdayNote(String text) {
        return WEEKDAY_NOTE_PATTERN.matcher(text.trim()).matches();
    }
}
