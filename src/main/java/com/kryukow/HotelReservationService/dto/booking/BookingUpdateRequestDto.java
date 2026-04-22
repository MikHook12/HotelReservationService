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
public class BookingUpdateRequestDto {

    @NotNull(message = "Новая дата заезда обязательна")
    @Future(message = "Дата заезда должна быть в будущем")
    private LocalDate checkInDate;

    @NotNull(message = "Новая дата выезда обязательна")
    @Future(message = "Дата выезда должна быть в будущем")
    private LocalDate checkOutDate;
}