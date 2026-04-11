package com.kryukow.HotelReservationService.dto;


import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/// метод для CONTROLLER

@Getter
@Setter
@NoArgsConstructor
public class HotelResponseDto {
    private Long id;
    private String name;
    private String title;
    private String city;
    private String address;
    private Double distanceFromCityCenter;
    private Double rating;
    private Integer countOfRatings;
    // потом добавить список комнат с RoomsDto
}
