package com.kryukow.HotelReservationService.dto.room;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;


@Getter
@Setter
@NoArgsConstructor
public class RoomResponseDto {
    private Long id;
    private String name;
    private String description;
    private Integer number;
    private BigDecimal price;
    private Integer capacity;
    private List<LocalDate> unavailableDates;
    private Long hotelId;
    /** Availability window: from inclusive, until exclusive; UTC calendar dates. */
    private LocalDate availabilityFrom;
    private LocalDate availabilityUntil;
}