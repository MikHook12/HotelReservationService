package com.kryukow.HotelReservationService.repository;

import com.kryukow.HotelReservationService.model.Room;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Long> {
}
