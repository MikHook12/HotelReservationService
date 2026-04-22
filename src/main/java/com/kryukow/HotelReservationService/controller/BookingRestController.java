package com.kryukow.HotelReservationService.controller;

import com.kryukow.HotelReservationService.dto.booking.BookingCreateRequestDto;
import com.kryukow.HotelReservationService.dto.booking.BookingResponseDto;
import com.kryukow.HotelReservationService.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
@Slf4j
public class BookingRestController {

    private final BookingService bookingService;


    @PostMapping
    public BookingResponseDto create(@RequestBody @Valid BookingCreateRequestDto request) {
        log.info("REST: создать бронирование");

        return bookingService.create(request);
    }


    @GetMapping
    public Page<BookingResponseDto> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        log.info("REST: получить все бронирования page={}, size={}", page, size);

        return bookingService.getAll(page, size);
    }
}
