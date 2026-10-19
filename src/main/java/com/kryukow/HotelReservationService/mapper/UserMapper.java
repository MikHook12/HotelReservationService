package com.kryukow.HotelReservationService.mapper;

import com.kryukow.HotelReservationService.dto.user.UserCreateUpdateRequestDto;
import com.kryukow.HotelReservationService.dto.user.UserResponseDto;
import com.kryukow.HotelReservationService.model.User;
import org.mapstruct.Mapping;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;


@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface UserMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "role", ignore = true)
    @Mapping(target = "bookings", ignore = true)
    User toEntity(UserCreateUpdateRequestDto request);

    UserResponseDto toResponseDto(User user);

}