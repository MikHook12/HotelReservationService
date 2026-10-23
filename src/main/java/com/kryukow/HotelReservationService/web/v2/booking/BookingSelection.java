package com.kryukow.HotelReservationService.web.v2.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.kryukow.HotelReservationService.web.v2.common.V2ApiException;
import java.time.LocalDate;
import java.util.*;

public record BookingSelection(long roomId, LocalDate checkInDate, LocalDate checkOutDate, int guests) {
    public static BookingSelection parse(JsonNode body, boolean create) {
        if (body == null || !body.isObject()) throw error("VALIDATION_FAILED", "Проверьте поля запроса");
        Set<String> allowed = create ? Set.of("roomId","checkInDate","checkOutDate","guests","quoteToken")
                : Set.of("roomId","checkInDate","checkOutDate","guests");
        body.fieldNames().forEachRemaining(k -> { if (!allowed.contains(k)) throw error("VALIDATION_FAILED", "Неизвестное поле запроса"); });
        try {
            JsonNode id = body.get("roomId"), in = body.get("checkInDate"), out = body.get("checkOutDate"), guests = body.get("guests");
            if (id == null || !id.isTextual() || !id.asText().matches("[1-9][0-9]{0,18}") || in == null || out == null
                    || !in.isTextual() || !out.isTextual() || !in.asText().matches("\\d{4}-\\d{2}-\\d{2}")
                    || !out.asText().matches("\\d{4}-\\d{2}-\\d{2}") || guests == null || !guests.isIntegralNumber()
                    || !guests.canConvertToInt() || guests.intValue() < 1 || guests.intValue() > 100) throw new IllegalArgumentException();
            return new BookingSelection(Long.parseLong(id.asText()), LocalDate.parse(in.asText()), LocalDate.parse(out.asText()), guests.intValue());
        } catch (RuntimeException ex) { throw error("VALIDATION_FAILED", "Проверьте комнату, даты и число гостей"); }
    }
    public Map<String,Object> stay() {
        return Map.of("roomId",String.valueOf(roomId),"checkInDate",checkInDate.toString(),"checkOutDate",checkOutDate.toString(),"guests",guests);
    }
    public static UUID key(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"))
            throw error("IDEMPOTENCY_KEY_REQUIRED", "Нужен ключ операции UUIDv4");
        return UUID.fromString(value);
    }
    public static long id(String value) {
        try { if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException(); return Long.parseLong(value); }
        catch (RuntimeException e) { throw error("VALIDATION_FAILED", "Некорректный номер"); }
    }
    private static V2ApiException error(String code, String message) { return new V2ApiException(400, code, message); }
}
