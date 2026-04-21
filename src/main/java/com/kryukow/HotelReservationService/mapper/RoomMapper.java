package com.kryukow.HotelReservationService.mapper;

import com.kryukow.HotelReservationService.dto.room.RoomCreateUpdateRequest;
import com.kryukow.HotelReservationService.dto.room.RoomResponseDto;
import com.kryukow.HotelReservationService.model.Room;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface RoomMapper {
    Room toEntity(RoomCreateUpdateRequest roomRequestDto);

    @Mapping(target = "unavailableDates", ignore = true)
    @Mapping(target = "hotelId", source = "hotel.id")
    RoomResponseDto toResponseDto(Room room);
}
