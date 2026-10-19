package com.kryukow.HotelReservationService.service;

import com.kryukow.HotelReservationService.model.EventType;
import com.kryukow.HotelReservationService.model.StatisticEvent;
import com.kryukow.HotelReservationService.repository.StatisticsExportRepository;
import com.kryukow.HotelReservationService.repository.StatisticsExportRepository.ExportPage;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StatisticsServiceTest {
    private final StatisticsExportRepository repository = mock(StatisticsExportRepository.class);
    private final StatisticsService service = new StatisticsService(repository);

    @Test
    void exportsTypedValuesUtf8AndEscapesCsv() throws Exception {
        StatisticEvent event = new StatisticEvent("тест,\"id", EventType.BOOKING_CREATED,
                Map.of("userId", 42L, "roomId", 7L, "checkInDate", LocalDate.of(2027, 1, 2)),
                LocalDateTime.of(2027, 1, 1, 10, 30));
        when(repository.findNextPage(null, 500)).thenReturn(new ExportPage(List.of(event), event.getId()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.exportToCsv(output);
        String csv = output.toString(StandardCharsets.UTF_8);
        assertThat(csv).contains("\"тест,\"\"id\"", "\"42\"", "\"7\"", "\"2027-01-02\"", "\"2027-01-01T10:30\"");
        verify(repository).findNextPage(null, 500);
    }

    @Test
    void readsNextPageByLastIdAndKeepsOnlyOnePageInMemory() throws Exception {
        List<StatisticEvent> first = IntStream.range(0, 500)
                .mapToObj(i -> new StatisticEvent(String.format("%04d", i), EventType.USER_REGISTERED,
                        Map.of("userId", (long) i), LocalDateTime.of(2027, 1, 1, 0, 0))).toList();
        ObjectId rawCursor = new ObjectId();
        when(repository.findNextPage(null, 500)).thenReturn(new ExportPage(first, rawCursor));
        when(repository.findNextPage(rawCursor, 500))
                .thenReturn(new ExportPage(List.of(new StatisticEvent("0500", EventType.USER_REGISTERED, Map.of(), null)), "0500"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.exportToCsv(output);
        assertThat(output.toString(StandardCharsets.UTF_8).lines().count()).isEqualTo(502);
        verify(repository).findNextPage(rawCursor, 500);
        verify(repository).findNextPage(null, 500);
    }

    @Test
    void exportsLegacyMongoDateAsIsoLocalDate() throws Exception {
        java.util.Date bsonDate = java.util.Date.from(LocalDate.of(2027, 1, 2)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
        StatisticEvent event = new StatisticEvent("legacy", EventType.BOOKING_CREATED,
                Map.of("checkInDate", bsonDate), null);
        when(repository.findNextPage(null, 500)).thenReturn(new ExportPage(List.of(event), event.getId()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.exportToCsv(output);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("\"2027-01-02\"");
    }

    @Test
    void reportsOutputFailure() {
        when(repository.findNextPage(null, 500)).thenReturn(new ExportPage(List.of(), null));
        OutputStream broken = new OutputStream() {
            @Override public void write(int b) throws IOException { throw new IOException("Client disconnected"); }
        };
        assertThatThrownBy(() -> service.exportToCsv(broken)).isInstanceOf(IOException.class);
    }
}
