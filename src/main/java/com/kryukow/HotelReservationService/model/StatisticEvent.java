package com.kryukow.HotelReservationService.model;

import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "statistics")
public class StatisticEvent {
    @Id
    private Long Id;
    private EventType eventType;
    private Map<String, Object> payload;
    private LocalDateTime timestamp;


}
