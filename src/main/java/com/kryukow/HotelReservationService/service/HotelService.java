package com.kryukow.HotelReservationService.service;

import com.kryukow.HotelReservationService.dto.HotelCreateUpdateRequest;
import com.kryukow.HotelReservationService.dto.HotelResponseDto;
import com.kryukow.HotelReservationService.dto.PageResponseDto;
import com.kryukow.HotelReservationService.exception.EntityNotFoundException;
import com.kryukow.HotelReservationService.mapper.HotelMapper;
import com.kryukow.HotelReservationService.model.Hotel;
import com.kryukow.HotelReservationService.repository.HotelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class HotelService {

    private final HotelRepository repository;
    private final HotelMapper mapper;

    @Transactional
    public HotelResponseDto create(HotelCreateUpdateRequest hotelDto) {
        log.info("Создание отеля: {}", hotelDto.getName());



        Hotel hotel = mapper.toEntity(hotelDto);
        hotel.setRating(0.0);
        hotel.setCountOfRatings(0);

        Hotel saved = repository.save(hotel);
        log.info("Отель создан с ID: {}", saved.getId());

        return mapper.toResponseDto(saved);
    }

    @Transactional(readOnly = true)
    public HotelResponseDto getById(Long id) {
        log.info("Получить отель по ID {}", id);
        Hotel hotel = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Hotel", id));
        return mapper.toResponseDto(hotel);
    }

    @Transactional(readOnly = true)
    public PageResponseDto<HotelResponseDto> getAll(int page, int size) {
        log.info("Получить все отели: page={}, size={}", page, size);

        // Валидация параметров пагинации
        if (page < 0) {
            throw new IllegalArgumentException("Page index must not be less than zero");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("Page size must be between 1 and 100");
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<Hotel> hotelPage = repository.findAll(pageable);

        var hotelDtos = hotelPage.getContent()
                .stream()
                .map(mapper::toResponseDto)
                .toList();

        return new PageResponseDto<>(hotelDtos, hotelPage);
    }

    @Transactional
    public HotelResponseDto update(Long id, HotelCreateUpdateRequest request) {
        log.info("Обновить отель с ID: {}", id);

        Hotel hotel = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Hotel", id));

        hotel.setName(request.getName());
        hotel.setTitle(request.getTitle());
        hotel.setCity(request.getCity());
        hotel.setAddress(request.getAddress());
        hotel.setDistanceFromCityCenter(request.getDistanceFromCityCenter());

        Hotel updated = repository.save(hotel);
        log.info("Отель с ID {} обновлён", id);

        return mapper.toResponseDto(updated);
    }

    @Transactional
    public void delete(Long id) {
        log.info("Удалить отель с ID: {}", id);

        if (!repository.existsById(id)) {
            throw new EntityNotFoundException("Hotel", id);
        }

        repository.deleteById(id);
        log.info("Отель с ID {} удалён", id);
    }
}