package com.kryukow.HotelReservationService.web.v2.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kryukow.HotelReservationService.integration.BaseIntegrationTest;
import com.kryukow.HotelReservationService.model.*;
import com.kryukow.HotelReservationService.repository.RoomAvailabilityRepository;
import com.kryukow.HotelReservationService.repository.RoomRepository;
import com.kryukow.HotelReservationService.specification.RoomSpecification;
import com.kryukow.HotelReservationService.web.v2.catalog.model.CatalogPhoto;
import com.kryukow.HotelReservationService.web.v2.common.V2ApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses only the isolated PostgreSQL/Kafka/Mongo Testcontainers provided by BaseIntegrationTest. */
@Transactional(isolation = Isolation.REPEATABLE_READ)
class CatalogIntegrationTest extends BaseIntegrationTest {
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @Autowired CatalogService catalog;
    @Autowired RoomAvailabilityRepository availability;
    @Autowired RoomRepository legacyRooms;
    @Autowired ObjectMapper json;
    @MockBean Clock clock;
    final Instant now = Instant.parse("2026-10-20T12:00:00Z");

    @BeforeEach void fixedClock() {
        when(clock.instant()).thenReturn(now);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private CatalogQuery query(String... pairs) {
        var raw = new LinkedMultiValueMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) raw.add(pairs[i], pairs[i + 1]);
        return CatalogQuery.parse(raw, CatalogQuery.Scope.HOTELS);
    }

    private Hotel hotel(String zone) {
        String key = "test-" + UUID.randomUUID();
        Hotel hotel = new Hotel(); hotel.setName(key); hotel.setTitle("Title"); hotel.setAddress("Вымышленный адрес");
        hotel.setCity("Тестовый город"); hotel.setCityCode("test-city"); hotel.setDistanceFromCityCenter(1.0);
        hotel.setSlug(key); hotel.setTimeZone(zone); hotel.setDemo(true); hotel.setPublished(true);
        hotel.setDescription("Явно настроенный тестовый отель"); em.persist(hotel); return hotel;
    }

    private Room room(Hotel hotel, int number, String price, int capacity) {
        Room room = new Room(); room.setHotel(hotel); room.setNumber(number); room.setName("Комната " + number);
        room.setDescription("Тестовая комната"); room.setPrice(new BigDecimal(price)); room.setCapacity(capacity);
        room.setPublished(true); em.persist(room); return room;
    }

    private Booking booking(Room room, String status) {
        String unique = UUID.randomUUID().toString();
        User user = new User(); user.setName(unique); user.setEmail(unique + "@example.invalid"); user.setPassword("hash"); user.setRole(Role.USER); em.persist(user);
        Booking booking = new Booking(); booking.setRoom(room); booking.setUser(user);
        booking.setStatus(status); booking.setCheckInDate(LocalDate.of(2026, 11, 10)); booking.setCheckOutDate(LocalDate.of(2026, 11, 12));
        if ("CANCELLED".equals(status)) booking.setCancelledAt(now);
        em.persist(booking); return booking;
    }

    @Test
    void eligibleMinimumCountsFreePublishedCapacityAndExactPriceTogether() {
        Hotel first = hotel("Europe/Moscow");
        room(first, 1, "100.00", 1); // Too small.
        Room occupied = room(first, 2, "200.00", 2); booking(occupied, "CONFIRMED");
        Room free = room(first, 3, "400.01", 2); booking(free, "CANCELLED");
        Room hidden = room(first, 4, "250.00", 2); hidden.setPublished(false);
        Hotel second = hotel("Europe/Moscow"); room(second, 1, "350.00", 2);
        em.flush(); em.clear();

        var page = catalog.hotels(query("city", "test-city", "guests", "2", "priceMin", "200.00", "priceMax", "400.01",
                "checkInDate", "2026-11-10", "checkOutDate", "2026-11-12", "size", "1", "page", "1"));
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        var result = page.items().get(0);
        assertThat(result.id()).isEqualTo(first.getId().toString());
        assertThat(result.fromPrice().nightlyPrice()).isEqualTo("400.01");
        assertThat(result.fromPrice().totalPrice()).isEqualTo("800.02");
        assertThat(result.matchedRoomCount()).isEqualTo(1);
        assertThat(result.availabilityEvaluated()).isTrue();
        assertThat(catalog.hotels(query("city", "test-city", "guests", "2", "priceMin", "200.00", "priceMax", "400.01",
                "checkInDate", "2026-11-10", "checkOutDate", "2026-11-12", "page", "2147483647", "size", "50")).items()).isEmpty();
    }

