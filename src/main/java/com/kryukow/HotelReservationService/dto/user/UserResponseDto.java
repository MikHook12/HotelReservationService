package com.kryukow.HotelReservationService.dto.user;

import com.kryukow.HotelReservationService.model.Role;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class UserResponseDto {

    private Long id;
    private String name;
    private String email;
    private Role role;

}