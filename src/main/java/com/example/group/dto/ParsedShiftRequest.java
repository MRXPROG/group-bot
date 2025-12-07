package com.example.group.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@AllArgsConstructor
public class ParsedShiftRequest {

    /** Дата смены */
    private LocalDate date;

    /** Время начала */
    private LocalTime startTime;

    /** Время окончания */
    private LocalTime endTime;

    /** Как человек написал своё имя (Фамилия Имя или Имя Фамилия) */
    private String fullName;

    /** Текст локации (как написал пользователь) */
    private String placeText;
}
