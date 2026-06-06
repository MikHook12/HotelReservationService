package com.kryukow.HotelReservationService.service;

import com.kryukow.HotelReservationService.dto.hotel.HotelResponseDto;
import com.kryukow.HotelReservationService.dto.PageResponseDto;
import com.kryukow.HotelReservationService.mapper.HotelMapper;
import com.kryukow.HotelReservationService.model.Hotel;
import com.kryukow.HotelReservationService.repository.HotelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HotelServiceTest {

    @Mock
    private HotelRepository repository;

    @Mock
    private HotelMapper mapper;

    @InjectMocks
    private HotelService service;

    @Test
    void getAllWithFilters_ShouldCallRepositoryWithSpecAndPageable() {
        // given
        int page = 0;
        int size = 10;
        String name = "Grand";
        String city = "Moscow";

        Hotel hotel = new Hotel();
        HotelResponseDto dto = new HotelResponseDto();

        Page<Hotel> hotelPage = new PageImpl<>(List.of(hotel));
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(hotelPage);
        when(mapper.toResponseDto(hotel)).thenReturn(dto);

        // when
        PageResponseDto<HotelResponseDto> result = service.getAllWithFilters(
                null, name, null, city, null,
                null, null, null, null, null, null,
                page, size);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        verify(repository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void getAllWithFilters_ShouldThrowException_WhenPageInvalid() {
        assertThatThrownBy(() -> service.getAllWithFilters(
                null, null, null, null, null,
                null, null, null, null, null, null,
                -1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Page must be >= 0");
    }

    @Test
    void getAllWithFilters_ShouldThrowException_WhenSizeInvalid() {
        assertThatThrownBy(() -> service.getAllWithFilters(
                null, null, null, null, null,
                null, null, null, null, null, null,
                0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Size must be between 1 and 100");
    }
}