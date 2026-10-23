package com.kryukow.HotelReservationService.web.v2.common;

import java.util.Map;
import java.util.List;

public class V2ApiException extends RuntimeException {
    private List<Map<String,String>> fieldErrors = List.of();
    private final int status;
    private final String code;
    private final Map<String, Object> details;

    public V2ApiException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }
    public V2ApiException(int status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }
    public V2ApiException withFieldError(String field, String code, String message) {
        fieldErrors = List.of(Map.of("field",field,"code",code,"message",message));
        return this;
    }
    public List<Map<String,String>> getFieldErrors() { return fieldErrors; }
    public int getStatus() { return status; }
    public String getCode() { return code; }
    public Map<String, Object> getDetails() { return details; }
}
