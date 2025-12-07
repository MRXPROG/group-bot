package com.example.group.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SlotDTO {
    private Long id;
    private String placeName;
    private String cityName;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    private int capacity;
    private int bookedCount;
    private boolean innRequired;

    private List<SlotBookingDTO> bookings = new ArrayList<>();
}