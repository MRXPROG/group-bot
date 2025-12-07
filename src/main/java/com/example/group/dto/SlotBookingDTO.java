package com.example.group.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SlotBookingDTO {
    private Long userId;
    private String fullName;
    private BookingStatusDTO status;
}
