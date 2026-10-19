package com.kryukow.HotelReservationService.integration;

import com.kryukow.HotelReservationService.dto.booking.BookingCreateRequestDto;
import com.kryukow.HotelReservationService.model.*;
import com.kryukow.HotelReservationService.repository.*;
import com.kryukow.HotelReservationService.security.CustomUserDetails;
import com.kryukow.HotelReservationService.service.BookingService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class BookingConcurrencyIntegrationTest extends BaseIntegrationTest {
    @Autowired BookingService service;
    @Autowired com.kryukow.HotelReservationService.service.RoomService roomService;
    @Autowired com.kryukow.HotelReservationService.service.HotelService hotelService;
    @Autowired BookingRepository bookings;
    @Autowired RoomRepository rooms;
    @Autowired HotelRepository hotels;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactionManager;
    private User owner;
    private Long roomId;
    private Long hotelId;

    @BeforeEach void createFixtures() {
        String suffix = UUID.randomUUID().toString();
        User user = new User(); user.setName("booking-" + suffix);
        user.setEmail(suffix + "@example.com"); user.setPassword("unused-in-direct-service-test"); user.setRole(Role.USER);
        owner = users.saveAndFlush(user);
        Hotel hotel = new Hotel(); hotel.setName("booking-" + suffix); hotel.setTitle("Test");
        hotel.setCity("Test"); hotel.setAddress("Test"); hotel.setDistanceFromCityCenter(1.0);
        hotel = hotels.saveAndFlush(hotel);
        hotelId = hotel.getId();
        Room room = new Room(); room.setHotel(hotel); room.setName("Test"); room.setDescription("Test");
        room.setNumber(101); room.setPrice(BigDecimal.TEN); room.setCapacity(2);
        roomId = rooms.saveAndFlush(room).getId();
    }

    @AfterEach void cleanUpAuthentication() { SecurityContextHolder.clearContext(); }

    private void authenticate() {
        var principal = new CustomUserDetails(owner);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private BookingCreateRequestDto request(int start, int end) {
        var request = new BookingCreateRequestDto(); request.setRoomId(roomId);
        request.setCheckInDate(LocalDate.now().plusDays(start));
        request.setCheckOutDate(LocalDate.now().plusDays(end));
        return request;
    }

    @Test void competingTransactionsCannotDoubleBookInitiallyEmptyRoom() throws Exception {
        int writers = 6;
        var ready = new CountDownLatch(writers);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(writers);
        try {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < writers; i++) {
                results.add(executor.submit(() -> {
                    authenticate(); ready.countDown();
                    try {
                        if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start barrier timed out");
                        service.create(request(10, 15));
                        return true;
                    } catch (IllegalStateException conflict) {
                        assertThat(conflict).hasMessageContaining("Комната уже забронирована");
                        return false;
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int successful = 0;
            for (var result : results) if (result.get(30, TimeUnit.SECONDS)) successful++;
            assertThat(successful).isEqualTo(1);
            assertThat(bookings.findAllByRoomId(roomId, org.springframework.data.domain.Pageable.unpaged())
                    .getTotalElements()).isEqualTo(1);
        } finally {
            start.countDown(); executor.shutdownNow();
        }
    }

    @Test void halfOpenIntervalsPermitCheckoutAndCheckinOnSameDay() {
        authenticate();
        service.create(request(10, 15));
        service.create(request(5, 10));
        service.create(request(15, 20));
        assertThatThrownBy(() -> service.create(request(9, 11))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.create(request(10, 15))).isInstanceOf(IllegalStateException.class);
        assertThat(bookings.findAllByRoomId(roomId, org.springframework.data.domain.Pageable.unpaged())
                .getTotalElements()).isEqualTo(3);
    }

    @Test void roomDeletionWaitsForBookingCommitThenPreservesHistory() throws Exception {
        assertDeletionCannotRacePastBooking(() -> roomService.delete(roomId));
    }

    @Test void hotelDeletionWaitsForBookingCommitThenPreservesHistory() throws Exception {
        assertDeletionCannotRacePastBooking(() -> hotelService.delete(hotelId));
    }

    private void assertDeletionCannotRacePastBooking(Runnable deletion) throws Exception {
        var bookingWritten = new CountDownLatch(1);
        var permitCommit = new CountDownLatch(1);
        var deleteStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> creating = executor.submit(() -> {
                authenticate();
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        service.create(request(10, 15));
                        bookingWritten.countDown();
                        try {
                            if (!permitCommit.await(10, TimeUnit.SECONDS)) throw new AssertionError("Commit barrier timed out");
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt(); throw new AssertionError(ex);
                        }
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            assertThat(bookingWritten.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> deleting = executor.submit(() -> {
                deleteStarted.countDown();
                deletion.run();
            });
            assertThat(deleteStarted.await(10, TimeUnit.SECONDS)).isTrue();
            // The booking transaction still holds the Room lock and has not committed.
            assertThatThrownBy(() -> deleting.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            permitCommit.countDown();
            creating.get(30, TimeUnit.SECONDS);
            assertThatThrownBy(() -> deleting.get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(com.kryukow.HotelReservationService.exception.BookingHistoryConflictException.class);
            assertThat(rooms.existsById(roomId)).isTrue();
            assertThat(hotels.existsById(hotelId)).isTrue();
            assertThat(bookings.findAllByRoomId(roomId, org.springframework.data.domain.Pageable.unpaged())
                    .getTotalElements()).isEqualTo(1);
        } finally {
            permitCommit.countDown(); executor.shutdownNow();
        }
    }

    @Test void callerTransactionRollbackDoesNotLeaveBooking() {
        authenticate();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.create(request(10, 15));
            throw new IllegalStateException("Force rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Force rollback");
        assertThat(bookings.findAllByRoomId(roomId, org.springframework.data.domain.Pageable.unpaged())
                .getTotalElements()).isZero();
        // The lock is released on rollback and the same interval can be booked afterwards.
        assertThat(service.create(request(10, 15)).getId()).isNotNull();
    }
}
