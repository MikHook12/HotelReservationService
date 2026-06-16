package com.kryukow.HotelReservationService.service;

import com.kryukow.HotelReservationService.model.StatisticEvent;
import com.kryukow.HotelReservationService.repository.StatisticEventRepository;
import com.opencsv.CSVWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.List;

@Service
public class StatisticsService {
    
    @Autowired
    private StatisticEventRepository repository;
    
    public byte[] exportToCsv() throws IOException {
        List<StatisticEvent> events = repository.findAll();
        
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             OutputStreamWriter writer = new OutputStreamWriter(out);
             CSVWriter csvWriter = new CSVWriter(writer)) {
            
            csvWriter.writeNext(new String[]{"id", "eventType", "userId", "roomId", "checkInDate", "checkOutDate", "timestamp"});
            
            for (StatisticEvent event : events) {
                String userId = (String) event.getPayload().getOrDefault("userId", "");
                String roomId = (String) event.getPayload().getOrDefault("roomId", "");
                String checkIn = (String) event.getPayload().getOrDefault("checkInDate", "");
                String checkOut = (String) event.getPayload().getOrDefault("checkOutDate", "");
                
                csvWriter.writeNext(new String[]{
                        String.valueOf(event.getId()),
                    event.getEventType().name(),
                    userId,
                    roomId,
                    checkIn,
                    checkOut,
                    event.getTimestamp().toString()
                });
            }
            
            csvWriter.flush();
            return out.toByteArray();
        }
    }
}