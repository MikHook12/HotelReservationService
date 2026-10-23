package com.kryukow.HotelReservationService.web.v2.media;

import com.kryukow.HotelReservationService.web.v2.common.V2Errors;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnProperty(name = "app.web.v2.enabled", havingValue = "true")
public class CatalogMediaController {
    private final CatalogMediaStore store;
    public CatalogMediaController(CatalogMediaStore store) { this.store = store; }

    @PostMapping(value = "/api/v2/admin/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> upload(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(201).body(Map.of("url", store.upload(file)));
    }

    @GetMapping("/api/v2/media/{filename}")
    public ResponseEntity<byte[]> image(@PathVariable String filename) {
        var image = store.read(filename);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "public, max-age=86400, immutable")
                .body(image.bytes());
    }

    @ExceptionHandler({MaxUploadSizeExceededException.class, MissingServletRequestPartException.class})
    public ResponseEntity<?> invalidUpload(Exception exception, HttpServletRequest request) {
        int status = exception instanceof MaxUploadSizeExceededException ? 413 : 400;
        return ResponseEntity.status(status).body(V2Errors.body(request, status, "VALIDATION_FAILED",
                "Передайте фото JPEG или PNG до 5 МБ в поле file.", List.of(), Map.of()));
    }
}
