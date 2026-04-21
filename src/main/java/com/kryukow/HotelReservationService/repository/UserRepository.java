package com.kryukow.HotelReservationService.repository;

import com.kryukow.HotelReservationService.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    // или если нужно несколько
    Page<User> findAllByName(String name, Pageable pageable);


    boolean existsByName(String name);
    boolean existsByEmail(String email);
}
