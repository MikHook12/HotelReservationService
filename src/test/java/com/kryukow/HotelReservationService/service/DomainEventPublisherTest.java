package com.kryukow.HotelReservationService.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kryukow.HotelReservationService.dto.event.BookingCreatedEvent;
import com.kryukow.HotelReservationService.dto.event.BookingCancelledEvent;
import com.kryukow.HotelReservationService.dto.event.UserRegisteredEvent;
import com.kryukow.HotelReservationService.model.EventType;
import com.kryukow.HotelReservationService.model.OutboxEvent;
import com.kryukow.HotelReservationService.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DomainEventPublisherTest {
    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DomainEventPublisher publisher = new DomainEventPublisher(repository, mapper);

    @Test
    void storesBothDtoShapesWithStableUniqueIdsInsteadOfSendingToKafka() throws Exception {
        LocalDate checkIn = LocalDate.of(2027, 1, 2);
        publisher.publishUserRegistered(42L);
        publisher.publishBookingCreated(42L, 7L, checkIn, checkIn.plusDays(2));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository, times(2)).save(captor.capture());
        OutboxEvent user = captor.getAllValues().get(0);
        OutboxEvent booking = captor.getAllValues().get(1);
        assertThat(user.getId()).isNotNull().isNotEqualTo(booking.getId());
        assertThat(user.getTopic()).isEqualTo("user-events");
        assertThat(user.getEventType()).isEqualTo(EventType.USER_REGISTERED);
        assertThat(user.getPublishedAt()).isNull();
        assertThat(user.getNextAttemptAt()).isEqualTo(user.getCreatedAt());
        assertThat(mapper.readValue(user.getPayload(), UserRegisteredEvent.class).userId()).isEqualTo(42L);
        assertThat(booking.getTopic()).isEqualTo("booking-events");
        assertThat(mapper.readValue(booking.getPayload(), BookingCreatedEvent.class).checkInDate()).isEqualTo(checkIn);
    }

    @Test
    void storesVersionedCancellationOnBookingTopicWithItsOwnEventType() throws Exception {
        LocalDate checkIn = LocalDate.of(2027, 1, 2);
        publisher.publishBookingCancelled(99L, 42L, 7L, checkIn, checkIn.plusDays(2));
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.getId()).isNotNull();
        assertThat(event.getTopic()).isEqualTo("booking-events");
        assertThat(event.getEventType()).isEqualTo(EventType.BOOKING_CANCELLED);
        assertThat(event.getPublishedAt()).isNull();
        BookingCancelledEvent dto = mapper.readValue(event.getPayload(), BookingCancelledEvent.class);
        assertThat(dto.schemaVersion()).isEqualTo(1);
        assertThat(dto.eventType()).isEqualTo(EventType.BOOKING_CANCELLED);
        assertThat(dto.bookingId()).isEqualTo(99L);
        assertThat(dto.userId()).isEqualTo(42L);
        assertThat(dto.roomId()).isEqualTo(7L);
        assertThat(dto.checkInDate()).isEqualTo(checkIn);
        assertThat(dto.timestamp()).isEqualTo(event.getCreatedAt());
        var json = mapper.readTree(event.getPayload());
        assertThat(json.size()).isEqualTo(8);
        assertThat(json.has("revenue")).isFalse();
        assertThat(json.has("payment")).isFalse();
        assertThat(json.has("amount")).isFalse();
    }

}
