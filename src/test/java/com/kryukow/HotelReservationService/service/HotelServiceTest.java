package com.kryukow.HotelReservationService.service;

import com.kryukow.HotelReservationService.dto.hotel.HotelResponseDto;
import com.kryukow.HotelReservationService.dto.PageResponseDto;
import com.kryukow.HotelReservationService.mapper.HotelMapper;
import com.kryukow.HotelReservationService.model.Hotel;
import com.kryukow.HotelReservationService.repository.HotelRepository;
import com.kryukow.HotelReservationService.repository.RoomAvailabilityRepository;
import java.math.BigDecimal;
import java.util.Optional;
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

    @Mock private RoomAvailabilityService availabilityService;
    @Mock private RoomAvailabilityRepository availabilityRepository;

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

    @Test
    void ratingsUseExactSumAndNewCountRatherThanRoundedAverage() {
        Hotel hotel = new Hotel();
        when(repository.findById(1L)).thenReturn(Optional.of(hotel));
        service.changeRating(1L, 1.0);
        service.changeRating(1L, 2.0);
        service.changeRating(1L, 2.0);
        service.changeRating(1L, 5.0);
        assertThat(hotel.getRatingSum()).isEqualByComparingTo("10");
        assertThat(hotel.getCountOfRatings()).isEqualTo(4);
        assertThat(hotel.getRating()).isEqualTo(2.5);
    }

    @Test
    void invalidRatingsNeverWrite() {
        for (Double mark : new Double[]{null, 0.0, 5.01, Double.NaN, Double.POSITIVE_INFINITY, 1.12345}) {
            assertThatThrownBy(() -> service.changeRating(1L, mark)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test
    void hotelWithBookingHistoryCannotBeDeleted() {
        Hotel hotel = new Hotel();
        com.kryukow.HotelReservationService.model.Room room = new com.kryukow.HotelReservationService.model.Room();
        room.setId(2L);
        when(repository.lockById(1L)).thenReturn(Optional.of(hotel));
        when(availabilityRepository.lockByHotelId(1L)).thenReturn(List.of(room));
        when(availabilityRepository.hasBookings(List.of(2L))).thenReturn(true);
        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("booking history");
        verify(repository, never()).delete(any(Hotel.class));
        verify(availabilityRepository, never()).deleteAll(any());
    }
    @Test
    void unbookedHotelRemovesManagedRoomsBeforeTheirParent() {
        Hotel hotel = new Hotel();
        com.kryukow.HotelReservationService.model.Room room = new com.kryukow.HotelReservationService.model.Room();
        room.setId(2L);
        List<com.kryukow.HotelReservationService.model.Room> children = List.of(room);
        when(repository.lockById(1L)).thenReturn(Optional.of(hotel));
        when(availabilityRepository.lockByHotelId(1L)).thenReturn(children);
        when(availabilityRepository.hasBookings(List.of(2L))).thenReturn(false);

        service.delete(1L);

        var order = inOrder(availabilityRepository, repository);
        order.verify(availabilityRepository).deleteAll(children);
        order.verify(repository).delete(hotel);
    }

}
