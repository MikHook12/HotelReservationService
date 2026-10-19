package com.kryukow.HotelReservationService.mapper;

import com.kryukow.HotelReservationService.dto.room.RoomCreateUpdateRequest;
import com.kryukow.HotelReservationService.dto.room.RoomResponseDto;
import com.kryukow.HotelReservationService.model.Room;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface RoomMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "hotel", ignore = true)
    @Mapping(target = "bookings", ignore = true)
    Room toEntity(RoomCreateUpdateRequest roomRequestDto);

    @Mapping(target = "availabilityFrom", ignore = true)
    @Mapping(target = "availabilityUntil", ignore = true)
    @Mapping(target = "unavailableDates", ignore = true)
    @Mapping(target = "hotelId", source = "hotel.id")
    RoomResponseDto toResponseDto(Room room);
}
