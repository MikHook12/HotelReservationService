package com.kryukow.HotelReservationService.service;

import com.kryukow.HotelReservationService.dto.hotel.HotelCreateUpdateRequest;
import com.kryukow.HotelReservationService.dto.hotel.HotelResponseDto;
import com.kryukow.HotelReservationService.dto.PageResponseDto;
import com.kryukow.HotelReservationService.exception.EntityNotFoundException;
import com.kryukow.HotelReservationService.exception.ResourceConflictException;
import com.kryukow.HotelReservationService.mapper.HotelMapper;
import com.kryukow.HotelReservationService.model.Hotel;
import com.kryukow.HotelReservationService.repository.HotelRepository;
import com.kryukow.HotelReservationService.specification.HotelSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.math.BigDecimal;
import java.math.RoundingMode;
import com.kryukow.HotelReservationService.model.Room;
import com.kryukow.HotelReservationService.repository.RoomAvailabilityRepository;
import com.kryukow.HotelReservationService.exception.BookingHistoryConflictException;

@Service
@RequiredArgsConstructor
@Slf4j
public class HotelService {

    private final HotelRepository repository;
    private final HotelMapper mapper;
    private final RoomAvailabilityRepository availabilityRepository;
    private final RoomAvailabilityService availabilityService;

    @Transactional
    public HotelResponseDto create(HotelCreateUpdateRequest hotelDto) {
        log.info("Создание отеля: {}", hotelDto.getName());

        if (repository.existsByName(hotelDto.getName())) {
            throw new ResourceConflictException("Отель с названием '" + hotelDto.getName() + "' уже существует");
        }

        Hotel hotel = mapper.toEntity(hotelDto);
        hotel.setRating(0.0);
        hotel.setCountOfRatings(0);

        Hotel saved = repository.save(hotel);
        log.info("Отель создан с ID: {}", saved.getId());

        return responses(List.of(saved)).get(0);
    }

    @Transactional(readOnly = true)
    public HotelResponseDto getById(Long id) {
        log.info("Получить отель по ID {}", id);
        Hotel hotel = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Hotel", id));
        return responses(List.of(hotel)).get(0);
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

        var hotelDtos = responses(hotelPage.getContent());

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

        return responses(List.of(updated)).get(0);
    }

    @Transactional
    public void delete(Long id) {
        log.info("Удалить отель с ID: {}", id);

        Hotel hotel = repository.lockById(id)
                .orElseThrow(() -> new EntityNotFoundException("Hotel", id));
        List<Room> rooms = availabilityRepository.lockByHotelId(id);
        if (!rooms.isEmpty() && availabilityRepository.hasBookings(rooms.stream().map(Room::getId).toList())) {
            throw new BookingHistoryConflictException("hotel");
        }
        availabilityRepository.deleteUnbookedRooms(id);
        repository.delete(hotel);
    }

    @Transactional
    public void changeRating(Long id, Double newMark) {
        if (newMark == null || !Double.isFinite(newMark) || newMark < 1 || newMark > 5)
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        BigDecimal mark;
        try {
            mark = BigDecimal.valueOf(newMark).setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Rating supports at most 4 decimal places");
        }
        Hotel hotel = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Hotel", id));
        BigDecimal sum = hotel.getRatingSum().add(mark);
        int count = Math.addExact(hotel.getCountOfRatings(), 1);
        hotel.setRatingSum(sum);
        hotel.setCountOfRatings(count);
        hotel.setRating(sum.divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP).doubleValue());
        repository.save(hotel);
    }

    private List<HotelResponseDto> responses(List<Hotel> hotels) {
        if (hotels.isEmpty()) return List.of();
        // Fetch collections after pagination: no collection-fetch pagination or per-hotel SELECTs.
        repository.fetchRooms(hotels.stream().map(Hotel::getId).toList());
        List<HotelResponseDto> dtos = hotels.stream().map(mapper::toResponseDto).toList();
        availabilityService.populate(dtos.stream().flatMap(dto -> dto.getRoomsList().stream()).toList());
        return dtos;
    }

    @Transactional(readOnly = true)
    public PageResponseDto<HotelResponseDto> getAllWithFilters(
            Long id, String name, String title, String city, String address,
            Double distanceMin, Double distanceMax,
            Double ratingMin, Double ratingMax,
            Integer ratingCountMin, Integer ratingCountMax,
            int page, int size) {

        log.info("Поиск отелей с фильтрацией: id={}, name={}, title={}, city={}, address={}, distanceMin={}, distanceMax={}, ratingMin={}, ratingMax={}, ratingCountMin={}, ratingCountMax={}, page={}, size={}",
                id, name, title, city, address, distanceMin, distanceMax, ratingMin, ratingMax, ratingCountMin, ratingCountMax, page, size);

        if (page < 0) throw new IllegalArgumentException("Page must be >= 0");
        if (size < 1 || size > 100) throw new IllegalArgumentException("Size must be between 1 and 100");

        Pageable pageable = PageRequest.of(page, size);

        Specification<Hotel> spec = HotelSpecification.filterBy(
                id, name, title, city, address,
                distanceMin, distanceMax,
                ratingMin, ratingMax,
                ratingCountMin, ratingCountMax
        );

        Page<Hotel> hotelPage = repository.findAll(spec, pageable);
        List<HotelResponseDto> dtos = responses(hotelPage.getContent());

        return new PageResponseDto<>(dtos, hotelPage);
    }
}
