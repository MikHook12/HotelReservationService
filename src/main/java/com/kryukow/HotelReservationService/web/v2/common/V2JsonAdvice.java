package com.kryukow.HotelReservationService.web.v2.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import java.io.*;
import java.lang.reflect.Type;

/** Strict reader applies only to v2, without changing legacy Jackson coercion/DTO compatibility. */
@ControllerAdvice(basePackages="com.kryukow.HotelReservationService.web.v2")
public class V2JsonAdvice extends RequestBodyAdviceAdapter {
    private final ObjectMapper strict;
    public V2JsonAdvice(ObjectMapper mapper) {
        strict = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        strict.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        strict.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                .setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
    }
    @Override public boolean supports(MethodParameter p, Type t, Class<? extends HttpMessageConverter<?>> c) { return true; }
    @Override public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter p, Type t,
                                                    Class<? extends HttpMessageConverter<?>> c) throws IOException {
        int limit = p.getDeclaringClass().getPackageName().contains(".v2.admin") ? 131072 : 16384;
        byte[] bytes = input.getBody().readNBytes(limit + 1);
        if (bytes.length > limit) throw new V2ApiException(413,"PAYLOAD_TOO_LARGE","Слишком большой запрос.");
        try { if (strict.readerFor(strict.constructType(t)).readValue(bytes) == null)
            throw new V2ApiException(400,"MALFORMED_JSON","Требуется JSON объект."); }
        catch (com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException ex) {
            throw new V2ApiException(400,"VALIDATION_FAILED","Неизвестное поле запроса.")
                    .withFieldError("$","UNKNOWN_FIELD","Удалите неизвестное поле.");
        } catch (IOException | IllegalArgumentException ex) {
            throw new V2ApiException(400,"MALFORMED_JSON","Некорректный JSON.");
        }
        return new HttpInputMessage() {
            public InputStream getBody() { return new ByteArrayInputStream(bytes); }
            public HttpHeaders getHeaders() { return input.getHeaders(); }
        };
    }
}
