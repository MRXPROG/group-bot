package com.example.group.service;

import com.example.group.dto.ParsedShiftRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class PatternParser {

    private static final Pattern DATE_DOTTED = Pattern.compile("(?iu)(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?(?:\\s*\\([^)]*\\))?");
    private static final Pattern DATE_ISO = Pattern.compile("(?iu)(\\d{4})-(\\d{1,2})-(\\d{1,2})");

    private static final Pattern TIME_FOUR_PARTS = Pattern.compile("(?iu)(\\d{1,2}[:.]\\d{2})[:.]?(\\d{1,2}[:.]\\d{2})");
    private static final Pattern TIME_RANGE = Pattern.compile(
            "(?iu)(?:[ззcс]\\s*)?(?<start>\\d{1,2}(?::?\\d{1,2})?)?\\s*[–—:\\-]\\s*(?<end>\\d{1,2}(?::?\\d{1,2})?)?"
    );

    private static final Pattern TIME_FROM_TO = Pattern.compile(
            "(?iu)(?:[ззcс]\s*)?(?<start>\\d{1,2}(?::?\\d{1,2})?)?\s*(?:до|to|по|till)\s*(?<end>\\d{1,2}(?::?\\d{1,2})?)?"
    );
    private static final Pattern INLINE_NAME = Pattern.compile(
            "(?iu)([\\p{L}\\p{M}'’-]{2,}\\s+[\\p{L}\\p{M}'’-]{2,}(?:\\s+[\\p{L}\\p{M}'’-]{2,})?)"
    );

    private final StopWordService stopWordService;

    public Optional<ParsedShiftRequest> parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return Optional.empty();
        }

        String normalizedText = normalize(rawText);
        List<String> lines = Arrays.stream(normalizedText.split("\\r?\\n"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        boolean hasLocationToken = stopWordService.containsAnyLocationToken(normalizedText);

        String preliminaryPlace = hasLocationToken ? extractPlace(lines, null) : null;
        String name = extractName(normalizedText, preliminaryPlace);
        String placeText = hasLocationToken ? extractPlace(lines, name) : null;
        LocalDate date = extractDate(normalizedText);
        TimeRange timeRange = extractTime(normalizedText);

        boolean hasDateOrTime = date != null || timeRange.start != null || timeRange.end != null;
        boolean hasName = name != null && !name.isBlank();

        if (!hasDateOrTime) {
            return Optional.empty();
        }

        if (!hasLocationToken && !(hasName && hasDateOrTime)) {
            return Optional.empty();
        }

        return Optional.of(new ParsedShiftRequest(
                date,
                timeRange.start,
                timeRange.end,
                blankToNull(placeText),
                name
        ));
    }

    public Optional<String> extractNameOnly(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return Optional.empty();
        }

        String normalizedText = normalize(rawText);
        String name = extractName(normalizedText, null);
        return Optional.ofNullable(blankToNull(name));
    }

    public boolean isLikelyShiftRequest(String text) {
        return parse(text)
                .map(req -> req.getDate() != null
                        || req.getStartTime() != null
                        || req.getEndTime() != null
                        || req.getPlaceText() != null)
                .orElse(false);
    }

    private String normalize(String text) {
        String cleaned = text
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .replace('\u2015', '-')
                .replace('\u2212', '-')
                .replace('\u2026', ' ')
                .replace('\u2010', '-')
                .replace('\u00a0', ' ');

        cleaned = cleaned.replaceAll("[\\p{So}\\p{Sk}\\p{Sc}\\p{Sm}]+", " ");

        // Keep line breaks to better separate place/name lines, but collapse all other whitespace noise.
        cleaned = cleaned.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        cleaned = cleaned.replaceAll("\\s*\\n\\s*", "\n");

        return cleaned.trim();
    }

    private LocalDate extractDate(String text) {
        Matcher dotted = DATE_DOTTED.matcher(text);
        if (dotted.find()) {
            return buildDate(dotted.group(1), dotted.group(2), dotted.group(3));
        }

        Matcher iso = DATE_ISO.matcher(text);
        if (iso.find()) {
            return buildDate(iso.group(3), iso.group(2), iso.group(1));
        }
        return null;
    }

    private LocalDate buildDate(String dayStr, String monthStr, String yearStr) {
        try {
            int day = Integer.parseInt(dayStr);
            int month = Integer.parseInt(monthStr);
            int year;
            if (yearStr != null) {
                year = Integer.parseInt(yearStr);
                if (year < 100) {
                    year += 2000;
                }
            } else {
                year = Year.now().getValue();
                LocalDate candidate = LocalDate.of(year, month, day);
                if (candidate.isBefore(LocalDate.now().minusDays(1))) {
                    year += 1;
                }
            }
            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            log.debug("Failed to parse date from {}.{}.{}, reason {}", dayStr, monthStr, yearStr, e.getMessage());
            return null;
        }
    }

    private TimeRange extractTime(String text) {
        Matcher four = TIME_FOUR_PARTS.matcher(text);
        if (four.find()) {
            LocalTime start = parseTimeToken(four.group(1));
            LocalTime end = parseTimeToken(four.group(2));
            return new TimeRange(start, end);
        }

        for (Pattern pattern : List.of(TIME_RANGE, TIME_FROM_TO)) {
            Matcher range = pattern.matcher(text);
            while (range.find()) {
                String startStr = range.group("start");
                String endStr = range.group("end");

                if (bothTooShort(startStr, endStr)) {
                    continue;
                }

                LocalTime start = parseTimeToken(startStr);
                LocalTime end = parseTimeToken(endStr);
                if (start != null || end != null) {
                    return new TimeRange(start, end);
                }
            }
        }

        return new TimeRange(null, null);
    }

    private LocalTime parseTimeToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String safe = token.replace('.', ':');
        if (!safe.contains(":")) {
            try {
                int hour = Integer.parseInt(safe);
                hour = Math.max(0, Math.min(24, hour));
                if (hour == 24) {
                    return LocalTime.of(23, 59);
                }
                return LocalTime.of(hour, 0);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        try {
            DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                    .appendPattern("H:mm")
                    .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
                    .toFormatter(Locale.getDefault());
            LocalTime time = LocalTime.parse(safe, formatter);
            if (time.getHour() == 24) {
                return LocalTime.of(23, 59);
            }
            return time;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private boolean bothTooShort(String startStr, String endStr) {
        if (startStr == null || endStr == null) {
            return false;
        }

        boolean startSimple = startStr.length() == 1 && !startStr.contains(":") && !startStr.contains(".");
        boolean endSimple = endStr.length() == 1 && !endStr.contains(":") && !endStr.contains(".");

        return startSimple && endSimple;
    }

    private String extractName(String text, String placeText) {
        Matcher matcher = INLINE_NAME.matcher(text);
        String best = null;
        int bestPos = -1;

        while (matcher.find()) {
            String candidate = matcher.group(1).trim();
            candidate = candidate.replaceAll("^[,-,\\s]+|[,-,\\s]+$", "");

            String[] parts = candidate.split("\\s+");
            if (parts.length < 2 || parts.length > 3) {
                continue;
            }
            if (candidate.matches(".*\\d.*")) {
                continue;
            }
            if (containsStopWord(parts) || insidePlace(candidate, placeText) || allStopWords(parts)) {
                continue;
            }

            if (matcher.start() >= bestPos) {
                best = candidate;
                bestPos = matcher.start();
            }
        }
        return best;
    }

    private String extractPlace(List<String> lines, String detectedName) {
        List<String> placeParts = new ArrayList<>();
        for (String line : lines) {
            String cleaned = removeMatches(line, DATE_DOTTED);
            cleaned = removeMatches(cleaned, DATE_ISO);
            cleaned = removeMatches(cleaned, TIME_FOUR_PARTS);
            cleaned = removeMatches(cleaned, TIME_RANGE);
            cleaned = removeMatches(cleaned, TIME_FROM_TO);

            if (detectedName != null) {
                cleaned = cleaned.replace(detectedName, " ");
            }

            cleaned = cleaned.trim();
            if (cleaned.isBlank()) {
                continue;
            }

            boolean hasLocationToken = stopWordService.containsAnyLocationToken(cleaned);
            boolean nameLike = looksLikeName(cleaned);

            if (nameLike && !hasLocationToken) {
                continue;
            }

            if (hasLocationToken || !nameLike) {
                placeParts.add(cleaned);
            }
        }

        if (placeParts.isEmpty()) {
            return null;
        }

        return String.join(" ", placeParts)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String removeMatches(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        boolean found = false;
        while (matcher.find()) {
            matcher.appendReplacement(sb, " ");
            found = true;
        }
        matcher.appendTail(sb);
        if (!found) {
            return text;
        }
        return sb.toString();
    }

    private boolean looksLikeName(String candidate) {
        Matcher matcher = INLINE_NAME.matcher(candidate);
        return matcher.find() && !candidate.matches(".*\\d.*");
    }

    private boolean containsStopWord(String[] parts) {
        return Arrays.stream(parts)
                .anyMatch(stopWordService::isStopWordToken);
    }

    private boolean allStopWords(String[] parts) {
        return Arrays.stream(parts)
                .allMatch(stopWordService::isStopWordToken);
    }

    private boolean insidePlace(String candidate, String placeText) {
        if (candidate == null || placeText == null) {
            return false;
        }
        String normalizedCandidate = candidate.toLowerCase(Locale.ROOT).trim();
        String normalizedPlace = placeText.toLowerCase(Locale.ROOT);

        if (normalizedPlace.contains(normalizedCandidate) || normalizedCandidate.contains(normalizedPlace)) {
            return true;
        }

        Set<String> candidateTokens = splitToTokens(normalizedCandidate);
        Set<String> placeTokens = splitToTokens(normalizedPlace);

        return candidateTokens.stream().anyMatch(placeTokens::contains);
    }

    private Set<String> splitToTokens(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(text.split("\\s+"))
                .map(token -> token.replaceAll("[^\\p{L}\\p{N}]+", ""))
                .filter(token -> !token.isBlank())
                .collect(Collectors.toSet());
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private record TimeRange(LocalTime start, LocalTime end) {}
}
