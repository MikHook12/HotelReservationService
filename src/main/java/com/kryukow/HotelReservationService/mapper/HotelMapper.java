package com.kryukow.HotelReservationService.mapper;

import com.kryukow.HotelReservationService.dto.HotelCreateUpdateRequest;
import com.kryukow.HotelReservationService.dto.HotelResponseDto;
import com.kryukow.HotelReservationService.model.Hotel;
import org.mapstruct.Mapper;


@Mapper(componentModel = "spring")
public interface HotelMapper {
    HotelResponseDto toResponseDto(Hotel hotel);
    Hotel toEntity(HotelCreateUpdateRequest dto);
}
