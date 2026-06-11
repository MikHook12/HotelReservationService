package com.kryukow.HotelReservationService.repository;

import ch.qos.logback.core.joran.event.StartEvent;
import com.kryukow.HotelReservationService.model.StatisticEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface StatisticEventRepository extends MongoRepository<StatisticEvent, Long> {
}
