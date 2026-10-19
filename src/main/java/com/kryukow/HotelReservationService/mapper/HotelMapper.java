package com.kryukow.HotelReservationService.mapper;

import com.kryukow.HotelReservationService.dto.hotel.HotelCreateUpdateRequest;
import com.kryukow.HotelReservationService.dto.hotel.HotelResponseDto;
import com.kryukow.HotelReservationService.model.Hotel;
import org.mapstruct.Mapping;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;


@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR, uses = RoomMapper.class)
public interface HotelMapper {
    @Mapping(target = "roomsList", source = "rooms")
    HotelResponseDto toResponseDto(Hotel hotel);
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "rating", ignore = true)
    @Mapping(target = "ratingSum", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "countOfRatings", ignore = true)
    @Mapping(target = "rooms", ignore = true)
    Hotel toEntity(HotelCreateUpdateRequest dto);
}
