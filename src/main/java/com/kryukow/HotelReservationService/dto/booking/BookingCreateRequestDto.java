package com.kryukow.HotelReservationService.dto.booking;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
public class BookingCreateRequestDto {

    @NotNull(message = "ID комнаты обязателен")
    private Long roomId;

    @NotNull(message = "ID пользователя обязателен")
    private Long userId;   // позже можно будет брать из контекста Security

    @NotNull(message = "Дата заезда обязательна")
    @Future(message = "Дата заезда должна быть в будущем")
    private LocalDate checkInDate;

    @NotNull(message = "Дата выезда обязательна")
    @Future(message = "Дата выезда должна быть в будущем")
    private LocalDate checkOutDate;
}