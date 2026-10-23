package com.kryukow.HotelReservationService.web.v2.catalog;

import lombok.RequiredArgsConstructor;
import com.kryukow.HotelReservationService.web.v2.common.V2Errors;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import java.util.Map;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.kryukow.HotelReservationService.web.v2.catalog.CatalogDtos.*;

@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class CatalogController {
    private final CatalogService catalog;

    @GetMapping("/cities")
    public CityList cities(@RequestParam MultiValueMap<String, String> query) {
        CatalogQuery.parse(query, CatalogQuery.Scope.CITIES);
        return catalog.cities();
    }

    @GetMapping("/hotels")
    public HotelPage hotels(@RequestParam MultiValueMap<String, String> query) {
        return catalog.hotels(CatalogQuery.parse(query, CatalogQuery.Scope.HOTELS));
    }

    @GetMapping("/hotels/{slug}")
    public HotelDetail hotel(@PathVariable String slug, @RequestParam MultiValueMap<String, String> query) {
        CatalogQuery.parse(query, CatalogQuery.Scope.HOTEL);
        return catalog.hotel(slug);
    }

    @GetMapping("/hotels/{slug}/rooms")
    public RoomPage rooms(@PathVariable String slug, @RequestParam MultiValueMap<String, String> query) {
        return catalog.rooms(slug, CatalogQuery.parse(query, CatalogQuery.Scope.ROOMS));
    }

    @GetMapping("/rooms/{roomId}")
    public RoomView room(@PathVariable String roomId, @RequestParam MultiValueMap<String, String> query) {
        return catalog.room(CatalogQuery.roomId(roomId), CatalogQuery.parse(query, CatalogQuery.Scope.ROOM));
    }
    @ExceptionHandler(CatalogValidationException.class)
    public ResponseEntity<?> validation(CatalogValidationException ex, HttpServletRequest request) {
        return ResponseEntity.status(400).header("Cache-Control", "private, no-store").header("Vary", "Cookie")
                .header("X-Trace-Id", V2Errors.trace(request))
                .body(V2Errors.body(request, 400, ex.getCode(), ex.getMessage(), ex.fields(), Map.of()));
    }

}
