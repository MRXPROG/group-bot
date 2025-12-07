package com.example.group.controllers.impl;

import com.example.group.controllers.MainBotApiClient;
import com.example.group.dto.SlotDTO;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MainBotApiClientImpl implements MainBotApiClient {

    private final RestTemplate restTemplate;

    @Value("${mainbot.api.base-url}")
    private String baseUrl;

    @Override
    public List<SlotDTO> getSlotsForDate(LocalDate date) {
        try {
            String url = baseUrl + "/api/group/slots?date=" + date;
            ResponseEntity<SlotDTO[]> response = restTemplate.getForEntity(url, SlotDTO[].class);
            SlotDTO[] body = response.getBody();
            return body != null ? Arrays.asList(body) : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to load slots for date {}: {}", date, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<SlotDTO> getUpcomingSlots() {
        try {
            String url = baseUrl + "/api/group/slots/upcoming";
            ResponseEntity<SlotDTO[]> response = restTemplate.getForEntity(url, SlotDTO[].class);
            SlotDTO[] body = response.getBody();
            return body != null ? Arrays.asList(body) : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to load upcoming slots: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void createBooking(Long telegramUserId, Long slotId) {
        try {
            String url = baseUrl + "/api/group/bookings";

            var body = new BookingCreateRequest(telegramUserId, slotId);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<BookingCreateRequest> entity = new HttpEntity<>(body, headers);

            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            log.error("Failed to create booking for user={}, slot={}: {}",
                    telegramUserId, slotId, e.getMessage());
            throw new RuntimeException("Booking creation failed", e);
        }
    }

    // внутренний DTO для POST-запроса
    private record BookingCreateRequest(Long telegramUserId, Long slotId) {}
}
