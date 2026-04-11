package com.kryukow.HotelReservationService.repository;

import com.kryukow.HotelReservationService.model.Hotel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HotelRepository extends JpaRepository<Hotel, Long> {
}
