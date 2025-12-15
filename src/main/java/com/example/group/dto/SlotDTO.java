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

    private LocalDateTime start;
    private LocalDateTime end;

    private int capacity;
    private int bookedCount;
    private boolean innRequired;

    private SlotStatus status = SlotStatus.READY;

    private List<SlotBookingDTO> bookings = new ArrayList<>();

    public enum SlotStatus { READY, RESERVED, COMPLETED }
}