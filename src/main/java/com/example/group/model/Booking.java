package com.example.group.model;

import jakarta.persistence.*;
import lombok.Getter; import lombok.Setter;

import java.time.LocalDateTime;

@Entity @Table(name="bookings")
@Getter @Setter
public class Booking {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional=false, fetch=FetchType.LAZY)
    private User user;

    @ManyToOne(optional=false, fetch=FetchType.LAZY)
    private Slot slot;

    @Enumerated(EnumType.STRING)
    private BookingStatus status = BookingStatus.PENDING;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public enum BookingStatus { PENDING, CONFIRMED, CANCELLED, COMPLETED }
}
