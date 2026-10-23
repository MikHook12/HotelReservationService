package com.kryukow.HotelReservationService.kafka;

import com.kryukow.HotelReservationService.dto.event.BookingCreatedEvent;
import com.kryukow.HotelReservationService.dto.event.BookingCancelledEvent;
import com.kryukow.HotelReservationService.dto.event.UserRegisteredEvent;
import com.kryukow.HotelReservationService.model.EventType;
import com.kryukow.HotelReservationService.model.StatisticEvent;
import com.kryukow.HotelReservationService.repository.StatisticEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class StatisticsEventListener {

    private final StatisticEventRepository statisticEventRepository;


    @KafkaListener(topics = "user-events", groupId = "statistics-group")
    public void handlerUserRegistered(UserRegisteredEvent event, @Header(KafkaHeaders.RECEIVED_KEY) String eventId) {
        log.info("Received user registered event: {}", event);

        StatisticEvent stat = new StatisticEvent();
        stat.setId(requireEventId(eventId));
        stat.setEventType(EventType.USER_REGISTERED);
        stat.setPayload(Map.of("userId", event.userId()));
        stat.setTimestamp(event.localDateTime());

        statisticEventRepository.save(stat);
    }


    @KafkaListener(topics = "booking-events", groupId = "statistics-group")
    public void consumeBookingEvent(ConsumerRecord<String, Object> record) {
        handleBookingEvent(record.value(), record.key());
    }

    public void handleBookingEvent(Object event, String eventId) {
        if (event instanceof BookingCreatedEvent created) {
            handlerBookingRegistered(created, eventId);
        } else if (event instanceof BookingCancelledEvent cancelled) {
            handlerBookingCancelled(cancelled, eventId);
        } else {
            throw new IllegalArgumentException("Unsupported booking event payload type: "
                    + (event == null ? "null" : event.getClass().getName()));
        }
    }

    public void handlerBookingRegistered(BookingCreatedEvent event, @Header(KafkaHeaders.RECEIVED_KEY) String eventId) {
        log.info("Received booking created event: {}", event);

        StatisticEvent stat = new StatisticEvent();
        stat.setId(requireEventId(eventId));
        stat.setEventType(EventType.BOOKING_CREATED);
        stat.setPayload(Map.of(
                "userId", event.userId(),
                "roomId", event.roomId(),
                "checkInDate", event.checkInDate().toString(),
                "checkOutDate", event.checkOutDate().toString())
        );
        stat.setTimestamp(event.timestamp());

        statisticEventRepository.save(stat);
    }

    public void handlerBookingCancelled(BookingCancelledEvent event, String eventId) {
        log.info("Received booking cancelled event: {}", event);
        StatisticEvent stat = new StatisticEvent();
        stat.setId(requireEventId(eventId));
        stat.setEventType(EventType.BOOKING_CANCELLED);
        stat.setPayload(Map.of(
                "schemaVersion", event.schemaVersion(),
                "bookingId", event.bookingId(),
                "userId", event.userId(),
                "roomId", event.roomId(),
                "checkInDate", event.checkInDate().toString(),
                "checkOutDate", event.checkOutDate().toString()));
        stat.setTimestamp(event.timestamp());
        statisticEventRepository.save(stat);
    }

    private String requireEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("Statistics event must have an outbox ID as Kafka key");
        }
        return eventId;
    }
}
