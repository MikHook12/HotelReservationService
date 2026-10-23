package com.kryukow.HotelReservationService.model;


import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import com.kryukow.HotelReservationService.web.v2.catalog.model.CatalogPhoto;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "hotels")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor

public class Hotel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "city", nullable = false)
    private String city;

    @Column(name = "address", nullable = false)
    private String address;

    @Column(name = "distance_from_city_center", nullable = false)
    private Double distanceFromCityCenter;

    @Column(name = "rating")
    private Double rating = 0.0;

    @Column(name = "count_of_ratings", nullable = false)
    private Integer countOfRatings = 0;

    @Column(name = "rating_sum", nullable = false, precision = 19, scale = 4)
    private BigDecimal ratingSum = BigDecimal.ZERO;

    @Version
    private Long version;

    @Column(length = 100, unique = true)
    private String slug;

    @Column(name = "time_zone", length = 64)
    private String timeZone;

    @Column(name = "city_code", length = 64)
    private String cityCode;

    @Column(nullable = false)
    private Boolean published = false;

    @Column(nullable = false)
    private Boolean demo = false;

    @Column(columnDefinition = "text")
    private String description;

    @Column(precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(precision = 9, scale = 6)
    private BigDecimal longitude;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "hotel_photos", joinColumns = @JoinColumn(name = "hotel_id"))
    @OrderColumn(name = "display_order")
    private List<CatalogPhoto> photos = new ArrayList<>();

    @OneToMany(mappedBy = "hotel", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
    private List<Room> rooms = new ArrayList<>();


    @PrePersist
    @PreUpdate
    private void validatePublicationZone() {
        if (Boolean.TRUE.equals(published) && (timeZone == null || !java.time.ZoneId.getAvailableZoneIds().contains(timeZone))) {
            throw new IllegalStateException("Published hotels require an explicit valid IANA time zone");
        }
    }

}
