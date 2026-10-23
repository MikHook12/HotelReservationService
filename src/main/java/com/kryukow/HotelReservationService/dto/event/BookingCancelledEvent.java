package com.kryukow.HotelReservationService.dto.event;

import com.kryukow.HotelReservationService.model.EventType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

public record BookingCancelledEvent(int schemaVersion, EventType eventType, Long bookingId, Long userId,
                                    Long roomId, LocalDate checkInDate, LocalDate checkOutDate,
                                    LocalDateTime timestamp) {
    public BookingCancelledEvent {
        if (schemaVersion != 1 || eventType != EventType.BOOKING_CANCELLED) {
            throw new IllegalArgumentException("Unsupported booking cancellation event schema or type");
        }
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(checkInDate, "checkInDate");
        Objects.requireNonNull(checkOutDate, "checkOutDate");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    public BookingCancelledEvent(Long bookingId, Long userId, Long roomId, LocalDate checkInDate,
                                 LocalDate checkOutDate, LocalDateTime timestamp) {
        this(1, EventType.BOOKING_CANCELLED, bookingId, userId, roomId, checkInDate, checkOutDate, timestamp);
    }
}
