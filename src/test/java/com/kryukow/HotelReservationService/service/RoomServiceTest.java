package com.kryukow.HotelReservationService.service;

import com.kryukow.HotelReservationService.dto.PageResponseDto;
import com.kryukow.HotelReservationService.dto.room.RoomResponseDto;
import com.kryukow.HotelReservationService.mapper.RoomMapper;
import com.kryukow.HotelReservationService.model.Room;
import com.kryukow.HotelReservationService.repository.RoomRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private RoomMapper roomMapper;

    @InjectMocks
    private RoomService roomService;

    @Test
    void getAllWithFilters_ShouldCallRepositoryWithSpecAndPageable() {
        // given
        int page = 0;
        int size = 10;
        Long hotelId = 1L;
        Double priceMin = 100.0;
        Integer capacity = 2;

        Room room = new Room();
        RoomResponseDto dto = new RoomResponseDto();

        Page<Room> roomPage = new PageImpl<>(List.of(room));
        when(roomRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(roomPage);
        when(roomMapper.toResponseDto(room)).thenReturn(dto);

        // when
        PageResponseDto<RoomResponseDto> result = roomService.getAllWithFilters(
                null, null, priceMin, null, capacity, hotelId,
                null, null, page, size);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        verify(roomRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void getAllWithFilters_ShouldThrowException_WhenPageInvalid() {
        assertThatThrownBy(() -> roomService.getAllWithFilters(
                null, null, null, null, null, null,
                null, null, -1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page must be >= 0");
    }

    @Test
    void getAllWithFilters_ShouldThrowException_WhenSizeInvalid() {
        assertThatThrownBy(() -> roomService.getAllWithFilters(
                null, null, null, null, null, null,
                null, null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Size must be between 1 and 100");
    }
}