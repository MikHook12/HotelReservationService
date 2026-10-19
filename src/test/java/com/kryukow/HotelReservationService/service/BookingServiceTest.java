package com.kryukow.HotelReservationService.service;

import com.kryukow.HotelReservationService.dto.booking.BookingCreateRequestDto;
import com.kryukow.HotelReservationService.exception.EntityNotFoundException;
import com.kryukow.HotelReservationService.model.*;
import com.kryukow.HotelReservationService.repository.*;
import com.kryukow.HotelReservationService.security.CurrentUser;
import com.kryukow.HotelReservationService.security.CustomUserDetails;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {
    @Mock BookingRepository bookingRepository;
    @Mock RoomRepository roomRepository;
    @Mock UserRepository userRepository;
    @Mock DomainEventPublisher eventPublisher;
    BookingService service;
    User user;

    @BeforeEach void setUp() {
        user = new User();
        user.setId(7L);
        user.setRole(Role.USER);
        authenticate(user);
        service = new BookingService(bookingRepository, roomRepository, userRepository, eventPublisher, new CurrentUser());
    }

    @AfterEach void cleanUp() { SecurityContextHolder.clearContext(); }

    private void authenticate(User user) {
        CustomUserDetails details = new CustomUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private BookingCreateRequestDto request() {
        BookingCreateRequestDto request = new BookingCreateRequestDto();
        request.setRoomId(1L);
        request.setCheckInDate(LocalDate.now().plusDays(5));
        request.setCheckOutDate(LocalDate.now().plusDays(8));
        return request;
    }

    private Room room() { Room room = new Room(); room.setId(1L); return room; }
    private Booking booking(User owner) {
        Booking booking = new Booking(); booking.setId(11L); booking.setUser(owner); booking.setRoom(room());
        booking.setCheckInDate(request().getCheckInDate()); booking.setCheckOutDate(request().getCheckOutDate());
        return booking;
    }

    @Test void createsForAuthenticatedOwnerAndLocksBeforeCheckingOverlapAndPublishing() {
        var request = request();
        when(roomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(room()));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(bookingRepository.save(any())).thenAnswer(invocation -> {
            Booking booking = invocation.getArgument(0); booking.setId(11L); return booking;
        });
        var result = service.create(request);
        assertThat(result.getUserId()).isEqualTo(7L);
        assertThat(result.getId()).isEqualTo(11L);
        InOrder order = inOrder(roomRepository, bookingRepository, eventPublisher);
        order.verify(roomRepository).findByIdForUpdate(1L);
        order.verify(bookingRepository).existsOverlappingBooking(1L, request.getCheckInDate(), request.getCheckOutDate());
        order.verify(bookingRepository).save(argThat(booking -> booking.getUser().getId().equals(7L)));
        order.verify(eventPublisher).publishBookingCreated(7L, 1L, request.getCheckInDate(), request.getCheckOutDate());
    }

    @Test void rejectsForgedOwnerBeforeAnyDatabaseOrEventWrite() {
        var request = request(); request.setUserId(99L);
        assertThatThrownBy(() -> service.create(request)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(roomRepository, userRepository, bookingRepository, eventPublisher);
    }

    @Test void adminCannotCreateOnBehalfOfAnotherUserEither() {
        user.setRole(Role.ADMIN); authenticate(user);
        var request = request(); request.setUserId(99L);
        assertThatThrownBy(() -> service.create(request)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(roomRepository, userRepository, bookingRepository, eventPublisher);
    }

    @Test void overlappingBookingDoesNotSaveOrPublish() {
        var request = request();
        when(roomRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(room()));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(bookingRepository.existsOverlappingBooking(anyLong(), any(), any())).thenReturn(true);
        assertThatThrownBy(() -> service.create(request)).isInstanceOf(IllegalStateException.class);
        verify(bookingRepository, never()).save(any()); verifyNoInteractions(eventPublisher);
    }

    @Test void missingRoomIsDomainNotFound() {
        when(roomRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(request())).isInstanceOf(EntityNotFoundException.class);
        verifyNoInteractions(userRepository, bookingRepository, eventPublisher);
    }

    @Test void rejectsNullPastSameAndReversedDates() {
        for (LocalDate checkIn : new LocalDate[]{null, LocalDate.now().minusDays(1), LocalDate.now(),
                LocalDate.now().plusDays(8), LocalDate.now().plusDays(9)}) {
            var request = request(); request.setCheckInDate(checkIn);
            assertThatThrownBy(() -> service.create(request)).isInstanceOf(IllegalArgumentException.class);
        }
        var request = request(); request.setCheckOutDate(null);
        assertThatThrownBy(() -> service.create(request)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(roomRepository, userRepository, bookingRepository, eventPublisher);
    }

    @Test void userListIsFilteredInDatabase() {
        var pageable = PageRequest.of(0, 10, Sort.by("id"));
        when(bookingRepository.findAllByUserId(7L, pageable)).thenReturn(Page.empty(pageable));
        assertThat(service.getAll(0, 10)).isEmpty();
        verify(bookingRepository, never()).findAll(any(Pageable.class));
    }

    @Test void adminCanListAllAndReadAnotherUsersBooking() {
        user.setRole(Role.ADMIN); authenticate(user);
        var pageable = PageRequest.of(0, 10, Sort.by("id"));
        when(bookingRepository.findAll(pageable)).thenReturn(Page.empty(pageable));
        assertThat(service.getAll(0, 10)).isEmpty();
        User owner = new User(); owner.setId(99L);
        when(bookingRepository.findById(11L)).thenReturn(Optional.of(booking(owner)));
        assertThat(service.getById(11L).getUserId()).isEqualTo(99L);
    }

    @Test void userCannotReadAnotherUsersBooking() {
        User owner = new User(); owner.setId(99L);
        when(bookingRepository.findById(11L)).thenReturn(Optional.of(booking(owner)));
        assertThatThrownBy(() -> service.getById(11L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void userCanReadOwnBookingAndMissingBookingReturnsNotFound() {
        when(bookingRepository.findById(11L)).thenReturn(Optional.of(booking(user)));
        assertThat(service.getById(11L).getId()).isEqualTo(11L);
        assertThatThrownBy(() -> service.getById(99L)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test void paginationRejectsNegativePageAndUnboundedSize() {
        assertThatThrownBy(() -> service.getAll(-1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getAll(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getAll(0, 101)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(bookingRepository);
    }

    @Test void missingAuthenticationCannotCreateBooking() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.create(request()))
                .isInstanceOf(org.springframework.security.core.AuthenticationException.class);
        verifyNoInteractions(roomRepository, userRepository, bookingRepository, eventPublisher);
    }
}
