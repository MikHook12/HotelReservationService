package com.kryukow.HotelReservationService.web.v2.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class V2Errors {
    public static final String TRACE = "v2.traceId";
    private final ObjectMapper mapper;
    public V2Errors(ObjectMapper mapper) { this.mapper = mapper; }
    public static String trace(HttpServletRequest request) {
        Object current = request.getAttribute(TRACE);
        if (current instanceof String value) return value;
        String value = UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(TRACE, value);
        return value;
    }
    public static void headers(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("Vary", "Cookie");
        response.setHeader("X-Trace-Id", trace(request));
    }
    public static Map<String, Object> body(HttpServletRequest request, int status, String code,
                                          String message, List<?> fields, Map<String, ?> details) {
        return Map.of("status", status, "code", code, "message", message,
                "fieldErrors", fields, "traceId", trace(request), "details", details);
    }
    public void write(HttpServletRequest request, HttpServletResponse response, int status,
                      String code, String message) throws IOException {
        write(request, response, status, code, message, List.of(), Map.of());
    }
    public void write(HttpServletRequest request, HttpServletResponse response, int status,
                      String code, String message, List<?> fields, Map<String, ?> details) throws IOException {
        headers(request, response);
        if (code.equals("OPERATION_IN_PROGRESS")) response.setHeader("Retry-After", "2");
        if (status == 429 && details.get("retryAfterSeconds") instanceof Number seconds)
            response.setHeader("Retry-After", seconds.toString());
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), body(request, status, code, message, fields, details));
    }
}
