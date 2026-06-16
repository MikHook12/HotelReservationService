package com.kryukow.HotelReservationService.controller;

import com.kryukow.HotelReservationService.dto.PageResponseDto;
import com.kryukow.HotelReservationService.dto.user.UserCreateUpdateRequestDto;
import com.kryukow.HotelReservationService.dto.user.UserResponseDto;
import com.kryukow.HotelReservationService.model.Role;
import com.kryukow.HotelReservationService.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Slf4j
public class UserRestController {

    private final UserService userService;

    @PostMapping
    public ResponseEntity<UserResponseDto> create(
            @RequestBody @Valid UserCreateUpdateRequestDto request
    ) {
        log.info("REST request: создать пользователя");

        UserResponseDto response = userService.create(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping
    public ResponseEntity<PageResponseDto<UserResponseDto>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        log.info("GET /api/v1/users?page={}&size={} - get all users", page, size);

        PageResponseDto<UserResponseDto> response =
                userService.getAll(page, size);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponseDto> getById(
            @PathVariable @Positive Long id
    ) {
        log.info("REST request: получить пользователя по id={}", id);

        UserResponseDto response = userService.getById(id);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/by-name")
    public ResponseEntity<PageResponseDto<UserResponseDto>> getByName(
            @RequestParam String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        log.info(
                "REST request: поиск пользователей по имени={}, page={}, size={}",
                name, page, size
        );

        PageResponseDto<UserResponseDto> response =
                userService.getAllByName(name, page, size);

        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserResponseDto> update(
            @PathVariable @Positive Long id,
            @RequestBody @Valid UserCreateUpdateRequestDto request
    ) {
        log.info("REST request: обновить пользователя id={}", id);

        UserResponseDto response =
                userService.update(id, request);

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable @Positive Long id
    ) {
        log.info("REST request: удалить пользователя id={}", id);

        userService.delete(id);

        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/role")
    public ResponseEntity<Void> changeRole(
            @PathVariable @Positive Long id,
            @RequestParam Role role
    ) {
        log.info(
                "REST request: изменить роль пользователя id={} на {}",
                id, role
        );

        userService.changeRole(id, role);

        return ResponseEntity.ok().build();
    }
}