package com.kryukow.HotelReservationService.service;


import com.kryukow.HotelReservationService.dto.HotelCreateUpdateRequest;
import com.kryukow.HotelReservationService.dto.HotelResponseDto;
import com.kryukow.HotelReservationService.dto.PageResponseDto;
import com.kryukow.HotelReservationService.mapper.HotelMapper;
import com.kryukow.HotelReservationService.model.Hotel;
import com.kryukow.HotelReservationService.repository.HotelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class HotelService {

    private final HotelRepository repository;
    private final HotelMapper mapper;

    public HotelResponseDto create(HotelCreateUpdateRequest hotelDto){
        log.info("Создание отеля");

        Hotel hotel = mapper.toEntity(hotelDto);
        hotel.setRating(0.0);
        hotel.setCountOfRatings(0);

        Hotel saved = repository.save(hotel);

        return mapper.toResponseDto(saved);
    }

    public HotelResponseDto getById(Long id){
        log.info("Получить отель по ID {}", id);
        Hotel hotel = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Hotel not found with id: " + id));
        return mapper.toResponseDto(hotel);
    }

    public PageResponseDto<HotelResponseDto> getAll(int page, int size){
        log.info("Получить все отели");

        Pageable pageable = PageRequest.of(page, size);
        Page<Hotel> hotelPage = repository.findAll(pageable);

        var hotelDtos = hotelPage.getContent()
                .stream()
                .map(mapper::toResponseDto)
                .toList();

        return new PageResponseDto<>(hotelDtos, hotelPage);


    }

    public HotelResponseDto update(Long id, HotelCreateUpdateRequest request) {
        log.info("Обновить отель с ID: {}", id);

        Hotel hotel = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Hotel not found with id: " + id));

        hotel.setName(request.getName());
        hotel.setTitle(request.getTitle());
        hotel.setCity(request.getCity());
        hotel.setAddress(request.getAddress());
        hotel.setDistanceFromCityCenter(request.getDistanceFromCityCenter());

        Hotel updated = repository.save(hotel);

        return mapper.toResponseDto(updated);
    }

    public void delete(Long id) {
        log.info("Удалить отель с ID: {}", id);

        Hotel hotel = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Hotel not found with id: " + id));

        repository.delete(hotel);
    }




/// Добавить обработчик ошибок и проверки

}
