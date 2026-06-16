package com.kryukow.HotelReservationService.service;

import com.kryukow.HotelReservationService.dto.booking.BookingCreateRequestDto;
import com.kryukow.HotelReservationService.dto.booking.BookingResponseDto;
import com.kryukow.HotelReservationService.dto.event.BookingCreatedEvent;
import com.kryukow.HotelReservationService.exception.EntityNotFoundException;
import com.kryukow.HotelReservationService.model.Booking;
import com.kryukow.HotelReservationService.model.Room;
import com.kryukow.HotelReservationService.model.User;
import com.kryukow.HotelReservationService.repository.BookingRepository;
import com.kryukow.HotelReservationService.repository.RoomRepository;
import com.kryukow.HotelReservationService.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final BookingRepository bookingRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private KafkaTemplate<String, Object> kafkaTemplate;



    @Transactional
    public BookingResponseDto create(BookingCreateRequestDto request) {
        log.info("Создание бронирования: roomId={}, userId={}",
                request.getRoomId(), request.getUserId());

        validateDates(request.getCheckInDate(), request.getCheckOutDate());

        Room room = roomRepository.findById(request.getRoomId())
                .orElseThrow(() -> new EntityNotFoundException("Room", request.getRoomId()));

        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new EntityNotFoundException("User", request.getUserId()));

        boolean conflict = bookingRepository.existsOverlappingBooking(
                room.getId(),
                request.getCheckInDate(),
                request.getCheckOutDate()
        );

        if (conflict) {
            throw new IllegalStateException("Комната уже забронирована на выбранные даты");
        }

        Booking booking = new Booking();
        booking.setRoom(room);
        booking.setUser(user);
        booking.setCheckInDate(request.getCheckInDate());
        booking.setCheckOutDate(request.getCheckOutDate());

        Booking saved = bookingRepository.save(booking);

        log.info("Бронирование создано с ID: {}", saved.getId());
        kafkaTemplate.send("booking-events", new BookingCreatedEvent(
                booking.getUser().getId(),
                booking.getRoom().getId(),
                booking.getCheckInDate(),
                booking.getCheckOutDate(),
                LocalDateTime.now()
        ));

        return toDto(saved);
    }


    @Transactional(readOnly = true)
    public BookingResponseDto getById(Long id) {
        log.info("Получить бронирование по ID {}", id);

        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Booking", id));

        return toDto(booking);
    }


    @Transactional(readOnly = true)
    public Page<BookingResponseDto> getAll(int page, int size) {
        log.info("Получить все бронирования: page={}, size={}", page, size);

        Page<Booking> bookings = bookingRepository.findAll(PageRequest.of(page, size));

        return bookings.map(this::toDto);
    }


    private void validateDates(java.time.LocalDate checkIn, java.time.LocalDate checkOut) {
        if (checkIn == null || checkOut == null) {
            throw new IllegalArgumentException("Даты не должны быть null");
        }

        if (!checkIn.isBefore(checkOut)) {
            throw new IllegalArgumentException("Дата заезда должна быть раньше даты выезда");
        }
    }


    private BookingResponseDto toDto(Booking booking) {
        BookingResponseDto dto = new BookingResponseDto();
        dto.setId(booking.getId());
        dto.setUserId(booking.getUser().getId());
        dto.setRoomId(booking.getRoom().getId());
        dto.setCheckInDate(booking.getCheckInDate());
        dto.setCheckOutDate(booking.getCheckOutDate());
        return dto;
    }
}
