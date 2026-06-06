package com.kryukow.HotelReservationService.repository;

import com.kryukow.HotelReservationService.model.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RoomRepository extends JpaRepository<Room, Long>, JpaSpecificationExecutor<Room> {
    boolean existsByHotelIdAndNumber(Long hotelId, Integer number);

}
