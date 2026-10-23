package com.kryukow.HotelReservationService.web.v2.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.List;
import java.util.Map;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.kryukow.HotelReservationService.web.v2")
public class V2ExceptionAdvice {
    private ResponseEntity<?> error(HttpServletRequest request, int status, String code, String message,
                                    List<?> fields, Map<String, ?> details) {
        var builder = ResponseEntity.status(status);
        if (code.equals("OPERATION_IN_PROGRESS")) builder.header("Retry-After", "2");
        if (status == 429 && details.get("retryAfterSeconds") instanceof Number seconds)
            builder.header("Retry-After", seconds.toString());
        // V2HttpFilter sets security/cache/trace headers once; adding again duplicates HTTP values.
        return builder.body(V2Errors.body(request, status, code, message, fields, details));
    }
    @ExceptionHandler(V2ApiException.class)
    public ResponseEntity<?> domain(V2ApiException ex, HttpServletRequest req) {
        return error(req, ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getFieldErrors(), ex.getDetails());
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        var fields = ex.getBindingResult().getFieldErrors().stream().map(field -> Map.of(
                "field", field.getField(), "code", ("NotNull".equals(field.getCode()) || "NotBlank".equals(field.getCode())) ? "REQUIRED" :
                        ("Email".equals(field.getCode()) || "Pattern".equals(field.getCode())) ? "INVALID_FORMAT" :
                        "BcryptPassword".equals(field.getCode()) ? "UTF8_TOO_LONG" : "OUT_OF_RANGE",
                "message", "Проверьте значение поля.")).toList();
        return error(req, 400, "VALIDATION_FAILED", "Проверьте поля запроса.", fields, Map.of());
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> json(Exception ex, HttpServletRequest req) {
        return error(req, 400, "MALFORMED_JSON", "Некорректный JSON.", List.of(), Map.of());
    }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class})
    public ResponseEntity<?> parameters(Exception ex, HttpServletRequest req) {
        return error(req, 400, "VALIDATION_FAILED", "Проверьте параметры запроса.", List.of(), Map.of());
    }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> denied(Exception ex, HttpServletRequest req) {
        return error(req, 403, "FORBIDDEN", "Доступ запрещён.", List.of(), Map.of());
    }
    @ExceptionHandler({DataIntegrityViolationException.class, org.springframework.dao.OptimisticLockingFailureException.class})
    public ResponseEntity<?> conflict(Exception ex, HttpServletRequest req) {
        return error(req, 409, "TERMS_CHANGED", "Состояние объекта изменилось.", List.of(), Map.of());
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<?> unavailable(Exception ex, HttpServletRequest req) {
        return error(req,503,"SERVICE_UNAVAILABLE","Сервис временно недоступен.",List.of(),Map.of());
    }
    @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<?> missing(Exception ex,HttpServletRequest req) {
        return error(req,404,"NOT_FOUND","Объект не найден.",List.of(),Map.of());
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<?> uploadSize(HttpServletRequest req) {
        return error(req,413,"PAYLOAD_TOO_LARGE","Файл слишком большой.",List.of(),Map.of());
    }
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    public ResponseEntity<?> missingUpload(HttpServletRequest req) {
        return error(req,400,"VALIDATION_FAILED","Выберите файл изображения.",List.of(),Map.of());
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unknown(Exception ex, HttpServletRequest req) {
        // Never log throwable text: Jackson/JDBC diagnostics may contain credentials or request fragments.
        return error(req, 500, "INTERNAL_ERROR", "Не удалось выполнить запрос.", List.of(), Map.of());
    }
}
