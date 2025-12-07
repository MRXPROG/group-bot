package com.example.group.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParsedShiftRequest {

    /** Дата смены */
    private LocalDate date;

    /** Время начала */
    private LocalTime startTime;

    /** Время окончания */
    private LocalTime endTime;

    /** Текст локации (как написал пользователь) */
    private String placeText;

    /** Как человек написал своё имя (Фамилия Имя или Имя Фамилия) */
    private String userFullName;
}
