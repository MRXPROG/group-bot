package com.example.group.service.exception;

public class BookingNameConflictException extends RuntimeException {

    public BookingNameConflictException(String message) {
        super(message);
    }
}
