package com.example.group.service.util;

public final class SlotAvailabilityCalculator {

    private SlotAvailabilityCalculator() {
    }

    public static SlotAvailability calculate(int capacity, int activeBookings) {
        int safeCapacity = Math.max(0, capacity);
        int safeBookings = Math.max(0, activeBookings);

        int totalPlaces;
        int availablePlaces;

        if (safeCapacity < safeBookings) {
            totalPlaces = safeCapacity + safeBookings;
            availablePlaces = safeCapacity;
        } else {
            totalPlaces = safeCapacity;
            availablePlaces = safeCapacity - safeBookings;
        }

        totalPlaces = Math.max(totalPlaces, safeBookings);
        availablePlaces = Math.max(availablePlaces, 0);

        return new SlotAvailability(safeBookings, totalPlaces, availablePlaces);
    }

    public record SlotAvailability(int activeBookings, int totalPlaces, int availablePlaces) {
        public boolean isFull() {
            return availablePlaces <= 0;
        }
    }
}
