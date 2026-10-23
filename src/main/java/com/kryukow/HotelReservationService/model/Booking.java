package com.kryukow.HotelReservationService.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.Instant;
import java.math.BigDecimal;

@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @Column(name = "check_in_date", nullable = false)
    private LocalDate checkInDate;

    @Column(name = "check_out_date", nullable = false)
    private LocalDate checkOutDate;

    @Column(nullable = false)
    private String status = "CONFIRMED";

    @Column(nullable = false)
    private String origin = "LEGACY";

    private Integer guests;

    @Column(name = "nightly_price", precision = 12, scale = 2)
    private BigDecimal nightlyPrice;

    @Column(name = "total_price", precision = 14, scale = 2)
    private BigDecimal totalPrice;

    private String currency;
    private Integer nights;

    @Column(name = "snapshot_json", columnDefinition = "text")
    private String snapshotJson;

    private String timeZone;
    private Instant cancellationDeadlineAt;
    private Instant createdAt;
    private Instant cancelledAt;
    private String timezoneProvenance;

}