    @Test
    void roomsIncludeOccupiedButNotSmallAndCancelledStaysNeverBlockLegacyFilters() {
        Hotel hotel = hotel("Europe/Moscow");
        Room busy = room(hotel, 1, "200.00", 2); booking(busy, "CONFIRMED");
        Room cancelled = room(hotel, 2, "200.00", 2); booking(cancelled, "CANCELLED");
        room(hotel, 3, "100.00", 1);
        em.flush(); em.clear();
        CatalogQuery input = query("guests", "2", "checkInDate", "2026-11-10", "checkOutDate", "2026-11-12");
        var results = catalog.rooms(hotel.getSlug(), input);
        assertThat(results.totalElements()).isEqualTo(2);
        assertThat(results.items()).extracting(CatalogDtos.RoomView::availability).containsExactly("UNAVAILABLE", "AVAILABLE");
        assertThat(results.items()).extracting(CatalogDtos.RoomView::id).containsExactly(busy.getId().toString(), cancelled.getId().toString());
        assertThat(availability.findIntervals(List.of(busy.getId(), cancelled.getId()), input.checkIn(), input.checkOut()))
                .extracting(RoomAvailabilityRepository.Interval::getRoomId).containsExactly(busy.getId());
        assertThat(availability.hasBookings(List.of(cancelled.getId()))).isTrue(); // History still prevents deletion.
        var legacy = legacyRooms.findAll(RoomSpecification.filterBy(cancelled.getId(), null, null, null, null, null, input.checkIn(), input.checkOut()));
        assertThat(legacy).hasSize(1);
        var adjacent = catalog.room(busy.getId(), query("checkInDate", "2026-11-12", "checkOutDate", "2026-11-13"));
        assertThat(adjacent.availability()).isEqualTo("AVAILABLE");
    }

    @Test
    void hiddenLegacyStaysHiddenAndPublicJsonNeverSerializesEntitiesOrOccupancy() throws Exception {
        Hotel hotel = hotel("Europe/Moscow"); Room room = room(hotel, 1, "500.00", 2); booking(room, "CONFIRMED");
        Hotel legacy = new Hotel(); legacy.setName(UUID.randomUUID().toString()); legacy.setTitle("Title"); legacy.setCity("Legacy");
        legacy.setAddress("Address"); legacy.setDistanceFromCityCenter(1.0); em.persist(legacy);
        Room hidden = room(hotel, 2, "300.00", 2); hidden.setPublished(false);
        em.flush(); em.clear();
        var detail = catalog.hotel(hotel.getSlug());
        assertThat(detail.demoLocation()).isNull();
        String payload = json.writeValueAsString(catalog.room(room.getId(), query("checkInDate", "2026-11-10", "checkOutDate", "2026-11-12")));
        assertThat(payload).doesNotContain("userId", "email", "bookings", "unavailableDates", "checkInDate", "checkOutDate", "password");
        assertThat(json.readTree(payload).get("id").isTextual()).isTrue();
        assertThat(json.readTree(payload).get("pricing").get("nightlyPrice").asText()).isEqualTo("500.00");
        assertThatThrownBy(() -> catalog.room(hidden.getId(), query())).isInstanceOfSatisfying(V2ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(404));
        assertThatThrownBy(() -> catalog.hotel(legacy.getId().toString())).isInstanceOfSatisfying(V2ApiException.class, ex -> assertThat(ex.getStatus()).isEqualTo(404));
        Hotel restored = em.find(Hotel.class, legacy.getId());
        assertThat(restored.getPublished()).isFalse(); assertThat(restored.getTimeZone()).isNull(); assertThat(restored.getSlug()).isNull();
    }

