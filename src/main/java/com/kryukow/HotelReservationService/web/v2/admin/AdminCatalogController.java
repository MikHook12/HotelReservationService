package com.kryukow.HotelReservationService.web.v2.admin;

import com.kryukow.HotelReservationService.web.v2.common.V2Errors;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.kryukow.HotelReservationService.web.v2.admin.AdminCatalogDtos.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/admin")
@ConditionalOnProperty(name="app.web.v2.enabled", havingValue="true")
public class AdminCatalogController {
    private final AdminCatalogService service;
    @GetMapping("/hotels") public Page<HotelView> hotels(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return service.hotels(page,size);}
    @GetMapping("/hotels/{id}") public HotelView hotel(@PathVariable long id) {return service.hotel(id);}
    @PostMapping("/hotels") public ResponseEntity<HotelView> create(@RequestBody HotelInput input) {return ResponseEntity.status(201).body(service.createHotel(input));}
    @PostMapping("/hotels/{id}") public HotelView update(@PathVariable long id,@RequestBody HotelInput input) {return service.updateHotel(id,input);}
    @GetMapping("/hotels/{id}/rooms") public Page<RoomView> rooms(@PathVariable long id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return service.rooms(id,page,size);}
    @PostMapping("/hotels/{id}/rooms") public ResponseEntity<RoomView> createRoom(@PathVariable long id,@RequestBody RoomInput input) {return ResponseEntity.status(201).body(service.createRoom(id,input));}
    @PostMapping("/rooms/{id}") public RoomView updateRoom(@PathVariable long id,@RequestBody RoomInput input) {return service.updateRoom(id,input);}
    @ExceptionHandler({DataIntegrityViolationException.class,OptimisticLockingFailureException.class})
    public ResponseEntity<?> conflict(Exception exception,HttpServletRequest request) {
        return ResponseEntity.status(409).body(V2Errors.body(request,409,"RESOURCE_CONFLICT","Данные изменились или уже существуют. Обновите страницу.",List.of(),Map.of()));
    }
}
