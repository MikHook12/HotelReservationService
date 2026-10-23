package com.kryukow.HotelReservationService.web.v2.catalog;

import com.kryukow.HotelReservationService.integration.BaseIntegrationTest;
import com.kryukow.HotelReservationService.model.Hotel;
import com.kryukow.HotelReservationService.model.Room;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A real committed write between candidate read and count/items must not tear the response. */
class CatalogSnapshotIntegrationTest extends BaseIntegrationTest {
    @Autowired CatalogService catalog;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @SpyBean CatalogRepository repository;
    @MockBean Clock clock;

    @Test
    void committedRoomAppearsOnlyOnTheNextRequestSnapshot() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-20T12:00:00Z"));
        String key = "snapshot-" + UUID.randomUUID();
        TransactionTemplate write = new TransactionTemplate(manager);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Long hotelId = write.execute(tx -> {
            Hotel hotel = new Hotel(); hotel.setName(key); hotel.setTitle("Title"); hotel.setCity("Тест");
            hotel.setCityCode(key); hotel.setAddress("Address"); hotel.setDistanceFromCityCenter(1.0);
            hotel.setSlug(key); hotel.setTimeZone("Europe/Moscow"); hotel.setDemo(true); hotel.setPublished(true);
            hotel.setDescription("Тестовый отель"); em.persist(hotel); return hotel.getId();
        });
        AtomicLong roomId = new AtomicLong();
        AtomicBoolean inserted = new AtomicBoolean();
        try {
            doAnswer(invocation -> {
                @SuppressWarnings("unchecked") List<CatalogRepository.CityZone> zones = (List<CatalogRepository.CityZone>) invocation.callRealMethod();
                assertThat(em.createNativeQuery("show transaction_isolation").getSingleResult()).isEqualTo("repeatable read");
                if (inserted.compareAndSet(false, true)) {
                    write.executeWithoutResult(tx -> {
                        Room room = new Room(); room.setHotel(em.getReference(Hotel.class, hotelId)); room.setNumber(1);
                        room.setName("Room"); room.setDescription("Room"); room.setPrice(new BigDecimal("100.00"));
                        room.setCapacity(2); room.setPublished(true); em.persist(room); roomId.set(room.getId());
                    });
                }
                return zones;
            }).when(repository).cityZones(key);
            var raw = new LinkedMultiValueMap<String, String>(); raw.add("city", key);
            CatalogQuery input = CatalogQuery.parse(raw, CatalogQuery.Scope.HOTELS);
            var first = catalog.hotels(input);
            assertThat(first.totalElements()).isZero();
            assertThat(first.items()).isEmpty();
            var next = catalog.hotels(input);
            assertThat(next.totalElements()).isEqualTo(1);
            assertThat(next.items()).hasSize(1);
            assertThat(next.items().get(0).id()).isEqualTo(hotelId.toString());
        } finally {
            // Test-owned rows only, in the isolated container. No shared/working database is accessed.
            write.executeWithoutResult(tx -> {
                Room room = roomId.get() == 0 ? null : em.find(Room.class, roomId.get());
                if (room != null) em.remove(room);
                Hotel hotel = em.find(Hotel.class, hotelId);
                if (hotel != null) em.remove(hotel);
            });
        }
    }
}
