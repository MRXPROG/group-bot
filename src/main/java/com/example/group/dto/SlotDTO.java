package com.example.group.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class SlotDTO {
    private Long id;
    private String placeName;
    private String cityName;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    private int capacity;
    private int bookedCount;
}