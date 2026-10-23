package com.kryukow.HotelReservationService.web.v2.admin;

import java.util.List;

public final class AdminCatalogDtos {
    private AdminCatalogDtos() {}
    public record Photo(String url, String alt, String credit, String license) {}
    public record HotelInput(Long version, String slug, String name, String city, String cityCode,
            String timeZone, String address, String description, Double distanceFromCityCenter,
            Boolean published, List<Photo> photos) {}
    public record HotelView(String id, Long version, String slug, String name, String city, String cityCode,
            String timeZone, String address, String description, Double distanceFromCityCenter,
            Boolean published, List<Photo> photos) {}
    public record RoomInput(Long version, String name, Integer number, Integer capacity,
            String nightlyPrice, String description, Boolean published, List<Photo> photos) {}
    public record RoomView(String id, String hotelId, Long version, String name, Integer number, Integer capacity,
            String nightlyPrice, String description, Boolean published, List<Photo> photos) {}
    public record Page<T>(List<T> items, int page, int size, long totalElements, int totalPages) {}
}
