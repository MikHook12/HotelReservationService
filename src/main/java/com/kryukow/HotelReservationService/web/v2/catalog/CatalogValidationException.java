package com.kryukow.HotelReservationService.web.v2.catalog;

import com.kryukow.HotelReservationService.web.v2.common.V2ApiException;
import java.util.List;
import java.util.Map;

/** Structured catalog validation; fields contain safe names/codes, never rejected values. */
public class CatalogValidationException extends V2ApiException {
    private final List<Map<String, String>> fields;
    public CatalogValidationException(String field, String code, String message) {
        super(400, "VALIDATION_FAILED", "Проверьте параметры запроса.");
        String safeField = field.matches("[A-Za-z][A-Za-z0-9]{0,99}") ? field : "query";
        fields = List.of(Map.of("field", safeField, "code", code, "message", message));
    }
    public List<Map<String, String>> fields() { return fields; }
}
