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
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Slf4j
public class UserRestController {

    private final UserService userService;

    @PostMapping
    public UserResponseDto create(@RequestBody @Valid UserCreateUpdateRequestDto request) {
        log.info("REST request: создать пользователя");

        return userService.create(request);
    }

    @GetMapping
    public PageResponseDto<UserResponseDto> getAll(@RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        log.info("GET /api/v1/rooms?page={}&size={} - get all users", page, size);
        return userService.getAll(page, size);
    }

    @GetMapping("/{id}")
    public UserResponseDto getById(@PathVariable @Positive Long id) {
        log.info("REST request: получить пользователя по id={}", id);

        return userService.getById(id);
    }

    @GetMapping("/by-name")
    public PageResponseDto<UserResponseDto> getByName(
            @RequestParam String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        log.info("REST request: поиск пользователей по имени={}, page={}, size={}", name, page, size);

        return userService.getAllByName(name, page, size);
    }



    @PutMapping("/{id}")
    public UserResponseDto update(
            @PathVariable @Positive Long id,
            @RequestBody @Valid UserCreateUpdateRequestDto request
    ) {
        log.info("REST request: обновить пользователя id={}", id);

        return userService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable @Positive Long id) {
        log.info("REST request: удалить пользователя id={}", id);

        userService.delete(id);
    }


    @PatchMapping("/{id}/role")
    public void changeRole(
            @PathVariable @Positive Long id,
            @RequestParam Role role
    ) {
        log.info("REST request: изменить роль пользователя id={} на {}", id, role);

        userService.changeRole(id, role);
    }
}
