package com.example.group.service.exception;

public class BookingSlotUnavailableException extends RuntimeException {

    public BookingSlotUnavailableException(String message) {
        super(message);
    }
}
