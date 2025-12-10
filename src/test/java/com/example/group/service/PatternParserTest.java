package com.example.group.service;

import com.example.group.dto.ParsedShiftRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

class PatternParserTest {

    private StopWordService stopWordService;
    private PatternParser parser;

    @BeforeEach
    void setUp() {
        stopWordService = Mockito.mock(StopWordService.class);
        parser = new PatternParser(stopWordService);

        Mockito.when(stopWordService.containsAnyLocationToken(anyString()))
                .thenAnswer(invocation -> {
                    String text = invocation.getArgument(0, String.class);
                    return text != null && text.toLowerCase().contains("пошта");
                });
        Mockito.when(stopWordService.isStopWordToken(anyString())).thenReturn(false);
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
}
