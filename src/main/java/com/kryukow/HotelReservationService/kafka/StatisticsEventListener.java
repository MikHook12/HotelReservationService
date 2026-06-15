package com.kryukow.HotelReservationService.kafka;

import com.kryukow.HotelReservationService.dto.event.BookingCreatedEvent;
import com.kryukow.HotelReservationService.dto.event.UserRegisteredEvent;
import com.kryukow.HotelReservationService.model.EventType;
import com.kryukow.HotelReservationService.model.StatisticEvent;
import com.kryukow.HotelReservationService.repository.StatisticEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class StatisticsEventListener {

    private final StatisticEventRepository statisticEventRepository;


    @KafkaListener(topics = "user-events", groupId = "statistics-group")
    public void handlerUserRegistered(UserRegisteredEvent event){
        log.info("Received user registered event: {}", event);

        StatisticEvent stat = new StatisticEvent();
        stat.setEventType(EventType.USER_REGISTERED);
        stat.setPayload(Map.of("userId", event.userId()));
        stat.setTimestamp(event.localDateTime());

        statisticEventRepository.save(stat);
    }


    @KafkaListener(topics = "booking-events", groupId = "statistics-group")
    public void handlerBookingRegistered(BookingCreatedEvent event){
        log.info("Received booking created event: {}", event);

        StatisticEvent stat = new StatisticEvent();
        stat.setEventType(EventType.BOOKING_CREATED);
        stat.setPayload(Map.of(
                "userId", event.userId(),
                "roomId", event.roomId(),
                "checkInDate", event.checkInDate(),
                "checkOutDate", event.checkOutDate())
        );
        stat.setTimestamp(event.timestamp());

        statisticEventRepository.save(stat);
    }
}
