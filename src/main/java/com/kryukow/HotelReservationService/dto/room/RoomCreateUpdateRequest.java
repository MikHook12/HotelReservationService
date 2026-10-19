package com.kryukow.HotelReservationService.dto.room;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class RoomCreateUpdateRequest {

    @NotBlank(message = "Название комнаты не должно быть пустым")
    private String name;

    @NotBlank(message = "Описание комнаты не должно быть пустым")
    private String description;

    @NotNull(message = "Номер комнаты обязателен")
    @Positive(message = "Номер комнаты должен быть положительным")
    private Integer number;

    @NotNull(message = "Цена обязательна")
    @Positive(message = "Цена должна быть положительной")
    @Digits(integer = 36, fraction = 2, message = "Цена допускает не более двух знаков после запятой")
    private BigDecimal price;

    @NotNull(message = "Вместимость обязательна")
    @Positive(message = "Вместимость должна быть положительной")
    private Integer capacity;

    @NotNull(message = "ID отеля обязателен")
    @Positive(message = "ID отеля должен быть положительным")
    private Long hotelId;
}