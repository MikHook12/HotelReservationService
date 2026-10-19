package com.kryukow.HotelReservationService.service;

import com.kryukow.HotelReservationService.dto.room.RoomResponseDto;
import com.kryukow.HotelReservationService.repository.RoomAvailabilityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class RoomAvailabilityServiceTest {
    @Mock RoomAvailabilityRepository repository;
    @InjectMocks RoomAvailabilityService service;

    @Test
    void longHistoryIsClippedTo365DaysAndFetchedOnceForAllRooms() {
        RoomResponseDto first = new RoomResponseDto(); first.setId(1L);
        RoomResponseDto second = new RoomResponseDto(); second.setId(2L);
        RoomAvailabilityRepository.Interval interval = mock(RoomAvailabilityRepository.Interval.class);
        when(interval.getRoomId()).thenReturn(1L);
        when(interval.getCheckInDate()).thenReturn(LocalDate.of(1900, 1, 1));
        when(interval.getCheckOutDate()).thenReturn(LocalDate.of(9999, 1, 1));
        when(repository.findIntervals(eq(List.of(1L, 2L)), any(), any())).thenReturn(List.of(interval, interval));
        service.populate(List.of(first, second));
        assertThat(first.getUnavailableDates()).hasSize(365).doesNotHaveDuplicates().isSorted();
        assertThat(first.getUnavailableDates().get(0)).isEqualTo(first.getAvailabilityFrom());
        assertThat(first.getUnavailableDates().get(364)).isEqualTo(first.getAvailabilityUntil().minusDays(1));
        assertThat(second.getUnavailableDates()).isEmpty();
        verify(repository, times(1)).findIntervals(eq(List.of(1L, 2L)), any(), any());
    }

    @Test
    void checkoutDayIsExcluded() {
        LocalDate from = LocalDate.now(ZoneOffset.UTC).plusDays(10);
        RoomResponseDto dto = new RoomResponseDto(); dto.setId(1L);
        RoomAvailabilityRepository.Interval interval = mock(RoomAvailabilityRepository.Interval.class);
        when(interval.getRoomId()).thenReturn(1L);
        when(interval.getCheckInDate()).thenReturn(from);
        when(interval.getCheckOutDate()).thenReturn(from.plusDays(2));
        when(repository.findIntervals(any(), any(), any())).thenReturn(List.of(interval));
        service.populate(List.of(dto));
        assertThat(dto.getUnavailableDates()).containsExactly(from, from.plusDays(1));
    }
}
