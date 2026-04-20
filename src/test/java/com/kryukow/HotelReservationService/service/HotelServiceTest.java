package com.kryukow.HotelReservationService.service;

import com.kryukow.HotelReservationService.dto.HotelCreateUpdateRequest;
import com.kryukow.HotelReservationService.dto.HotelResponseDto;
import com.kryukow.HotelReservationService.dto.PageResponseDto;
import com.kryukow.HotelReservationService.exception.EntityNotFoundException;
import com.kryukow.HotelReservationService.mapper.HotelMapper;
import com.kryukow.HotelReservationService.model.Hotel;
import com.kryukow.HotelReservationService.repository.HotelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
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
    void create_shouldSaveHotel_andReturnDto() {
        HotelCreateUpdateRequest request = new HotelCreateUpdateRequest();
        request.setName("Hotel Test");

        Hotel hotel = new Hotel();
        Hotel savedHotel = new Hotel();
        savedHotel.setId(1L);

        HotelResponseDto responseDto = new HotelResponseDto();

        when(mapper.toEntity(request)).thenReturn(hotel);
        when(repository.save(hotel)).thenReturn(savedHotel);
        when(mapper.toResponseDto(savedHotel)).thenReturn(responseDto);

        HotelResponseDto result = service.create(request);

        assertNotNull(result);
        verify(repository).save(hotel);
        verify(mapper).toResponseDto(savedHotel);
        assertEquals(0.0, hotel.getRating());
        assertEquals(0, hotel.getCountOfRatings());
    }

    @Test
    void getById_shouldReturnHotel_whenExists() {
        Long id = 1L;
        Hotel hotel = new Hotel();
        HotelResponseDto dto = new HotelResponseDto();

        when(repository.findById(id)).thenReturn(Optional.of(hotel));
        when(mapper.toResponseDto(hotel)).thenReturn(dto);

        HotelResponseDto result = service.getById(id);

        assertNotNull(result);
    }

    @Test
    void getById_shouldThrow_whenNotFound() {
        Long id = 1L;

        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.getById(id));
    }

    @Test
    void getAll_shouldReturnPage() {
        Hotel hotel = new Hotel();
        HotelResponseDto dto = new HotelResponseDto();

        Page<Hotel> page = new PageImpl<>(List.of(hotel));

        when(repository.findAll(any(Pageable.class))).thenReturn(page);
        when(mapper.toResponseDto(hotel)).thenReturn(dto);

        PageResponseDto<HotelResponseDto> result = service.getAll(0, 10);

        assertEquals(1, result.getContent().size());
    }

    @Test
    void getAll_shouldThrow_whenPageInvalid() {
        assertThrows(IllegalArgumentException.class, () -> service.getAll(-1, 10));
    }

    @Test
    void getAll_shouldThrow_whenSizeInvalid() {
        assertThrows(IllegalArgumentException.class, () -> service.getAll(0, 0));
        assertThrows(IllegalArgumentException.class, () -> service.getAll(0, 101));
    }

    @Test
    void update_shouldUpdateHotel() {
        Long id = 1L;

        Hotel existing = new Hotel();
        HotelCreateUpdateRequest request = new HotelCreateUpdateRequest();
        request.setName("Updated");

        HotelResponseDto dto = new HotelResponseDto();

        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);
        when(mapper.toResponseDto(existing)).thenReturn(dto);

        HotelResponseDto result = service.update(id, request);

        assertNotNull(result);
        assertEquals("Updated", existing.getName());
    }

    @Test
    void update_shouldThrow_whenNotFound() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> service.update(1L, new HotelCreateUpdateRequest()));
    }

    @Test
    void delete_shouldDelete_whenExists() {
        Long id = 1L;

        when(repository.existsById(id)).thenReturn(true);

        service.delete(id);

        verify(repository).deleteById(id);
    }

    @Test
    void delete_shouldThrow_whenNotExists() {
        when(repository.existsById(1L)).thenReturn(false);

        assertThrows(EntityNotFoundException.class, () -> service.delete(1L));
    }
}
