package com.kryukow.HotelReservationService.dto.hotel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class HotelCreateUpdateRequest {

    @NotBlank(message = "Название отеля не должно быть пустым")
    private String name;

    @NotBlank(message = "Заголовок объявления не должен быть пустым")
    private String title;

    @NotBlank(message = "Город не должен быть пустым")
    private String city;

    @NotBlank(message = "Адрес не должен быть пустым")
    private String address;

    @NotNull(message = "Расстояние до центра обязательно")
    @PositiveOrZero(message = "Расстояние до центра не может быть отрицательным")
    private Double distanceFromCityCenter;
}
