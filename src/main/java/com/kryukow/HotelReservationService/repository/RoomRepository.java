package com.kryukow.HotelReservationService.repository;

import com.kryukow.HotelReservationService.model.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RoomRepository extends JpaRepository<Room, Long>, JpaSpecificationExecutor<Room> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Room r where r.id = :id")
    java.util.Optional<Room> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    boolean existsByHotelIdAndNumber(Long hotelId, Integer number);

}
