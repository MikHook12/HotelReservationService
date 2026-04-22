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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private BookingService bookingService;

    private BookingCreateRequestDto createValidRequest() {
        BookingCreateRequestDto request = new BookingCreateRequestDto();
        request.setRoomId(1L);
        request.setUserId(1L);
        request.setCheckInDate(LocalDate.of(2025, 6, 1));
        request.setCheckOutDate(LocalDate.of(2025, 6, 5));
        return request;
    }

    private Room createRoom(Long id) {
        Room room = new Room();
        room.setId(id);
        return room;
    }

    private User createUser(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private Booking createBooking(Long id, Long roomId, Long userId,
                                  LocalDate checkIn, LocalDate checkOut) {
        Booking booking = new Booking();
        booking.setId(id);
        booking.setRoom(createRoom(roomId));
        booking.setUser(createUser(userId));
        booking.setCheckInDate(checkIn);
        booking.setCheckOutDate(checkOut);
        return booking;
    }

    // ========== CREATE ==========

    @Test
    void create_ShouldSaveAndReturnBooking() {
        // given
        BookingCreateRequestDto request = createValidRequest();
        Room room = createRoom(1L);
        User user = createUser(1L);
        Booking booking = createBooking(1L, 1L, 1L,
                request.getCheckInDate(), request.getCheckOutDate());

        when(roomRepository.findById(1L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(bookingRepository.existsOverlappingBooking(eq(1L),
                eq(request.getCheckInDate()), eq(request.getCheckOutDate())))
                .thenReturn(false);
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);

        // when
        BookingResponseDto result = bookingService.create(request);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getRoomId()).isEqualTo(1L);
        assertThat(result.getCheckInDate()).isEqualTo(request.getCheckInDate());
        assertThat(result.getCheckOutDate()).isEqualTo(request.getCheckOutDate());

        verify(bookingRepository).save(any(Booking.class));
    }

    @Test
    void create_WhenRoomNotFound_ShouldThrowException() {
        // given
        BookingCreateRequestDto request = createValidRequest();
        when(roomRepository.findById(1L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> bookingService.create(request))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Room not found with id: 1");
    }

    @Test
    void create_WhenUserNotFound_ShouldThrowException() {
        // given
        BookingCreateRequestDto request = createValidRequest();
        when(roomRepository.findById(1L)).thenReturn(Optional.of(createRoom(1L)));
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> bookingService.create(request))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("User not found with id: 1");
    }

    @Test
    void create_WhenDatesOverlap_ShouldThrowException() {
        // given
        BookingCreateRequestDto request = createValidRequest();
        when(roomRepository.findById(1L)).thenReturn(Optional.of(createRoom(1L)));
        when(userRepository.findById(1L)).thenReturn(Optional.of(createUser(1L)));
        when(bookingRepository.existsOverlappingBooking(eq(1L),
                eq(request.getCheckInDate()), eq(request.getCheckOutDate())))
                .thenReturn(true);

        // when & then
        assertThatThrownBy(() -> bookingService.create(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Комната уже забронирована на выбранные даты");
    }

    @Test
    void create_WhenCheckInAfterCheckOut_ShouldThrowException() {
        // given
        BookingCreateRequestDto request = createValidRequest();
        request.setCheckInDate(LocalDate.of(2025, 6, 10));
        request.setCheckOutDate(LocalDate.of(2025, 6, 5));

        // when & then
        assertThatThrownBy(() -> bookingService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Дата заезда должна быть раньше даты выезда");
    }

    @Test
    void create_WhenCheckInOrCheckOutNull_ShouldThrowException() {
        // given
        BookingCreateRequestDto request = createValidRequest();
        request.setCheckInDate(null);

        // when & then
        assertThatThrownBy(() -> bookingService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Даты не должны быть null");
    }

    // ========== GET BY ID ==========

    @Test
    void getById_WhenBookingExists_ShouldReturnBooking() {
        // given
        Long bookingId = 1L;
        Booking booking = createBooking(bookingId, 1L, 1L,
                LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 5));
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));

        // when
        BookingResponseDto result = bookingService.getById(bookingId);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(bookingId);
    }

    @Test
    void getById_WhenBookingNotFound_ShouldThrowException() {
        // given
        Long bookingId = 999L;
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> bookingService.getById(bookingId))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Booking not found with id: 999");
    }

    // ========== GET ALL ==========

    @Test
    void getAll_ShouldReturnPage() {
        // given
        int page = 0;
        int size = 10;
        Pageable pageable = PageRequest.of(page, size);

        Booking booking1 = createBooking(1L, 1L, 1L,
                LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 5));
        Booking booking2 = createBooking(2L, 2L, 2L,
                LocalDate.of(2025, 7, 1), LocalDate.of(2025, 7, 5));
        Page<Booking> bookingPage = new PageImpl<>(List.of(booking1, booking2), pageable, 2);

        when(bookingRepository.findAll(pageable)).thenReturn(bookingPage);

        // when
        Page<BookingResponseDto> result = bookingService.getAll(page, size);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void getAll_ShouldReturnEmptyPage() {
        // given
        int page = 0;
        int size = 10;
        Pageable pageable = PageRequest.of(page, size);
        Page<Booking> emptyPage = new PageImpl<>(List.of(), pageable, 0);

        when(bookingRepository.findAll(pageable)).thenReturn(emptyPage);

        // when
        Page<BookingResponseDto> result = bookingService.getAll(page, size);

        // then
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(0);
    }
}