package com.kryukow.HotelReservationService.web.v2.catalog;

import com.kryukow.HotelReservationService.model.Hotel;
import com.kryukow.HotelReservationService.web.v2.catalog.model.CatalogPhoto;
import com.kryukow.HotelReservationService.web.v2.common.V2ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.LinkedMultiValueMap;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {
    @Mock CatalogRepository repository;
    CatalogService service;
    final Instant now = Instant.parse("2026-10-20T22:30:00Z");
    @BeforeEach void setup() { service = new CatalogService(repository, Clock.fixed(now, ZoneOffset.UTC)); }

    private CatalogQuery query(String in, String out) {
        var raw = new LinkedMultiValueMap<String, String>();
        if (in != null) { raw.add("checkInDate", in); raw.add("checkOutDate", out); }
        return CatalogQuery.parse(raw, CatalogQuery.Scope.HOTELS);
    }

    @Test
    void partialTimezoneCoverageIsExplicitAndNeverShiftsDates() {
        when(repository.cityZones(null)).thenReturn(List.of(
                new CatalogRepository.CityZone("east", "East", "Europe/Moscow"),
                new CatalogRepository.CityZone("west", "West", "Pacific/Honolulu")));
        CatalogQuery input = query("2026-10-21", "2026-10-22");
        when(repository.hotels(input, Set.of("Pacific/Honolulu"))).thenReturn(new CatalogRepository.Selection<>(List.of(), 0));
        var page = service.hotels(input);
        assertThat(page.evaluatedAt()).isEqualTo(now);
        assertThat(page.items()).isEmpty();
        assertThat(page.warnings()).containsExactly(new CatalogDtos.SearchWarning("DATE_OUT_OF_RANGE_FOR_SOME_HOTELS", List.of("Europe/Moscow")));
        verify(repository).hotels(input, Set.of("Pacific/Honolulu"));
    }

    @Test
    void allInvalidZonesReturnDateLimitsAndNoSearchQuery() {
        when(repository.cityZones(null)).thenReturn(List.of(new CatalogRepository.CityZone("east", "East", "Europe/Moscow")));
        assertThatThrownBy(() -> service.hotels(query("2026-10-21", "2026-10-22")))
                .isInstanceOfSatisfying(V2ApiException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("DATE_RANGE_INVALID");
                    assertThat(error.getDetails()).containsKey("dateLimits");
                });
        verify(repository, never()).hotels(any(), any());
    }

    @Test
    void noDatesAndNoRoomsReturnEmptyWithoutAvailabilityClaim() {
        when(repository.cityZones(null)).thenReturn(List.of());
        when(repository.hotels(any(), eq(Set.of()))).thenReturn(new CatalogRepository.Selection<>(List.of(), 0));
        var result = service.hotels(query(null, null));
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void cityLimitsUseHotelZoneAndInclusive365DayHorizon() {
        when(repository.cityZones(null)).thenReturn(List.of(new CatalogRepository.CityZone("east", "East", "Europe/Moscow")));
        var city = service.cities().items().get(0);
        assertThat(city.dateLimits().get(0).hotelToday()).hasToString("2026-10-21");
        assertThat(city.dateLimits().get(0).minCheckInDate()).hasToString("2026-10-22");
        assertThat(city.dateLimits().get(0).maxCheckOutDate()).hasToString("2027-10-21");
    }

    @Test
    void missingCityIsValidationAndNumericHotelPathNeverFallsBackToId() {
        when(repository.cityZones("missing")).thenReturn(List.of());
        var raw = new LinkedMultiValueMap<String, String>(); raw.add("city", "missing");
        assertThatThrownBy(() -> service.hotels(CatalogQuery.parse(raw, CatalogQuery.Scope.HOTELS)))
                .isInstanceOf(CatalogValidationException.class);
        assertThatThrownBy(() -> service.hotel("123")).isInstanceOfSatisfying(V2ApiException.class,
                ex -> assertThat(ex.getCode()).isEqualTo("NOT_FOUND"));
        verify(repository, never()).hotel(any());
    }

    @Test
    void decimalPricingFitsThirtyNightMaximumWithoutFloatOrRounding() {
        var pricing = CatalogService.pricing(new BigDecimal("9999999999.99"), 30);
        assertThat(pricing.nightlyPrice()).isEqualTo("9999999999.99");
        assertThat(pricing.totalPrice()).isEqualTo("299999999999.70");
        assertThatThrownBy(() -> CatalogService.pricing(new BigDecimal("1.001"), 1)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void photoAllowlistRejectsTraversalRemoteUrlsAndUnreviewedMetadata() {
        CatalogPhoto safe = photo("/media/hotels/room-one.webp");
        CatalogPhoto remote = photo("https://example.com/image.jpg");
        CatalogPhoto traversal = photo("/media/../private.jpg");
        CatalogPhoto encoded = photo("/media/%2e%2e/private.jpg");
        CatalogPhoto noCredit = photo("/media/no-credit.jpg"); noCredit.setCredit("");
        assertThat(CatalogService.photos(List.of(safe, remote, traversal, encoded, noCredit)))
                .containsExactly(new CatalogDtos.Photo(safe.getUrl(), "Описание", "Автор", "CC0"));
    }

    private CatalogPhoto photo(String url) {
        var photo = new CatalogPhoto(); photo.setUrl(url); photo.setAlt("Описание"); photo.setCredit("Автор"); photo.setLicense("CC0"); return photo;
    }
}
