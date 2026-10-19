package com.kryukow.HotelReservationService.specification;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Validation shared by HTTP and direct service/specification callers. */
final class FilterValidation {
    private FilterValidation() {}

    static void room(Long id, BigDecimal min, BigDecimal max, Integer capacity, Long hotelId,
                     LocalDate checkIn, LocalDate checkOut) {
        positiveId(id); positiveId(hotelId);
        if (min != null && min.signum() < 0 || max != null && max.signum() < 0)
            throw new IllegalArgumentException("Price bounds must be >= 0");
        if (min != null && max != null && min.compareTo(max) > 0)
            throw new IllegalArgumentException("priceMin must be <= priceMax");
        if (capacity != null && capacity <= 0)
            throw new IllegalArgumentException("Capacity must be positive");
        if ((checkIn == null) != (checkOut == null))
            throw new IllegalArgumentException("checkInDate and checkOutDate must be supplied together");
        if (checkIn != null && !checkIn.isBefore(checkOut))
            throw new IllegalArgumentException("checkInDate must be before checkOutDate");
    }

    static void hotel(Long id, Double distanceMin, Double distanceMax, Double ratingMin, Double ratingMax,
                      Integer countMin, Integer countMax) {
        positiveId(id);
        finiteRange(distanceMin, distanceMax, "Distance", Double.MAX_VALUE);
        finiteRange(ratingMin, ratingMax, "Rating", 5);
        if (countMin != null && countMin < 0 || countMax != null && countMax < 0)
            throw new IllegalArgumentException("Rating count bounds must be >= 0");
        if (countMin != null && countMax != null && countMin > countMax)
            throw new IllegalArgumentException("ratingCountMin must be <= ratingCountMax");
    }

    private static void positiveId(Long id) {
        if (id != null && id <= 0) throw new IllegalArgumentException("ID must be positive");
    }

    private static void finiteRange(Double min, Double max, String name, double upper) {
        if (min != null && (!Double.isFinite(min) || min < 0 || min > upper)
                || max != null && (!Double.isFinite(max) || max < 0 || max > upper))
            throw new IllegalArgumentException(name + " bounds must be finite and between 0 and " + upper);
        if (min != null && max != null && min > max)
            throw new IllegalArgumentException(name + " minimum must be <= maximum");
    }
}
