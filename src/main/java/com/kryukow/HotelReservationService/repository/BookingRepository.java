package com.kryukow.HotelReservationService.repository;

import com.kryukow.HotelReservationService.model.Booking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

public interface BookingRepository extends JpaRepository<Booking, Long> {


    @Query("""
           SELECT COUNT(b) > 0 FROM Booking b
           WHERE b.room.id = :roomId
           AND b.checkInDate < :checkOutDate
           AND b.checkOutDate > :checkInDate
           """)
    boolean existsOverlappingBooking(@Param("roomId") Long roomId,
                                     @Param("checkInDate") LocalDate checkInDate,
                                     @Param("checkOutDate") LocalDate checkOutDate);




    Page<Booking> findAllByUserId(Long userId, Pageable pageable);


    Page<Booking> findAllByRoomId(Long roomId, Pageable pageable);
}
