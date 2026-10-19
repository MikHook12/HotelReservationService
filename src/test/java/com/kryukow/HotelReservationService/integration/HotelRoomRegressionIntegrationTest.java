package com.kryukow.HotelReservationService.integration;

import com.kryukow.HotelReservationService.model.*;
import com.kryukow.HotelReservationService.service.HotelService;
import com.kryukow.HotelReservationService.service.RoomService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Transactional
class HotelRoomRegressionIntegrationTest extends BaseIntegrationTest {
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @Autowired HotelService hotels;
    @Autowired RoomService rooms;

    private Room fixture(boolean booked) {
        String unique = UUID.randomUUID().toString();
        Hotel hotel = new Hotel();
        hotel.setName(unique); hotel.setTitle("Title"); hotel.setCity("City");
        hotel.setAddress("Address"); hotel.setDistanceFromCityCenter(1.0);
        em.persist(hotel);
        Room room = new Room();
        room.setHotel(hotel); room.setName("Room"); room.setNumber(101);
        room.setDescription("Description"); room.setCapacity(2); room.setPrice(new BigDecimal("100.01"));
        em.persist(room);
        if (booked) {
            User user = new User();
            user.setName(unique); user.setPassword("hash"); user.setEmail(unique + "@example.com"); user.setRole(Role.USER);
            em.persist(user);
            Booking booking = new Booking();
            booking.setUser(user); booking.setRoom(room);
            booking.setCheckInDate(day()); booking.setCheckOutDate(day().plusDays(2));
            em.persist(booking);
        }
        em.flush(); em.clear();
        return room;
    }

    private LocalDate day() { return LocalDate.now(ZoneOffset.UTC).plusDays(10); }

    @Test
    void availabilityUsesHalfOpenBookingIntervalsAndExactPrice() {
        Room room = fixture(true);
        var overlap = rooms.getAllWithFilters(room.getId(), null, new BigDecimal("100.01"), new BigDecimal("100.01"),
                null, null, day().plusDays(1), day().plusDays(3), 0, 20);
        assertThat(overlap.getContent()).isEmpty();
        var adjacent = rooms.getAllWithFilters(room.getId(), null, null, null, null, null,
                day().plusDays(2), day().plusDays(3), 0, 20);
        assertThat(adjacent.getContent()).hasSize(1);
        assertThat(adjacent.getContent().get(0).getUnavailableDates()).containsExactly(day(), day().plusDays(1));
        var tooExpensive = rooms.getAllWithFilters(room.getId(), null, null, new BigDecimal("100.00"), null, null,
                null, null, 0, 20);
        assertThat(tooExpensive.getContent()).isEmpty();
    }

    @Test
    void hotelMappingIncludesRoomsAndAvailabilityWithBoundedQueryCount() {
        Room first = fixture(true);
        fixture(false);
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        var response = hotels.getAll(0, 100);
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(5);
        var hotel = response.getContent().stream().filter(dto -> dto.getId().equals(first.getHotel().getId())).findFirst().orElseThrow();
        assertThat(hotel.getRoomsList()).hasSize(1);
        assertThat(hotel.getRoomsList().get(0).getHotelId()).isEqualTo(hotel.getId());
        assertThat(hotel.getRoomsList().get(0).getUnavailableDates()).containsExactly(day(), day().plusDays(1));
    }

    @Test
    void deletingBookedRoomPreservesBookingHistory() {
        Room room = fixture(true);
        assertThatThrownBy(() -> rooms.delete(room.getId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("booking history");
        assertThat(em.createQuery("select count(b) from Booking b where b.room.id = :id", Long.class)
                .setParameter("id", room.getId()).getSingleResult()).isEqualTo(1);
    }

    @Test
    void deletingBookedHotelIsRejected() {
        Room room = fixture(true);
        assertThatThrownBy(() -> hotels.delete(room.getHotel().getId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("booking history");
    }

    @Test
    void unbookedHotelAndRoomsCanBeDeletedTogether() {
        Room room = fixture(false);
        Long hotelId = room.getHotel().getId();
        hotels.delete(hotelId);
        em.flush(); em.clear();
        assertThat(em.find(Hotel.class, hotelId)).isNull();
        assertThat(em.find(Room.class, room.getId())).isNull();
    }

    @Test
    void deletionWithInitializedRoomsPreservesUnrelatedManagedChanges() {
        Room first = fixture(false);
        Long hotelId = first.getHotel().getId();
        Room unrelated = fixture(false);
        Long unrelatedHotelId = unrelated.getHotel().getId();
        Hotel hotel = em.find(Hotel.class, hotelId);
        Room sibling = new Room();
        sibling.setHotel(hotel); sibling.setName("Sibling"); sibling.setNumber(102);
        sibling.setDescription("Description"); sibling.setCapacity(2); sibling.setPrice(new BigDecimal("120.00"));
        em.persist(sibling);
        em.flush(); em.clear();

        hotel = em.find(Hotel.class, hotelId);
        assertThat(hotel.getRooms()).hasSize(2); // Initialize the graph before the deletion command.
        Hotel otherHotel = em.find(Hotel.class, unrelatedHotelId);
        otherHotel.setTitle("Pending unrelated change");

        hotels.delete(hotelId);

        // The command must track removals rather than clear/detach the caller's context.
        assertThat(em.contains(otherHotel)).isTrue();
        em.flush(); em.clear();
        assertThat(em.find(Hotel.class, hotelId)).isNull();
        assertThat(em.find(Room.class, first.getId())).isNull();
        assertThat(em.find(Room.class, sibling.getId())).isNull();
        assertThat(em.find(Hotel.class, unrelatedHotelId).getTitle()).isEqualTo("Pending unrelated change");
        assertThat(em.find(Room.class, unrelated.getId())).isNotNull();
    }

    @Test
    void ratingAggregateSurvivesReloadAndRejectsStaleVersion() {
        Room room = fixture(false);
        Long id = room.getHotel().getId();
        Hotel stale = em.find(Hotel.class, id);
        em.detach(stale);
        for (double mark : new double[]{1, 2, 2, 5}) { hotels.changeRating(id, mark); em.flush(); em.clear(); }
        Hotel current = em.find(Hotel.class, id);
        assertThat(current.getRatingSum()).isEqualByComparingTo("10");
        assertThat(current.getCountOfRatings()).isEqualTo(4);
        assertThat(current.getRating()).isEqualTo(2.5);
        assertThat(current.getVersion()).isGreaterThan(stale.getVersion());
        stale.setName("Stale update");
        assertThatThrownBy(() -> em.merge(stale)).isInstanceOf(OptimisticLockException.class);
    }
}
