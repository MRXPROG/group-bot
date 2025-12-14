package com.example.group.controllers.impl;

import com.example.group.controllers.MainBotApiClient;
import com.example.group.dto.SlotDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.example.group.service.exception.BookingConflictException;
import com.example.group.service.exception.BookingTimeRestrictionException;

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
            String url = baseUrl + "/slots?date=" + date;
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
            String url = baseUrl + "/slots/upcoming";
            ResponseEntity<SlotDTO[]> response = restTemplate.getForEntity(url, SlotDTO[].class);
            SlotDTO[] body = response.getBody();
            return body != null ? Arrays.asList(body) : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to load upcoming slots: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public SlotDTO getSlotById(Long slotId) {
        try {
            String url = baseUrl + "/slots/" + slotId;
            return restTemplate.getForObject(url, SlotDTO.class);
        } catch (Exception e) {
            log.error("Failed to load slot {}: {}", slotId, e.getMessage());
            return null;
        }
    }

    @Override
    public void createBooking(Long telegramUserId, Long slotId, String firstName, String lastName) {
        try {
            String url = baseUrl + "/bookings";

            var body = new BookingCreateRequest(telegramUserId, slotId, firstName, lastName);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<BookingCreateRequest> entity = new HttpEntity<>(body, headers);

            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (HttpStatusCodeException e) {
            String responseBody = e.getResponseBodyAsString();
            log.error("Failed to create booking for user={}, slot={}: {} : {}", telegramUserId, slotId,
                    e.getStatusCode(), responseBody);

            if (HttpStatus.CONFLICT.equals(e.getStatusCode())) {
                throw new BookingConflictException("Booking already exists for slot");
            }

            if (HttpStatus.BAD_REQUEST.equals(e.getStatusCode()) && isBookingDateRestricted(responseBody)) {
                throw new BookingTimeRestrictionException(resolveRestrictionMessage(responseBody));
            }

            throw new RuntimeException("Booking creation failed with status " + e.getStatusCode(), e);
        } catch (Exception e) {
            log.error("Failed to create booking for user={}, slot={}: {}",
                    telegramUserId, slotId, e.getMessage());
            throw new RuntimeException("Booking creation failed", e);
        }
    }

    private boolean isBookingDateRestricted(String responseBody) {
        if (responseBody == null) {
            return false;
        }

        String normalized = responseBody.toLowerCase();
        return normalized.contains("err.booking_date_restricted")
                || normalized.contains("запис на вибрану дату недоступний за правилами запису");
    }

    private String resolveRestrictionMessage(String responseBody) {
        String defaultMessage = "❌ Запис на вибрану дату недоступний за правилами запису.";

        if (responseBody == null || responseBody.isBlank()) {
            return defaultMessage;
        }

        if ("err.booking_date_restricted".equalsIgnoreCase(responseBody.trim())) {
            return defaultMessage;
        }

        return responseBody;
    }

    private record BookingCreateRequest(Long telegramUserId, Long slotId, String firstName, String lastName) {}
}
