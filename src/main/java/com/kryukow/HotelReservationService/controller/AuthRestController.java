package com.kryukow.HotelReservationService.controller;

import com.kryukow.HotelReservationService.dto.user.UserCreateUpdateRequestDto;
import com.kryukow.HotelReservationService.dto.user.UserResponseDto;
import com.kryukow.HotelReservationService.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/v1/auth")
public class AuthRestController {

    private final UserService userService;

    @org.springframework.web.bind.annotation.GetMapping("/csrf")
    public org.springframework.security.web.csrf.CsrfToken csrf(
            org.springframework.security.web.csrf.CsrfToken token) {
        return token;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponseDto> register(@Valid @RequestBody UserCreateUpdateRequestDto request){
        log.info("REST: регистрация нового пользователя: {}", request.getEmail());
        UserResponseDto userDto = userService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(userDto);
    }



}
