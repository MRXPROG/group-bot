package com.example.group.dto;

import com.example.group.model.Booking;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SlotBookingDTO {
    private Long id;
    private Booking.BookingStatus status;
    private Long telegramUserId;
    private String username;
    private String firstName;
    private String lastName;
    private String phone;
}
