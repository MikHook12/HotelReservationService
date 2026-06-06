package com.kryukow.HotelReservationService.controller;


import com.kryukow.HotelReservationService.dto.PageResponseDto;
import com.kryukow.HotelReservationService.dto.room.RoomCreateUpdateRequest;
import com.kryukow.HotelReservationService.dto.room.RoomResponseDto;
import com.kryukow.HotelReservationService.service.RoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/rooms")
@RequiredArgsConstructor
@Slf4j
@Validated
public class RoomRestController {

    private final RoomService roomService;

    @PostMapping
    public RoomResponseDto create(@Valid @RequestBody RoomCreateUpdateRequest roomRequestDto) {
        log.info("POST /api/v1/rooms - create room: {}", roomRequestDto.getName());  // ← добавить
        return roomService.create(roomRequestDto);
    }

    @PutMapping("/{id}")
    public RoomResponseDto update(@PathVariable Long id,
                                  @Valid @RequestBody RoomCreateUpdateRequest roomRequestDto) {
        log.info("PUT /api/v1/rooms/{} - update room", id);  // ← добавить
        return roomService.update(id, roomRequestDto);
    }

    @GetMapping("/{id}")
    public RoomResponseDto get(@PathVariable Long id) {
        log.info("GET /api/v1/rooms/{} - get room by id", id);  // ← добавить
        return roomService.getById(id);
    }

    @GetMapping
    public PageResponseDto<RoomResponseDto> getAll(@RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        log.info("GET /api/v1/rooms?page={}&size={} - get all rooms", page, size);  // ← добавить
        return roomService.getAll(page, size);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        log.info("DELETE /api/v1/rooms/{} - delete room", id);  // ← добавить
        roomService.delete(id);
    }


    @GetMapping("/filter")
    public PageResponseDto<RoomResponseDto> filterRooms(
            @RequestParam(required = false) String id,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Double priceMin,
            @RequestParam(required = false) Double priceMax,
            @RequestParam(required = false) Integer capacity,
            @RequestParam(required = false) Long hotelId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkInDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOutDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        log.info("GET /api/v1/rooms/filter – фильтрация комнат");
        if ((checkInDate != null && checkOutDate == null) || (checkInDate == null && checkOutDate != null)) {
            checkInDate = null;
            checkOutDate = null;
            log.warn("Only one date provided. Date filtering disabled.");
        }
        return roomService.getAllWithFilters(
                id, name, priceMin, priceMax, capacity, hotelId,
                checkInDate, checkOutDate,
                page, size
        );
    }


}
