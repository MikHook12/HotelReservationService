package com.kryukow.HotelReservationService.repository;

import com.kryukow.HotelReservationService.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Page<User> findAllByName(String name, Pageable pageable);
    Optional<User> findByName(String name);



    boolean existsByName(String name);
    boolean existsByEmail(String email);
}
