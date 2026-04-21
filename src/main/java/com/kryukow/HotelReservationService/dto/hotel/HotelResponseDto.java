package com.kryukow.HotelReservationService.dto.hotel;


import com.kryukow.HotelReservationService.dto.room.RoomResponseDto;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

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
    private List<RoomResponseDto> roomsList;
}
