package com.kryukow.HotelReservationService.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kryukow.HotelReservationService.dto.event.BookingCreatedEvent;
import com.kryukow.HotelReservationService.dto.event.UserRegisteredEvent;
import com.kryukow.HotelReservationService.model.EventType;
import com.kryukow.HotelReservationService.model.OutboxEvent;
import com.kryukow.HotelReservationService.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class DomainEventPublisher {
    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public void publishUserRegistered(Long userId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        store("user-events", EventType.USER_REGISTERED, new UserRegisteredEvent(userId, now), now);
    }

    public void publishBookingCreated(Long userId, Long roomId, LocalDate checkInDate, LocalDate checkOutDate) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        store("booking-events", EventType.BOOKING_CREATED,
                new BookingCreatedEvent(userId, roomId, checkInDate, checkOutDate, now), now);
    }

    private void store(String topic, EventType type, Object payload, LocalDateTime now) {
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setTopic(topic);
        event.setEventType(type);
        event.setCreatedAt(now);
        event.setNextAttemptAt(now);
        try {
            event.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize domain event", e);
        }
        repository.save(event);
    }
}
