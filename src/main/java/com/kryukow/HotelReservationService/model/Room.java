package com.kryukow.HotelReservationService.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import com.kryukow.HotelReservationService.web.v2.catalog.model.CatalogPhoto;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "rooms",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"hotel_id", "number"})
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor

public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Version
    private Long version;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "number", nullable = false)
    private Integer number;

    @Column(name = "price", nullable = false)
    private BigDecimal price;

//    вместимость людей в комнату
    @Column(name = "room_capacity", nullable = false)
    private Integer capacity;

    @Column(nullable = false)
    private Boolean published = false;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "room_photos", joinColumns = @JoinColumn(name = "room_id"))
    @OrderColumn(name = "display_order")
    private List<CatalogPhoto> photos = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hotel_id", nullable = false)
    private Hotel hotel;

    @OneToMany(mappedBy = "room", fetch = FetchType.LAZY)
    private List<Booking> bookings = new ArrayList<>();






}
