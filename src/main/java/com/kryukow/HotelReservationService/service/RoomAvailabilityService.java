package com.kryukow.HotelReservationService.service;

import com.kryukow.HotelReservationService.dto.room.RoomResponseDto;
import com.kryukow.HotelReservationService.repository.RoomAvailabilityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
public class RoomAvailabilityService {
    public static final int HORIZON_DAYS = 365;
    private final RoomAvailabilityRepository repository;

    /** UTC today (inclusive) to today + 365 days (exclusive), checkout dates excluded. */
    public void populate(List<RoomResponseDto> rooms) {
        if (rooms.isEmpty()) return;
        LocalDate from = LocalDate.now(ZoneOffset.UTC);
        LocalDate until = from.plusDays(HORIZON_DAYS);
        List<Long> ids = rooms.stream().map(RoomResponseDto::getId).distinct().toList();
        Map<Long, TreeSet<LocalDate>> dates = new HashMap<>();
        for (var interval : repository.findIntervals(ids, from, until)) {
            LocalDate start = interval.getCheckInDate().isBefore(from) ? from : interval.getCheckInDate();
            LocalDate end = interval.getCheckOutDate().isAfter(until) ? until : interval.getCheckOutDate();
            start.datesUntil(end).forEach(date -> dates.computeIfAbsent(interval.getRoomId(), key -> new TreeSet<>()).add(date));
        }
        rooms.forEach(room -> {
            room.setUnavailableDates(List.copyOf(dates.getOrDefault(room.getId(), new TreeSet<>())));
            room.setAvailabilityFrom(from);
            room.setAvailabilityUntil(until);
        });
    }
}
