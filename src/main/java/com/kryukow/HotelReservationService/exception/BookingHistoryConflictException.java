package com.kryukow.HotelReservationService.exception;

import org.springframework.dao.DataIntegrityViolationException;

/** Safe domain message, distinct from database errors which must not expose SQL. */
public class BookingHistoryConflictException extends DataIntegrityViolationException {
    public BookingHistoryConflictException(String resource) {
        super("Cannot delete " + resource + " with booking history");
    }
}
