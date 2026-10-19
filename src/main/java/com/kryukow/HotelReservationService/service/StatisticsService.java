package com.kryukow.HotelReservationService.service;

import com.kryukow.HotelReservationService.model.StatisticEvent;
import com.kryukow.HotelReservationService.repository.StatisticsExportRepository;
import com.kryukow.HotelReservationService.repository.StatisticsExportRepository.ExportPage;
import com.opencsv.CSVWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StatisticsService {
    private static final int PAGE_SIZE = 500;
    private final StatisticsExportRepository repository;

    public void exportToCsv(OutputStream output) throws IOException {
        // Keyset pagination bounds memory and avoids keeping a Mongo cursor open for a slow client.
        CSVWriter writer = new CSVWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        writer.writeNext(new String[]{"id", "eventType", "userId", "roomId", "checkInDate", "checkOutDate", "timestamp"});
        ExportPage page = repository.findNextPage(null, PAGE_SIZE);
        while (!page.events().isEmpty()) {
            for (StatisticEvent event : page.events()) {
                Map<String, Object> payload = event.getPayload() == null ? Map.of() : event.getPayload();
                writer.writeNext(new String[]{text(event.getId()), text(event.getEventType()),
                        text(payload.get("userId")), text(payload.get("roomId")),
                        date(payload.get("checkInDate")), date(payload.get("checkOutDate")), text(event.getTimestamp())});
            }
            writer.flush();
            if (writer.checkError()) {
                throw new IOException("Failed to write statistics CSV");
            }
            if (page.events().size() < PAGE_SIZE) {
                break;
            }
            page = repository.findNextPage(page.lastId(), PAGE_SIZE);
        }
        writer.flush();
        if (writer.checkError()) {
            throw new IOException("Failed to write statistics CSV");
        }
        // The response owns the output stream, so do not close it here.
    }

    private String date(Object value) {
        // Older Mongo payloads may have stored LocalDate as BSON Date via the default zone converter.
        if (value instanceof Date date) {
            return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toString();
        }
        return text(value);
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
