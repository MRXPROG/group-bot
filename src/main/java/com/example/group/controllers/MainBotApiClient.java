package com.example.group.controllers;

import com.example.group.dto.SlotDTO;

import java.time.LocalDate;
import java.util.List;

public interface MainBotApiClient {

    /** Все слоты на конкретную дату (для поиска по паттерну) */
    List<SlotDTO> getSlotsForDate(LocalDate date);

    /** Все будущие слоты (для утренней рассылки) */
    List<SlotDTO> getUpcomingSlots();

    SlotDTO getSlotById(Long slotId);

    /** Создать запись на слот для пользователя */
    void createBooking(Long telegramUserId, Long slotId, String username, String firstName, String lastName);
}
