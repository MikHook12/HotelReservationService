package com.kryukow.HotelReservationService.web.v2.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import static org.assertj.core.api.Assertions.*;

class CatalogQueryTest {
    private CatalogQuery query(String... pairs) {
        var query = new LinkedMultiValueMap<String, String>();
        for (int index = 0; index < pairs.length; index += 2) query.add(pairs[index], pairs[index + 1]);
        return CatalogQuery.parse(query, CatalogQuery.Scope.HOTELS);
    }

    @Test
    void defaultsDoNotClaimAvailability() {
        CatalogQuery query = query();
        assertThat(query.dated()).isFalse();
        assertThat(query.nights()).isEqualTo(1);
        assertThat(query.guests()).isEqualTo(1);
        assertThat(query.size()).isEqualTo(20);
        assertThat(query.sort()).isEqualTo("priceAsc");
    }

    @Test
    void rejectsUnknownRepeatedEmptyAndNoncanonicalQueries() {
        assertThatThrownBy(() -> query("guests", "2", "guests", "3")).isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> query("guests", "")).isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> query("guests", "01")).isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> query("userId", "7")).isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> query("size", "51")).isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> query("page", "-1")).isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> query("city", "Москва")).isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> query("sort", "r.price desc")).isInstanceOf(CatalogValidationException.class);
    }

    @Test
    void datePairsAreStrictCalendarNightsIncludingDst() {
        assertThatThrownBy(() -> query("checkInDate", "2027-03-27")).isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> query("checkInDate", "2027-02-29", "checkOutDate", "2027-03-01")).isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> query("checkInDate", "2027-03-27", "checkOutDate", "2027-03-27")).isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> query("checkInDate", "2027-03-27", "checkOutDate", "2027-04-27")).isInstanceOf(CatalogValidationException.class);
        assertThat(query("checkInDate", "2027-03-27", "checkOutDate", "2027-03-29").nights()).isEqualTo(2);
    }

    @Test
    void monetaryInputsAreExactAndNeverRounded() {
        for (String value : new String[]{"1", "1.0", "1e2", "01.00", "-1.00", "1.001", " 1.00", "10000000000.00"})
            assertThatThrownBy(() -> query("priceMin", value)).isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> query("priceMin", "10.02", "priceMax", "10.01")).isInstanceOf(CatalogValidationException.class);
        assertThat(query("priceMin", "9999999999.99").priceMin()).isEqualByComparingTo("9999999999.99");
    }

    @Test
    void largePageDoesNotOverflowOffsetAndIdsNeverLoseLongPrecision() {
        assertThat(query("page", "2147483647", "size", "50").offset()).isEqualTo(107374182350L);
        assertThat(CatalogQuery.roomId("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
        for (String id : new String[]{"01", "0", "-1", "9223372036854775808", "1.0"})
            assertThatThrownBy(() -> CatalogQuery.roomId(id)).isInstanceOf(CatalogValidationException.class);
    }
}