    @Test
    void noDatesGiveIndicativePriceAndRoomCapacityIsNeverInvented() {
        Hotel hotel = hotel("Europe/Moscow"); Room room = room(hotel, 1, "100.01", 2);
        booking(room, "CONFIRMED"); em.flush(); em.clear();
        var card = catalog.hotels(query("city", "test-city")).items().get(0);
        assertThat(card.availabilityEvaluated()).isFalse();
        assertThat(card.fromPrice().nights()).isEqualTo(1);
        assertThat(card.fromPrice().totalPrice()).isEqualTo("100.01");
        assertThat(catalog.room(room.getId(), query()).availability()).isEqualTo("NOT_EVALUATED");
        var tooMany = catalog.room(room.getId(), query("guests", "3", "checkInDate", "2026-11-12", "checkOutDate", "2026-11-13"));
        assertThat(tooMany.availability()).isEqualTo("UNAVAILABLE");
        assertThat(tooMany.unavailableReason()).isEqualTo("CAPACITY_EXCEEDED");
    }

    @Test
    void photosAreOrderedAndPageQueryCountIsBounded() {
        for (int i = 0; i < 6; i++) {
            Hotel hotel = hotel("Europe/Moscow");
            CatalogPhoto photo = new CatalogPhoto(); photo.setUrl("/media/hotels/first.webp"); photo.setAlt("Первое"); photo.setCredit("Автор"); photo.setLicense("CC0");
            CatalogPhoto second = new CatalogPhoto(); second.setUrl("/media/hotels/second.webp"); second.setAlt("Второе"); second.setCredit("Автор"); second.setLicense("CC0");
            hotel.getPhotos().add(photo); hotel.getPhotos().add(second); room(hotel, 1, "100.00", 2);
        }
        em.flush(); em.clear();
        var stats = emf.unwrap(SessionFactory.class).getStatistics(); stats.setStatisticsEnabled(true); stats.clear();
        var response = catalog.hotels(query("city", "test-city"));
        assertThat(response.items()).hasSize(6);
        assertThat(response.items()).allSatisfy(card -> assertThat(card.photos()).extracting(CatalogDtos.Photo::alt).containsExactly("Первое", "Второе"));
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(4);
    }

    @Test
    void realZoneBoundariesReturnWarningsAndAllInvalidFailsWithoutMovingDates() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-20T22:30:00Z"));
        Hotel east = hotel("Europe/Moscow"); room(east, 1, "100.00", 2);
        Hotel west = hotel("Pacific/Honolulu"); room(west, 1, "200.00", 2);
        em.flush(); em.clear();
        var partial = catalog.hotels(query("city", "test-city", "checkInDate", "2026-10-21", "checkOutDate", "2026-10-22"));
        assertThat(partial.items()).extracting(CatalogDtos.HotelCard::id).containsExactly(west.getId().toString());
        assertThat(partial.warnings().get(0).timeZones()).containsExactly("Europe/Moscow");
        assertThatThrownBy(() -> catalog.hotels(query("city", "test-city", "checkInDate", "2026-10-20", "checkOutDate", "2026-10-21")))
                .isInstanceOfSatisfying(V2ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("DATE_RANGE_INVALID"));
    }
    @Test
    void inclusiveHorizonAndDstUseLocalCalendarDates() {
        Hotel hotel = hotel("Europe/Berlin"); Room room = room(hotel, 1, "100.01", 2);
        em.flush(); em.clear();
        var dst = catalog.room(room.getId(), query("checkInDate", "2027-03-27", "checkOutDate", "2027-03-29"));
        assertThat(dst.pricing().nights()).isEqualTo(2);
        assertThat(dst.pricing().totalPrice()).isEqualTo("200.02");
        assertThat(catalog.room(room.getId(), query("checkInDate", "2027-10-19", "checkOutDate", "2027-10-20")).availability())
                .isEqualTo("AVAILABLE");
        assertThatThrownBy(() -> catalog.room(room.getId(), query("checkInDate", "2027-10-20", "checkOutDate", "2027-10-21")))
                .isInstanceOfSatisfying(V2ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("DATE_RANGE_INVALID"));
    }

}
