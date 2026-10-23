package com.kryukow.HotelReservationService.web.v2.catalog;

import com.kryukow.HotelReservationService.web.v2.common.V2ApiException;
import org.springframework.util.MultiValueMap;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/** Strict raw-query parsing preserves omitted vs empty and rejects duplicate scalar parameters. */
public record CatalogQuery(String city, LocalDate checkIn, LocalDate checkOut, int guests,
                           BigDecimal priceMin, BigDecimal priceMax, int page, int size, String sort) {
    public enum Scope {
        CITIES(Set.of()), HOTEL(Set.of()),
        HOTELS(Set.of("city", "checkInDate", "checkOutDate", "guests", "priceMin", "priceMax", "page", "size", "sort")),
        ROOMS(Set.of("checkInDate", "checkOutDate", "guests", "priceMin", "priceMax", "page", "size", "sort")),
        ROOM(Set.of("checkInDate", "checkOutDate", "guests"));
        final Set<String> allowed;
        Scope(Set<String> allowed) { this.allowed = allowed; }
    }

    public static CatalogQuery parse(MultiValueMap<String, String> raw, Scope scope) {
        raw.forEach((field, values) -> {
            if (!scope.allowed.contains(field)) throw invalid(field, "UNKNOWN_FIELD", "Неизвестный параметр.");
            if (values.size() != 1 || values.get(0) == null || values.get(0).isEmpty())
                throw invalid(field, "INVALID_FORMAT", "Параметр должен иметь одно непустое значение.");
        });
        String city = raw.getFirst("city");
        if (city != null && !city.matches("[a-z0-9-]{1,64}"))
            throw invalid("city", "INVALID_FORMAT", "Некорректный код города.");
        LocalDate in = date(raw.getFirst("checkInDate"), "checkInDate");
        LocalDate out = date(raw.getFirst("checkOutDate"), "checkOutDate");
        if ((in == null) != (out == null)) throw invalid("checkOutDate", "REQUIRED", "Укажите обе даты.");
        if (in != null && (ChronoUnit.DAYS.between(in, out) < 1 || ChronoUnit.DAYS.between(in, out) > 30))
            throw invalid("checkOutDate", "INVALID_RANGE", "Проживание должно длиться от 1 до 30 ночей.");
        int guests = integer(raw.getFirst("guests"), 1, 1, 100, "guests");
        int page = integer(raw.getFirst("page"), 0, 0, Integer.MAX_VALUE, "page");
        int size = integer(raw.getFirst("size"), 20, 1, 50, "size");
        BigDecimal min = price(raw.getFirst("priceMin"), "priceMin");
        BigDecimal max = price(raw.getFirst("priceMax"), "priceMax");
        if (min != null && max != null && min.compareTo(max) > 0)
            throw invalid("priceMax", "INVALID_RANGE", "Максимальная цена должна быть не меньше минимальной.");
        String sort = raw.getFirst("sort") == null ? "priceAsc" : raw.getFirst("sort");
        if (!Set.of("priceAsc", "priceDesc", "idAsc").contains(sort))
            throw invalid("sort", "INVALID_FORMAT", "Неизвестный порядок сортировки.");
        return new CatalogQuery(city, in, out, guests, min, max, page, size, sort);
    }

    public boolean dated() { return checkIn != null; }
    public int nights() { return dated() ? Math.toIntExact(ChronoUnit.DAYS.between(checkIn, checkOut)) : 1; }
    public long offset() { return (long) page * size; }

    public static long roomId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}"))
            throw invalid("roomId", "INVALID_FORMAT", "Некорректный ID комнаты.");
        try { return Long.parseLong(value); }
        catch (NumberFormatException ex) { throw invalid("roomId", "OUT_OF_RANGE", "Некорректный ID комнаты."); }
    }

    private static LocalDate date(String value, String field) {
        if (value == null) return null;
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() < 1) throw new IllegalArgumentException();
            return date;
        } catch (RuntimeException ex) { throw invalid(field, "INVALID_FORMAT", "Укажите календарную дату ГГГГ-ММ-ДД."); }
    }

    private static int integer(String value, int fallback, int min, int max, String field) {
        if (value == null) return fallback;
        try {
            if (!value.matches("0|[1-9][0-9]*")) throw new NumberFormatException();
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException ex) { throw invalid(field, "OUT_OF_RANGE", "Значение вне допустимого диапазона."); }
    }

    private static BigDecimal price(String value, String field) {
        if (value == null) return null;
        if (!value.matches("(0|[1-9][0-9]{0,9})\\.[0-9]{2}"))
            throw invalid(field, "INVALID_FORMAT", "Цена должна иметь ровно два знака после запятой.");
        return new BigDecimal(value);
    }

    static V2ApiException invalid(String field, String code, String message) {
        return new CatalogValidationException(field, code, message);
    }
}
