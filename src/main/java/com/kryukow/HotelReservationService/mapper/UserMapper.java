package com.kryukow.HotelReservationService.mapper;

import com.kryukow.HotelReservationService.dto.user.UserCreateUpdateRequestDto;
import com.kryukow.HotelReservationService.dto.user.UserResponseDto;
import com.kryukow.HotelReservationService.model.User;
import org.mapstruct.Mapper;


@Mapper(componentModel = "spring")
public interface UserMapper {

    User toEntity(UserCreateUpdateRequestDto request);

    UserResponseDto toResponseDto(User user);

}