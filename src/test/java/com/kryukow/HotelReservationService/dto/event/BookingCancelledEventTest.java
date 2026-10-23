package com.kryukow.HotelReservationService.dto.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kryukow.HotelReservationService.model.EventType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class BookingCancelledEventTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void rejectsUnsupportedSchemaVersionOrCreationDiscriminatorDuringDeserialization() throws Exception {
        LocalDate date = LocalDate.of(2027, 1, 1);
        BookingCancelledEvent event = new BookingCancelledEvent(1L, 2L, 3L, date, date.plusDays(1),
                LocalDateTime.of(2026, 12, 31, 10, 30));
        String json = mapper.writeValueAsString(event);
        assertThatThrownBy(() -> mapper.readValue(json.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                BookingCancelledEvent.class)).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.readValue(json.replace(EventType.BOOKING_CANCELLED.name(),
                EventType.BOOKING_CREATED.name()), BookingCancelledEvent.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
