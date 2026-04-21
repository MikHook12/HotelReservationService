package com.kryukow.HotelReservationService.integration;

import com.kryukow.HotelReservationService.dto.room.RoomCreateUpdateRequest;
import com.kryukow.HotelReservationService.dto.room.RoomResponseDto;
import com.kryukow.HotelReservationService.exception.EntityNotFoundException;
import com.kryukow.HotelReservationService.model.Hotel;
import com.kryukow.HotelReservationService.repository.HotelRepository;
import com.kryukow.HotelReservationService.service.RoomService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

public class RoomServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private RoomService roomService;

    @Autowired
    private HotelRepository hotelRepository;

    // 🔹 Вспомогательный метод создания отеля
    private Hotel createHotel() {
        Hotel hotel = new Hotel();
        hotel.setName("Test Hotel");
        hotel.setTitle("Nice hotel");
        hotel.setCity("Vilnius");
        hotel.setAddress("Street 1");
        hotel.setDistanceFromCityCenter(1.0);
        hotel.setRating(0.0);
        hotel.setCountOfRatings(0);

        return hotelRepository.save(hotel);
    }

    // 🔹 Вспомогательный метод создания DTO
    private RoomCreateUpdateRequest createRoomRequest(Long hotelId) {
        RoomCreateUpdateRequest request = new RoomCreateUpdateRequest();
        request.setName("Room 1");
        request.setDescription("Nice room");
        request.setNumber(101);
        request.setPrice(BigDecimal.valueOf(100));
        request.setCapacity(2);
        request.setHotelId(hotelId);
        return request;
    }

    // ✅ CREATE
    @Test
    void shouldCreateRoom() {
        Hotel hotel = createHotel();
        RoomCreateUpdateRequest request = createRoomRequest(hotel.getId());

        RoomResponseDto result = roomService.create(request);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        assertThat(result.getName()).isEqualTo("Room 1");
        assertThat(result.getHotelId()).isEqualTo(hotel.getId());
        assertThat(result.getUnavailableDates()).isEmpty(); // нет бронирований
    }

    // ✅ GET BY ID
    @Test
    void shouldGetRoomById() {
        Hotel hotel = createHotel();
        RoomResponseDto created = roomService.create(createRoomRequest(hotel.getId()));

        RoomResponseDto found = roomService.getById(created.getId());

        assertThat(found.getId()).isEqualTo(created.getId());
        assertThat(found.getName()).isEqualTo("Room 1");
    }

    // ❌ NOT FOUND
    @Test
    void shouldThrowExceptionWhenRoomNotFound() {
        assertThatThrownBy(() -> roomService.getById(999L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ✅ UPDATE
    @Test
    void shouldUpdateRoom() {
        Hotel hotel = createHotel();
        RoomResponseDto created = roomService.create(createRoomRequest(hotel.getId()));

        RoomCreateUpdateRequest updateRequest = new RoomCreateUpdateRequest();
        updateRequest.setName("Updated Room");
        updateRequest.setDescription("Updated desc");
        updateRequest.setNumber(202);
        updateRequest.setPrice(BigDecimal.valueOf(200));
        updateRequest.setCapacity(3);
        updateRequest.setHotelId(hotel.getId());

        RoomResponseDto updated = roomService.update(created.getId(), updateRequest);

        assertThat(updated.getName()).isEqualTo("Updated Room");
        assertThat(updated.getNumber()).isEqualTo(202);
    }

    // ✅ DELETE
    @Test
    void shouldDeleteRoom() {
        Hotel hotel = createHotel();
        RoomResponseDto created = roomService.create(createRoomRequest(hotel.getId()));

        roomService.delete(created.getId());

        assertThatThrownBy(() -> roomService.getById(created.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ❌ DELETE NOT FOUND
    @Test
    void shouldThrowExceptionWhenDeletingNonExistingRoom() {
        assertThatThrownBy(() -> roomService.delete(999L))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
