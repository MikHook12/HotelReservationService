package com.kryukow.HotelReservationService.dto.event;

import java.time.LocalDateTime;

public record UserRegisteredEvent(Long userId, LocalDateTime localDateTime) {
}
