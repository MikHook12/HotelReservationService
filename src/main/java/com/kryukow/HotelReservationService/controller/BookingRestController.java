package com.kryukow.HotelReservationService.controller;

import com.kryukow.HotelReservationService.dto.booking.BookingCreateRequestDto;
import com.kryukow.HotelReservationService.dto.booking.BookingResponseDto;
import com.kryukow.HotelReservationService.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
@Slf4j
public class BookingRestController {

    private final BookingService bookingService;


    @PostMapping
    public ResponseEntity<BookingResponseDto> create(@Valid @RequestBody BookingCreateRequestDto request) {
        log.info("REST: создать бронирование");

        BookingResponseDto bookingResponseDto = bookingService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(bookingResponseDto);
    }


    @GetMapping
    public ResponseEntity<Page<BookingResponseDto>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        log.info("REST: получить все бронирования page={}, size={}", page, size);

        Page<BookingResponseDto> bookingResponseDto = bookingService.getAll(page, size);
        return ResponseEntity.status(HttpStatus.OK).body(bookingResponseDto);
    }

    @GetMapping("/{id}")
    public ResponseEntity<BookingResponseDto> getById(@PathVariable Long id){
        BookingResponseDto bookingResponseDto = bookingService.getById(id);
        return ResponseEntity.status(HttpStatus.OK).body(bookingResponseDto);
    }

}
