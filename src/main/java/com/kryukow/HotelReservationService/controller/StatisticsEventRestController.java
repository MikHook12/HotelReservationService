package com.kryukow.HotelReservationService.controller;

import com.kryukow.HotelReservationService.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/admin/statistics")
@RequiredArgsConstructor
public class StatisticsEventRestController {

    private final StatisticsService statisticsService;

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportCsv() throws IOException {
        byte[] csv = statisticsService.exportToCsv();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=statistics.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}
