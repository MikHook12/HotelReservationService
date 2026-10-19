package com.kryukow.HotelReservationService.service;

import com.kryukow.HotelReservationService.dto.booking.BookingCreateRequestDto;
import com.kryukow.HotelReservationService.dto.booking.BookingResponseDto;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import com.kryukow.HotelReservationService.security.CurrentUser;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final BookingRepository bookingRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final DomainEventPublisher eventPublisher;
    private final CurrentUser currentUser;



    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public BookingResponseDto create(BookingCreateRequestDto request) {
        Long userId = currentUser.id();
        validateDates(request.getCheckInDate(), request.getCheckOutDate());
        if (request.getRoomId() == null || request.getRoomId() < 1) {
            throw new IllegalArgumentException("Room ID must be positive");
        }
        // Legacy clients may still send userId, but it can never select another owner.
        if (request.getUserId() != null && !request.getUserId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException("Cannot book for another user");
        }
        // Serialize all writers for this room, including when no booking exists yet.
        Room room = roomRepository.findByIdForUpdate(request.getRoomId())
                .orElseThrow(() -> new EntityNotFoundException("Room", request.getRoomId()));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", userId));

        boolean conflict = bookingRepository.existsOverlappingBooking(
                room.getId(),
                request.getCheckInDate(),
                request.getCheckOutDate()
        );

        if (conflict) {
            throw new com.kryukow.HotelReservationService.exception.ResourceConflictException("Комната уже забронирована на выбранные даты");
        }

        Booking booking = new Booking();
        booking.setRoom(room);
        booking.setUser(user);
        booking.setCheckInDate(request.getCheckInDate());
        booking.setCheckOutDate(request.getCheckOutDate());

        Booking saved = bookingRepository.save(booking);

        log.info("Бронирование создано с ID: {}", saved.getId());
        eventPublisher.publishBookingCreated(user.getId(), room.getId(),
                saved.getCheckInDate(), saved.getCheckOutDate());

        return toDto(saved);
    }


    @Transactional(readOnly = true)
    public BookingResponseDto getById(Long id) {
        if (id == null || id < 1) {
            throw new IllegalArgumentException("Booking ID must be positive");
        }
        log.info("Получить бронирование по ID {}", id);

        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Booking", id));

        currentUser.requireOwnerOrAdmin(booking.getUser().getId());
        return toDto(booking);
    }


    @Transactional(readOnly = true)
    public Page<BookingResponseDto> getAll(int page, int size) {
        log.info("Получить все бронирования: page={}, size={}", page, size);

        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be non-negative and size between 1 and 100");
        }
        PageRequest pageable = PageRequest.of(page, size, org.springframework.data.domain.Sort.by("id"));
        Page<Booking> bookings = currentUser.isAdmin()
                ? bookingRepository.findAll(pageable)
                : bookingRepository.findAllByUserId(currentUser.id(), pageable);

        return bookings.map(this::toDto);
    }


    private void validateDates(java.time.LocalDate checkIn, java.time.LocalDate checkOut) {
        if (checkIn == null || checkOut == null) {
            throw new IllegalArgumentException("Даты не должны быть null");
        }

        if (!checkIn.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Дата заезда должна быть в будущем");
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
