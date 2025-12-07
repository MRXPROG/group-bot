package com.example.group.controllers;

import com.example.group.service.TelegramBot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

    private final TelegramBot bot;
    private final BookingStatusService status;

    /** Пришло новое бронирование — разослать во все админ-чаты. */
    @PostMapping("/booking")
    public ResponseEntity<Void> receive(@RequestBody BookingDetailsDTO dto) {
        bot.broadcast(dto);
        return ResponseEntity.ok().build();
    }

    /** Отмена пользователем — сделать reply + обновить статус во всех чатах. */
    @PostMapping("/cancel")
    public ResponseEntity<Void> onUserCancel(@RequestBody BookingCancelDTO dto) {
        try {
            try {
                status.cancel(dto.bookingId());
            } catch (Exception e) {
                log.warn("status.cancel failed for bookingId={}, err={}", dto.bookingId(), e.toString());
            }

            bot.replyCancellationToAll(dto);
            bot.reflectStatus(dto.bookingId(), "❌ Скасовано");

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("onUserCancel failed: {}", e.toString());
            return ResponseEntity.internalServerError().build();
        }
    }
}
