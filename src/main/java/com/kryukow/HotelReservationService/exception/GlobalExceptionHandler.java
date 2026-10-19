package com.kryukow.HotelReservationService.exception;

import com.kryukow.HotelReservationService.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 404 - Сущность не найдена в БД
     */
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEntityNotFound(
            EntityNotFoundException ex,
            WebRequest request
    ) {
        log.error("404 - Entity not found: {}", ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );

        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    /**
     * 400 - Ошибки валидации (@Valid)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationExceptions(
            MethodArgumentNotValidException ex,
            WebRequest request
    ) {
        String errorMessage = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));

        log.error("400 - Validation error: {}", errorMessage);

        ErrorResponse error = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                "Validation failed: " + errorMessage,
                request.getDescription(false),
                LocalDateTime.now()
        );

        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /**
     * 400 - Неправильный тип параметра (например, id должен быть числом)
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            WebRequest request
    ) {
        log.warn("400 - Type mismatch: parameter={}", ex.getName());

        ErrorResponse error = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                "Invalid parameter: " + ex.getName() + " should be of type " + (ex.getRequiredType() == null ? "valid value" : ex.getRequiredType().getSimpleName()),
                request.getDescription(false),
                LocalDateTime.now()
        );

        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /**
     * 400 - Некорректный JSON (не читается)
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(
            HttpMessageNotReadableException ex,
            WebRequest request
    ) {
        log.warn("400 - Malformed JSON: path={}, type={}", request.getDescription(false), ex.getClass().getSimpleName());

        ErrorResponse error = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                "Malformed JSON request",
                request.getDescription(false),
                LocalDateTime.now()
        );

        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    /**
     * 400 - IllegalArgumentException (некорректные аргументы)
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex,
            WebRequest request
    ) {
        log.warn("400 - Illegal argument: path={}, type={}", request.getDescription(false), ex.getClass().getSimpleName());

        ErrorResponse error = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                "Invalid request parameters",
                request.getDescription(false),
                LocalDateTime.now()
        );

        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler({ResourceConflictException.class,
            org.springframework.dao.DataIntegrityViolationException.class,
            org.springframework.dao.OptimisticLockingFailureException.class,
            jakarta.persistence.OptimisticLockException.class,
            org.springframework.dao.PessimisticLockingFailureException.class,
            jakarta.persistence.PessimisticLockException.class,
            jakarta.persistence.LockTimeoutException.class})
    public ResponseEntity<ErrorResponse> handleConflict(Exception ex, WebRequest request) {
        // Never include SQL, constraint details or persistence internals in the response.
        return error(HttpStatus.CONFLICT, "Request conflicts with the current resource state", request);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(Exception ex, WebRequest request) {
        return error(HttpStatus.FORBIDDEN, "Access denied", request);
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleUnauthenticated(Exception ex, WebRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "Authentication required", request);
    }

    @ExceptionHandler({jakarta.validation.ConstraintViolationException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.validation.BindException.class})
    public ResponseEntity<ErrorResponse> handleInvalidParameters(Exception ex, WebRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Invalid request parameters", request);
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String message, WebRequest request) {
        return ResponseEntity.status(status).body(new ErrorResponse(status.value(), status.getReasonPhrase(),
                message, request.getDescription(false), LocalDateTime.now()));
    }

    /**
     * 500 - Все остальные необработанные ошибки
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex,
            WebRequest request
    ) {
        log.error("500 - Internal server error: path={}, type={}", request.getDescription(false), ex.getClass().getName());

        ErrorResponse error = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                "Internal server error. Please try again later.",
                request.getDescription(false),
                LocalDateTime.now()
        );

        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}