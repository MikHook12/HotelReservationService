package com.kryukow.HotelReservationService.exception;


/// 404

public class EntityNotFoundException extends RuntimeException {

    public EntityNotFoundException(String entityName, Long id) {
        super(String.format("%s not found with id: %d", entityName, id));
    }

    public EntityNotFoundException(String message) {
        super(message);
    }
}

