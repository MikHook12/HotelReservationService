package com.kryukow.HotelReservationService.specification;

import com.kryukow.HotelReservationService.model.Booking;
import com.kryukow.HotelReservationService.model.Room;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class RoomSpecification {

    public static Specification<Room> filterBy(
            String id,
            String name,
            Double priceMin,
            Double priceMax,
            Integer capacity,
            Long hotelId,
            LocalDate checkInDate,
            LocalDate checkOutDate
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (id != null && !id.isBlank()) {
                predicates.add(cb.equal(root.get("id"), Long.parseLong(id)));
            }

            if (name != null && !name.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("name")), "%" + name.toLowerCase() + "%"));
            }

            if (priceMin != null) {
                predicates.add(cb.ge(root.get("price"), priceMin));
            }

            if (priceMax != null) {
                predicates.add(cb.le(root.get("price"), priceMax));
            }

            if (capacity != null) {
                predicates.add(cb.ge(root.get("capacity"), capacity));
            }

            if (hotelId != null) {
                predicates.add(cb.equal(root.get("hotel").get("id"), hotelId));
            }

            if (checkInDate != null && checkOutDate != null) {

                Subquery<Long> bookingSubquery = query.subquery(Long.class);
                Root<Booking> bookingRoot = bookingSubquery.from(Booking.class);
                bookingSubquery.select(bookingRoot.get("id"));
                bookingSubquery.where(
                        cb.equal(bookingRoot.get("room"), root),
                        cb.lessThan(bookingRoot.get("checkInDate"), checkOutDate),
                        cb.greaterThan(bookingRoot.get("checkOutDate"), checkInDate)
                );
                predicates.add(cb.not(cb.exists(bookingSubquery)));

                
                Subquery<LocalDate> unavailableSubquery = query.subquery(LocalDate.class);
                Root<Room> roomRoot = unavailableSubquery.from(Room.class);
                Join<Object, Object> unavailableJoin = roomRoot.join("unavailableDates");
                unavailableSubquery.select(unavailableJoin.get("unavailableDate"));
                unavailableSubquery.where(
                        cb.equal(roomRoot, root),
                        cb.between(unavailableJoin.get("unavailableDate"), checkInDate, checkOutDate.minusDays(1))
                );
                predicates.add(cb.not(cb.exists(unavailableSubquery)));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}