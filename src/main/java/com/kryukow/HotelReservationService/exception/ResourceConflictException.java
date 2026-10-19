package com.kryukow.HotelReservationService.exception;

/** A valid request conflicts with the persisted state of a resource. */
public class ResourceConflictException extends IllegalStateException {
    public ResourceConflictException(String message) {
        super(message);
    }
}
