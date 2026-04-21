package com.kryukow.HotelReservationService.integration;

import com.kryukow.HotelReservationService.dto.hotel.HotelCreateUpdateRequest;
import com.kryukow.HotelReservationService.dto.hotel.HotelResponseDto;
import com.kryukow.HotelReservationService.exception.EntityNotFoundException;
import com.kryukow.HotelReservationService.service.HotelService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
class HotelServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_db")
            .withUsername("test_user")
            .withPassword("test_pass");

    @Autowired
    private HotelService hotelService;

    // 👇 Подменяем datasource Spring'а на контейнер
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private HotelCreateUpdateRequest createValidRequest() {
        HotelCreateUpdateRequest request = new HotelCreateUpdateRequest();
        request.setName("Test");
        request.setTitle("Title");
        request.setCity("City");
        request.setAddress("Address");
        request.setDistanceFromCityCenter(1.0);
        return request;
    }

    @Test
    void createAndGetById_shouldWork() {
        HotelCreateUpdateRequest request = new HotelCreateUpdateRequest();
        request.setName("Hilton");
        request.setTitle("Luxury");
        request.setCity("Paris");
        request.setAddress("Center");
        request.setDistanceFromCityCenter(1.0);

        // create
        HotelResponseDto created = hotelService.create(request);

        assertNotNull(created.getId());

        // getById
        HotelResponseDto found = hotelService.getById(created.getId());

        assertEquals("Hilton", found.getName());
        assertEquals("Paris", found.getCity());
    }

    @Test
    void update_shouldPersistChanges() {
        HotelCreateUpdateRequest request = new HotelCreateUpdateRequest();
        request.setName("Old");
        request.setTitle("Title");           // ← добавить
        request.setCity("City");             // ← добавить
        request.setAddress("Address");       // ← добавить
        request.setDistanceFromCityCenter(1.0);  // ← добавить

        HotelResponseDto created = hotelService.create(request);

        HotelCreateUpdateRequest update = new HotelCreateUpdateRequest();
        update.setName("New");
        update.setTitle("New Title");        // ← добавить
        update.setCity("New City");          // ← добавить
        update.setAddress("New Address");    // ← добавить
        update.setDistanceFromCityCenter(2.0);   // ← добавить

        HotelResponseDto updated = hotelService.update(created.getId(), update);

        assertEquals("New", updated.getName());
    }

    @Test
    void delete_shouldRemoveHotel() {
        HotelCreateUpdateRequest request = createValidRequest();
        request.setName("ToDelete");

        HotelResponseDto created = hotelService.create(request);
        hotelService.delete(created.getId());

        assertThrows(EntityNotFoundException.class,
                () -> hotelService.getById(created.getId()));
    }

    @Test
    void getById_whenNotFound_shouldThrowException() {
        assertThrows(EntityNotFoundException.class,
                () -> hotelService.getById(999L));
    }
}
