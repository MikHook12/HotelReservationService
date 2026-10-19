package com.kryukow.HotelReservationService.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kryukow.HotelReservationService.dto.event.BookingCreatedEvent;
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
}
