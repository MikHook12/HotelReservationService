package com.kryukow.HotelReservationService.repository;

import com.kryukow.HotelReservationService.model.Room;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Separate read/deletion queries; booking commands own RoomRepository. */
public interface RoomAvailabilityRepository extends Repository<Room, Long> {
    interface Interval {
        Long getRoomId();
        LocalDate getCheckInDate();
        LocalDate getCheckOutDate();
    }

    @Query("select b.room.id as roomId, b.checkInDate as checkInDate, b.checkOutDate as checkOutDate " +
            "from Booking b where b.room.id in :ids and b.checkInDate < :until and b.checkOutDate > :from")
    List<Interval> findIntervals(@Param("ids") Collection<Long> ids, @Param("from") LocalDate from,
                                 @Param("until") LocalDate until);

    @Query("select (count(b) > 0) from Booking b where b.room.id in :ids")
    boolean hasBookings(@Param("ids") Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Room r where r.id = :id")
    Optional<Room> lockById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Room r where r.hotel.id = :hotelId order by r.id")
    List<Room> lockByHotelId(@Param("hotelId") Long hotelId);

    @Modifying
    @Query("delete from Room r where r.hotel.id = :hotelId")
    void deleteUnbookedRooms(@Param("hotelId") Long hotelId);
}
