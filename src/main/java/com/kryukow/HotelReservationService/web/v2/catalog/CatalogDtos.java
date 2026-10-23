package com.kryukow.HotelReservationService.web.v2.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Public allowlist DTOs. No JPA entity, occupancy interval, owner or guest data is serialized. */
public final class CatalogDtos {
    private CatalogDtos() {}
    public record Photo(String url, String alt, String credit, String license) {}
    public record Pricing(String nightlyPrice, int nights, String totalPrice, String currency, boolean feesIncluded) {}
    public record DateLimits(LocalDate hotelToday, LocalDate minCheckInDate, LocalDate maxCheckOutDate,
                             int maxNights, String timeZone) {}
    public record DemoLocation(BigDecimal latitude, BigDecimal longitude, boolean isFictional) {}
    public record City(String code, String name, List<String> timeZones, List<DateLimits> dateLimits) {}
    public record CityList(List<City> items, Instant evaluatedAt) {}
    public record SearchWarning(String code, List<String> timeZones) {}
    public record HotelCard(String id, String slug, String name, String cityCode, String cityName,
                            String timeZone, boolean isDemo, List<Photo> photos, Pricing fromPrice,
                            boolean availabilityEvaluated, long matchedRoomCount) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record HotelDetail(String id, String slug, String name, String cityCode, String cityName,
                              String timeZone, boolean isDemo, List<Photo> photos, String description,
                              String address, DemoLocation demoLocation, DateLimits dateLimits) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RoomView(String id, String hotelId, String hotelSlug, String name, String description,
                           int capacity, List<Photo> photos, Pricing pricing, String availability,
                           String unavailableReason, DateLimits dateLimits) {}
    public record HotelPage(List<HotelCard> items, int page, int size, long totalElements, long totalPages,
                            String sort, Instant evaluatedAt, List<SearchWarning> warnings) {}
    public record RoomPage(List<RoomView> items, int page, int size, long totalElements, long totalPages,
                           String sort, Instant evaluatedAt) {}
}
