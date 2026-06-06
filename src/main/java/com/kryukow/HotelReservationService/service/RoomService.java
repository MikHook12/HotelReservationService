package com.kryukow.HotelReservationService.service;

import com.kryukow.HotelReservationService.dto.PageResponseDto;
import com.kryukow.HotelReservationService.dto.room.RoomCreateUpdateRequest;
import com.kryukow.HotelReservationService.dto.room.RoomResponseDto;
import com.kryukow.HotelReservationService.exception.EntityNotFoundException;
import com.kryukow.HotelReservationService.mapper.RoomMapper;
import com.kryukow.HotelReservationService.model.Hotel;
import com.kryukow.HotelReservationService.model.Room;
import com.kryukow.HotelReservationService.repository.HotelRepository;
import com.kryukow.HotelReservationService.repository.RoomRepository;
import com.kryukow.HotelReservationService.specification.RoomSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoomService {

    private final RoomRepository roomRepository;
    private final HotelRepository hotelRepository;
    private final RoomMapper roomMapper;


    @Transactional
    public RoomResponseDto create(RoomCreateUpdateRequest request) {
        log.info("Создание комнаты {}", request.getName());

        Hotel hotel = hotelRepository.findById(request.getHotelId())
                .orElseThrow(() -> new EntityNotFoundException("Hotel", request.getHotelId()));

        if (roomRepository.existsByHotelIdAndNumber(request.getHotelId(), request.getNumber())) {
            throw new IllegalArgumentException("Комната с номером " + request.getNumber() + " уже существует в этом отеле");
        }

        Room room = roomMapper.toEntity(request);
        room.setHotel(hotel);

        Room saved = roomRepository.save(room);

        RoomResponseDto dto = roomMapper.toResponseDto(saved);
        dto.setUnavailableDates(getUnavailableDates(saved));

        return dto;
    }

    @Transactional(readOnly = true)
    public RoomResponseDto getById(Long id) {
        log.info("Получить комнату {}", id);

        Room room = roomRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Room", id));

        RoomResponseDto dto = roomMapper.toResponseDto(room);
        dto.setUnavailableDates(getUnavailableDates(room));

        return dto;
    }

    @Transactional(readOnly = true)
    public PageResponseDto<RoomResponseDto> getAll(int page, int size) {
        log.info("Получить комнаты page={}, size={}", page, size);

        if (page < 0) {
            throw new IllegalArgumentException("Page must be >= 0");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("Size must be between 1 and 100");
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<Room> roomPage = roomRepository.findAll(pageable);

        List<RoomResponseDto> content = roomPage.getContent()
                .stream()
                .map(room -> {
                    RoomResponseDto dto = roomMapper.toResponseDto(room);
                    dto.setUnavailableDates(getUnavailableDates(room));
                    return dto;
                })
                .toList();

        return new PageResponseDto<>(content, roomPage);
    }

    @Transactional
    public RoomResponseDto update(Long id, RoomCreateUpdateRequest request) {
        log.info("Обновление комнаты {}", id);

        Room room = roomRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Room", id));

        Hotel hotel = hotelRepository.findById(request.getHotelId())
                .orElseThrow(() -> new EntityNotFoundException("Hotel", request.getHotelId()));

        // ⚠️ MapStruct не обновляет существующий объект — делаем руками
        room.setName(request.getName());
        room.setDescription(request.getDescription());
        room.setNumber(request.getNumber());
        room.setPrice(request.getPrice());
        room.setCapacity(request.getCapacity());
        room.setHotel(hotel);

        Room updated = roomRepository.save(room);

        RoomResponseDto dto = roomMapper.toResponseDto(updated);
        dto.setUnavailableDates(getUnavailableDates(updated));

        return dto;
    }

    @Transactional
    public void delete(Long id) {
        log.info("Удаление комнаты {}", id);

        if (!roomRepository.existsById(id)) {
            throw new EntityNotFoundException("Room", id);
        }

        roomRepository.deleteById(id);
    }


    private List<LocalDate> getUnavailableDates(Room room) {
        return room.getBookings().stream()
                .flatMap(booking -> booking.getCheckInDate()
                        .datesUntil(booking.getCheckOutDate()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponseDto<RoomResponseDto> getAllWithFilters(
            String id, String name,
            Double priceMin, Double priceMax,
            Integer capacity, Long hotelId,
            LocalDate checkInDate, LocalDate checkOutDate,
            int page, int size) {

        log.info("Поиск комнат с фильтрацией: id={}, name={}, priceMin={}, priceMax={}, capacity={}, hotelId={}, checkIn={}, checkOut={}, page={}, size={}",
                id, name, priceMin, priceMax, capacity, hotelId, checkInDate, checkOutDate, page, size);

        if (page < 0) throw new IllegalArgumentException("Page must be >= 0");
        if (size < 1 || size > 100) throw new IllegalArgumentException("Size must be between 1 and 100");

        Pageable pageable = PageRequest.of(page, size);

        Specification<Room> spec = RoomSpecification.filterBy(
                id, name, priceMin, priceMax, capacity, hotelId, checkInDate, checkOutDate
        );

        Page<Room> roomPage = roomRepository.findAll(spec, pageable);
        List<RoomResponseDto> dtos = roomPage.getContent().stream()
                .map(room -> {
                    RoomResponseDto dto = roomMapper.toResponseDto(room);

                    return dto;
                })
                .toList();

        return new PageResponseDto<>(dtos, roomPage);
    }

}
