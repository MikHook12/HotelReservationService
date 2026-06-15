package com.kryukow.HotelReservationService.dto.event;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record BookingCreatedEvent(Long userId, Long roomId, LocalDate checkInDate, LocalDate checkOutDate, LocalDateTime timestamp) {
}
