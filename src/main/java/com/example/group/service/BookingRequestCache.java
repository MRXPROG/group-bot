package com.example.group.service;

import com.example.group.dto.SlotDTO;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BookingRequestCache {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final Map<String, BookingRequestState> storage = new ConcurrentHashMap<>();

    public String store(Message message, String userFullName, List<SlotDTO> slots) {
        String token = UUID.randomUUID().toString();
        BookingRequestState state = BookingRequestState.builder()
                .token(token)
                .chatId(message.getChatId())
                .userId(message.getFrom().getId())
                .userMessage(message)
                .userFullName(userFullName)
                .slots(List.copyOf(slots))
                .currentIndex(0)
                .expiresAt(LocalDateTime.now().plus(TTL))
                .build();
        storage.put(token, state);
        return token;
    }

    public Optional<BookingRequestState> get(String token) {
        BookingRequestState state = storage.get(token);
        if (state == null) {
            return Optional.empty();
        }
        if (state.getExpiresAt().isBefore(LocalDateTime.now())) {
            storage.remove(token);
            return Optional.empty();
        }
        return Optional.of(state);
    }

    public void update(BookingRequestState state) {
        storage.put(state.getToken(), state);
    }

    public void remove(String token) {
        storage.remove(token);
    }

    @Data
    @Builder
    public static class BookingRequestState {
        private String token;
        private Long chatId;
        private Long userId;
        private Message userMessage;
        private String userFullName;
        private List<SlotDTO> slots;
        private int currentIndex;
        private Integer controlMessageId;
        private LocalDateTime expiresAt;
    }
}

