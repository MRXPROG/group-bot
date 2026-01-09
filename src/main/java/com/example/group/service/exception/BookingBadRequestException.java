package com.example.group.service.exception;

public class BookingBadRequestException extends RuntimeException {

    public BookingBadRequestException(String message) {
        super(message);
    }
}
