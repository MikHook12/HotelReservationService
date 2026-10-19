package com.kryukow.HotelReservationService.controller;

import com.kryukow.HotelReservationService.dto.ErrorResponse;
import com.kryukow.HotelReservationService.exception.BookingHistoryConflictException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;

/** Only domain deletion conflicts expose their known-safe explanation. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {HotelRestController.class, RoomRestController.class})
public class HotelRoomExceptionHandler {
    @ExceptionHandler(BookingHistoryConflictException.class)
    public ResponseEntity<ErrorResponse> historyConflict(BookingHistoryConflictException ex, WebRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(409, "Conflict",
                ex.getMessage(), request.getDescription(false), LocalDateTime.now()));
    }
}
