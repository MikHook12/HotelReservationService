package com.kryukow.HotelReservationService.kafka;

import com.kryukow.HotelReservationService.dto.event.BookingCreatedEvent;
import com.kryukow.HotelReservationService.dto.event.BookingCancelledEvent;
import com.kryukow.HotelReservationService.dto.event.UserRegisteredEvent;
import com.kryukow.HotelReservationService.model.EventType;
import com.kryukow.HotelReservationService.model.StatisticEvent;
import com.kryukow.HotelReservationService.repository.StatisticEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StatisticsEventListenerTest {
    private final StatisticEventRepository repository = mock(StatisticEventRepository.class);
    private final StatisticsEventListener listener = new StatisticsEventListener(repository);

    @Test
    void duplicateUserDeliveriesUseSameMongoId() {
        UserRegisteredEvent event = new UserRegisteredEvent(42L, LocalDateTime.now());
        listener.handlerUserRegistered(event, "outbox-id");
        listener.handlerUserRegistered(event, "outbox-id");
        ArgumentCaptor<StatisticEvent> captor = ArgumentCaptor.forClass(StatisticEvent.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(stat -> {
            assertThat(stat.getId()).isEqualTo("outbox-id");
            assertThat(stat.getEventType()).isEqualTo(EventType.USER_REGISTERED);
            assertThat(stat.getPayload()).containsEntry("userId", 42L);
            assertThat(stat.getTimestamp()).isEqualTo(event.localDateTime());
        });
    }

    @Test
    void bookingKeepsNumericIdsAndStoresDatesInIsoFormat() {
        LocalDate date = LocalDate.of(2027, 1, 2);
        BookingCreatedEvent event = new BookingCreatedEvent(42L, 7L, date, date.plusDays(2), LocalDateTime.now());
        listener.handleBookingEvent(event, "booking-outbox-id");
        ArgumentCaptor<StatisticEvent> captor = ArgumentCaptor.forClass(StatisticEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("booking-outbox-id");
        assertThat(captor.getValue().getPayload()).containsEntry("roomId", 7L).containsEntry("checkInDate", date.toString());
    }

    @Test
    void missingStableKeyFailsInsteadOfCreatingDuplicateStatistics() {
        UserRegisteredEvent event = new UserRegisteredEvent(42L, LocalDateTime.now());
        assertThatThrownBy(() -> listener.handlerUserRegistered(event, " "))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void cancellationIsIdempotentByEventKeyAndCannotBeClassifiedAsBookingCreated() {
        LocalDate date = LocalDate.of(2027, 1, 2);
        BookingCancelledEvent event = new BookingCancelledEvent(99L, 42L, 7L, date, date.plusDays(2),
                LocalDateTime.of(2026, 12, 1, 10, 30));
        listener.handleBookingEvent(event, "cancellation-outbox-id");
        listener.handleBookingEvent(event, "cancellation-outbox-id");
        ArgumentCaptor<StatisticEvent> captor = ArgumentCaptor.forClass(StatisticEvent.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(stat -> {
            assertThat(stat.getId()).isEqualTo("cancellation-outbox-id");
            assertThat(stat.getEventType()).isEqualTo(EventType.BOOKING_CANCELLED);
            assertThat(stat.getPayload()).containsEntry("bookingId", 99L).containsEntry("schemaVersion", 1)
                    .containsEntry("userId", 42L).containsEntry("roomId", 7L)
                    .containsEntry("checkInDate", "2027-01-02").containsEntry("checkOutDate", "2027-01-04")
                    .doesNotContainKeys("revenue", "payment", "amount");
            assertThat(stat.getTimestamp()).isEqualTo(event.timestamp());
        });
    }

    @Test
    void unsupportedBookingTopicPayloadIsRejectedInsteadOfCountedAsACreation() {
        assertThatThrownBy(() -> listener.handleBookingEvent(new UserRegisteredEvent(1L, LocalDateTime.now()), "id"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

}
