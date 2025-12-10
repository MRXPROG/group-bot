package com.example.group.service;

import com.example.group.dto.ParsedShiftRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

class PatternParserTest {

    private StopWordService stopWordService;
    private PatternParser parser;
    private Set<String> locationTokens;

    @BeforeEach
    void setUp() {
        stopWordService = Mockito.mock(StopWordService.class);
        parser = new PatternParser(stopWordService);

        locationTokens = Set.of(
                "пошта", "нова", "нова пошта", "стрижавка", "стрижавк", "стрижовка",
                "шепеля", "якова", "якова шепеля", "киев"
        );

        Mockito.when(stopWordService.containsAnyLocationToken(anyString()))
                .thenAnswer(invocation -> {
                    String text = invocation.getArgument(0, String.class);
                    if (text == null) {
                        return false;
                    }
                    String normalized = text.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", " ");
                    return locationTokens.stream().anyMatch(token -> containsToken(normalized, token));
                });
        Mockito.when(stopWordService.isStopWordToken(anyString()))
                .thenAnswer(invocation -> {
                    String token = invocation.getArgument(0, String.class);
                    if (token == null) {
                        return false;
                    }
                    String normalized = token.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", "");
                    return locationTokens.stream().anyMatch(sw -> sw.contains(normalized) || normalized.contains(sw));
                });
    }

    private boolean containsToken(String normalizedText, String token) {
        String normalizedToken = token.toLowerCase();
        if (normalizedText.contains(normalizedToken)) {
            return true;
        }
        List<String> tokens = List.of(normalizedText.split(" "));
        return tokens.stream().anyMatch(t -> t.contains(normalizedToken) || normalizedToken.contains(t));
    }

    @Test
    void shouldParseNameSplitAcrossLinesWithPlace() {
        Optional<ParsedShiftRequest> parsed = parser.parse("пошта 11.12\nДима \nМаслов");

        assertThat(parsed).isPresent();
        ParsedShiftRequest request = parsed.get();

        assertThat(request.getUserFullName()).isEqualTo("Дима Маслов");
        assertThat(request.getPlaceText()).isEqualTo("пошта");

        LocalDate expectedDate = LocalDate.of(LocalDate.now().getYear(), 12, 11);
        if (expectedDate.isBefore(LocalDate.now().minusDays(1))) {
            expectedDate = expectedDate.plusYears(1);
        }

        assertThat(request.getDate()).isEqualTo(expectedDate);
    }

    @Test
    void shouldParseNameAndDateWithoutPlaceTokens() {
        Optional<ParsedShiftRequest> parsed = parser.parse("11.12 Дима Маслов");

        assertThat(parsed).isPresent();
        ParsedShiftRequest request = parsed.get();

        assertThat(request.getUserFullName()).isEqualTo("Дима Маслов");
        assertThat(request.getPlaceText()).isNull();

        LocalDate expectedDate = LocalDate.of(LocalDate.now().getYear(), 12, 11);
        if (expectedDate.isBefore(LocalDate.now().minusDays(1))) {
            expectedDate = expectedDate.plusYears(1);
        }

        assertThat(request.getDate()).isEqualTo(expectedDate);
    }

    @Test
    void shouldParseShiftWithPlaceNameOnSameLine() {
        Optional<ParsedShiftRequest> parsed = parser.parse("Стрижавка 18-09 9.12 Зваричевський Юрій");

        assertThat(parsed).isPresent();
        ParsedShiftRequest request = parsed.get();

        assertThat(request.getUserFullName()).isEqualTo("Зваричевський Юрій");
        assertThat(request.getPlaceText()).containsIgnoringCase("Стрижавка");
        assertThat(request.getStartTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(request.getEndTime()).isEqualTo(LocalTime.of(9, 0));

        assertThat(request.getDate()).isEqualTo(expectedDate(9, 12));
    }

    @Test
    void shouldParseShiftWithLocationLineSeparatingNameAndTimes() {
        String message = "Владислав Орловський\nЯкова Шепеля\n06.12(сб)\n7-23";

        Optional<ParsedShiftRequest> parsed = parser.parse(message);

        assertThat(parsed).isPresent();
        ParsedShiftRequest request = parsed.get();

        assertThat(request.getUserFullName()).isEqualTo("Владислав Орловський");
        assertThat(request.getPlaceText()).isEqualToIgnoringCase("якова шепеля");
        assertThat(request.getStartTime()).isEqualTo(LocalTime.of(7, 0));
        assertThat(request.getEndTime()).isEqualTo(LocalTime.of(23, 0));

        assertThat(request.getDate()).isEqualTo(expectedDate(6, 12));
    }

    @Test
    void shouldParseNameWithInlineTimeInParentheses() {
        String message = "Кириченко Микита (18-9)\nСтрижавка 9.12";

        Optional<ParsedShiftRequest> parsed = parser.parse(message);

        assertThat(parsed).isPresent();
        ParsedShiftRequest request = parsed.get();

        assertThat(request.getUserFullName()).isEqualTo("Кириченко Микита");
        assertThat(request.getPlaceText()).containsIgnoringCase("Стрижавка");
        assertThat(request.getStartTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(request.getEndTime()).isEqualTo(LocalTime.of(9, 0));

        assertThat(request.getDate()).isEqualTo(expectedDate(9, 12));
    }

    private LocalDate expectedDate(int day, int month) {
        LocalDate expectedDate = LocalDate.of(LocalDate.now().getYear(), month, day);
        if (expectedDate.isBefore(LocalDate.now().minusDays(1))) {
            expectedDate = expectedDate.plusYears(1);
        }
        return expectedDate;
    }
}
