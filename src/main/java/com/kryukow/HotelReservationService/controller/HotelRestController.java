package com.kryukow.HotelReservationService.controller;

import com.kryukow.HotelReservationService.dto.PageResponseDto;
import com.kryukow.HotelReservationService.dto.hotel.HotelCreateUpdateRequest;
import com.kryukow.HotelReservationService.dto.hotel.HotelResponseDto;
import com.kryukow.HotelReservationService.service.HotelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/hotels")
@RequiredArgsConstructor
@Slf4j
@Validated
public class HotelRestController {

    private final HotelService hotelService;

    @PostMapping
    public ResponseEntity<HotelResponseDto> create(
            @Valid @RequestBody HotelCreateUpdateRequest hotelDto) {

        log.info("POST /api/v1/hotels - create hotel: {}", hotelDto.getName());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(hotelService.create(hotelDto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<HotelResponseDto> update(
            @PathVariable Long id,
            @Valid @RequestBody HotelCreateUpdateRequest hotelDto) {

        log.info("PUT /api/v1/hotels/{} - update hotel", id);

        return ResponseEntity.ok(
                hotelService.update(id, hotelDto)
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<HotelResponseDto> get(
            @PathVariable Long id) {

        log.info("GET /api/v1/hotels/{}", id);

        return ResponseEntity.ok(
                hotelService.getById(id)
        );
    }

    @GetMapping
    public ResponseEntity<PageResponseDto<HotelResponseDto>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.info("GET /api/v1/hotels?page={}&size={}", page, size);

        return ResponseEntity.ok(
                hotelService.getAll(page, size)
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id) {

        log.info("DELETE /api/v1/hotels/{}", id);

        hotelService.delete(id);

        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/rating")
    public ResponseEntity<Void> changeRating(
            @PathVariable Long id,
            @RequestParam Double rating) {

        log.info("PATCH /api/v1/hotels/{}/rating", id);

        hotelService.changeRating(id, rating);

        return ResponseEntity.ok().build();
    }

    @GetMapping("/filter")
    public ResponseEntity<PageResponseDto<HotelResponseDto>> filterHotels(
            @RequestParam(required = false) Long id,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String address,
            @RequestParam(required = false) Double distanceMin,
            @RequestParam(required = false) Double distanceMax,
            @RequestParam(required = false) Double ratingMin,
            @RequestParam(required = false) Double ratingMax,
            @RequestParam(required = false) Integer ratingCountMin,
            @RequestParam(required = false) Integer ratingCountMax,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        log.info("GET /api/v1/hotels/filter - фильтрация отелей");

        return ResponseEntity.ok(
                hotelService.getAllWithFilters(
                        id,
                        name,
                        title,
                        city,
                        address,
                        distanceMin,
                        distanceMax,
                        ratingMin,
                        ratingMax,
                        ratingCountMin,
                        ratingCountMax,
                        page,
                        size
                )
        );
    }
}