package com.kryukow.HotelReservationService.specification;

import com.kryukow.HotelReservationService.model.Hotel;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;

public class HotelSpecification {

    public static Specification<Hotel> filterBy(String id, String name, String title,
                                                String city, String address,
                                                Double distanceMin, Double distanceMax,
                                                Double ratingMin, Double ratingMax,
                                                Integer ratingCountMin, Integer ratingCountMax) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (id != null && !id.isBlank()) {
                predicates.add(criteriaBuilder.equal(root.get("id"), Long.parseLong(id)));
            }
            if (name != null && !name.isBlank()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("name")),
                        "%" + name.toLowerCase() + "%"));
            }
            if (title != null && !title.isBlank()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("title")),
                        "%" + title.toLowerCase() + "%"));
            }
            if (city != null && !city.isBlank()) {
                predicates.add(criteriaBuilder.equal(criteriaBuilder.lower(root.get("city")),
                        city.toLowerCase()));
            }
            if (address != null && !address.isBlank()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("address")),
                        "%" + address.toLowerCase() + "%"));
            }
            if (distanceMin != null) {
                predicates.add(criteriaBuilder.ge(root.get("distanceFromCityCenter"), distanceMin));
            }
            if (distanceMax != null) {
                predicates.add(criteriaBuilder.le(root.get("distanceFromCityCenter"), distanceMax));
            }
            if (ratingMin != null) {
                predicates.add(criteriaBuilder.ge(root.get("rating"), ratingMin));
            }
            if (ratingMax != null) {
                predicates.add(criteriaBuilder.le(root.get("rating"), ratingMax));
            }
            if (ratingCountMin != null) {
                predicates.add(criteriaBuilder.ge(root.get("countOfRatings"), ratingCountMin));
            }
            if (ratingCountMax != null) {
                predicates.add(criteriaBuilder.le(root.get("countOfRatings"), ratingCountMax));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}