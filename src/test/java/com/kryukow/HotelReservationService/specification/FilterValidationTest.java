package com.kryukow.HotelReservationService.specification;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

class FilterValidationTest {
    @Test
    void datePairIsRequiredAndCheckoutMustFollowCheckin() {
        LocalDate day = LocalDate.of(2027, 1, 1);
        assertThatThrownBy(() -> room(day, null)).hasMessageContaining("together");
        assertThatThrownBy(() -> room(null, day)).hasMessageContaining("together");
        assertThatThrownBy(() -> room(day, day)).hasMessageContaining("before");
        assertThatThrownBy(() -> room(day, day.minusDays(1))).hasMessageContaining("before");
        assertThatCode(() -> room(day, day.plusDays(1))).doesNotThrowAnyException();
    }

    private void room(LocalDate from, LocalDate until) {
        RoomSpecification.filterBy(null, null, null, null, null, null, from, until);
    }

    @Test
    void priceBoundsUseExactDecimalComparison() {
        assertThatThrownBy(() -> RoomSpecification.filterBy(null, null,
                new BigDecimal("10000000000000000.02"), new BigDecimal("10000000000000000.01"),
                null, null, null, null)).hasMessageContaining("priceMin");
        assertThatThrownBy(() -> RoomSpecification.filterBy(null, null, new BigDecimal("-0.01"),
                null, null, null, null, null)).hasMessageContaining("Price bounds");
        assertThatThrownBy(() -> RoomSpecification.filterBy(0L, null, null, null,
                null, null, null, null)).hasMessageContaining("ID");
        assertThatThrownBy(() -> RoomSpecification.filterBy(null, null, null, null,
                0, null, null, null)).hasMessageContaining("Capacity");
    }

    @Test
    void hotelBoundsRejectNegativeReversedAndNonFiniteValues() {
        assertThatThrownBy(() -> HotelSpecification.filterBy(null, null, null, null, null,
                2.0, 1.0, null, null, null, null)).hasMessageContaining("minimum");
        assertThatThrownBy(() -> HotelSpecification.filterBy(null, null, null, null, null,
                Double.NaN, null, null, null, null, null)).hasMessageContaining("finite");
        assertThatThrownBy(() -> HotelSpecification.filterBy(null, null, null, null, null,
                null, null, null, 5.1, null, null)).hasMessageContaining("Rating");
        assertThatThrownBy(() -> HotelSpecification.filterBy(null, null, null, null, null,
                null, null, null, null, 2, 1)).hasMessageContaining("ratingCountMin");
    }
}
