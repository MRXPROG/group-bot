package com.example.group.service;

import com.example.group.dto.ParsedShiftRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Year;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class PatternParser {

    // Пример строки времени: "18-23", "18:00-23:00", "з 7 до 13", "з 07:00 до 23"
    // Группы: 1=startHour, 2=startMin (опц), 3=endHour, 4=endMin (опц)
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(?iu)^(?:з\\s*)?(\\d{1,2})[:.-]?(\\d{0,2})?\\s*(?:до|[-–])\\s*(\\d{1,2})[:.-]?(\\d{0,2})?.*$"
    );

    public Optional<ParsedShiftRequest> parse(String rawText) {
        if (rawText == null) return Optional.empty();

        String text = rawText.trim();
        if (text.isEmpty()) return Optional.empty();

        // Разбиваем по строкам
        String[] lines = text.split("\\r?\\n");
        if (lines.length < 4) {
            // Нам важно именно 4 строки: дата, имя, время, локация
            return Optional.empty();
        }

        String dateLine = lines[0].trim();
        String nameLine = lines[1].trim();
        String timeLine = lines[2].trim();
        String placeLine = lines[3].trim();

        try {
            LocalDate date = parseDate(dateLine);
            TimeRange range = parseTime(timeLine);

            if (range == null) {
                log.debug("Failed to parse time from line: '{}'", timeLine);
                return Optional.empty();
            }

            if (nameLine.isEmpty() || placeLine.isEmpty()) {
                return Optional.empty();
            }

            ParsedShiftRequest req = new ParsedShiftRequest(
                    date,
                    range.start(),
                    range.end(),
                    placeLine,
                    nameLine
            );
            return Optional.of(req);
        } catch (Exception e) {
            log.debug("PatternParser error for text='{}': {}", text, e.getMessage());
            return Optional.empty();
        }
    }

    // ---------------------- DATE PARSING ----------------------

    private LocalDate parseDate(String line) {
        // Допускаем как точки, так и слеши: "14.11", "14/11/2025"
        String norm = line.trim().replace('/', '.');

        String[] parts = norm.split("\\.");
        if (parts.length < 2 || parts.length > 3) {
            throw new IllegalArgumentException("Unsupported date format: " + line);
        }

        int day = Integer.parseInt(parts[0]);
        int month = Integer.parseInt(parts[1]);
        int year;

        if (parts.length == 3) {
            // указали год явно
            year = Integer.parseInt(parts[2]);
            if (year < 100) {
                // если вдруг дали 25 → считаем как 2025
                year += 2000;
            }
        } else {
            // если год не указан — берём текущий
            year = Year.now().getValue();
        }

        return LocalDate.of(year, month, day);
    }

    // ---------------------- TIME PARSING ----------------------

    private TimeRange parseTime(String line) {
        String norm = line.trim();

        Matcher m = TIME_PATTERN.matcher(norm);
        if (!m.matches()) {
            return null;
        }

        int sh = Integer.parseInt(m.group(1)); // start hour
        int sm = parseMinutes(m.group(2));     // start minutes
        int eh = Integer.parseInt(m.group(3)); // end hour
        int em = parseMinutes(m.group(4));     // end minutes

        // Простейшая нормализация
        if (sh < 0 || sh > 23 || eh < 0 || eh > 23) return null;
        if (sm < 0 || sm > 59 || em < 0 || em > 59) return null;

        LocalTime start = LocalTime.of(sh, sm);
        LocalTime end = LocalTime.of(eh, em);

        return new TimeRange(start, end);
    }

    private int parseMinutes(String group) {
        if (group == null || group.isBlank()) return 0;
        if (group.length() == 1) {
            // "7-13" → 7:00–13:00
            return 0;
        }
        return Integer.parseInt(group);
    }

    // небольшой внутренний record для удобства
    private record TimeRange(LocalTime start, LocalTime end) {}
}
