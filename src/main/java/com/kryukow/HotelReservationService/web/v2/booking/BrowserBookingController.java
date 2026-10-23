package com.kryukow.HotelReservationService.web.v2.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.kryukow.HotelReservationService.web.v2.common.V2ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v2")
@ConditionalOnProperty(name="app.web.v2.enabled",havingValue="true")
public class BrowserBookingController {
    private final BrowserBookingService service;
    public BrowserBookingController(BrowserBookingService service){this.service=service;}

    @PostMapping("/bookings/quote")
    public ResponseEntity<Map<String,Object>> quote(@RequestBody JsonNode body,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        BrowserBookingService.Reply reply=service.quote(BookingSelection.parse(body,false),BookingSelection.key(key));
        return ResponseEntity.status(201).header("Idempotency-Replayed",Boolean.toString(reply.replayed())).body(reply.body());
    }

    @PostMapping("/bookings")
    public ResponseEntity<Map<String,Object>> create(@RequestBody(required=false) JsonNode body,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        BookingSelection s=body==null?null:BookingSelection.parse(body,true);
        String token=null;
        if(body!=null){JsonNode value=body.get("quoteToken");if(value==null || !value.isTextual() || !value.asText().matches("[A-Za-z0-9_-]{43}")) throw invalid();token=value.asText();}
        BrowserBookingService.Reply reply=service.create(s,token,BookingSelection.key(key));
        return ResponseEntity.status(201).header("Location",(String)reply.body().get("bookingUrl"))
                .header("Idempotency-Replayed",Boolean.toString(reply.replayed())).body(reply.body());
    }
    @GetMapping("/me/booking-operations/result")
    public Map<String,Object> result(@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestParam MultiValueMap<String,String> params){noParams(params);return service.result(BookingSelection.key(key));}
    @PostMapping("/me/booking-operations/fence")
    public Map<String,Object> fence(@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody(required=false) JsonNode body){noBody(body);return service.fence(BookingSelection.key(key));}
    @GetMapping("/me/bookings")
    public Map<String,Object> own(@RequestParam MultiValueMap<String,String> params){return page(params,false);}
    @GetMapping("/admin/bookings")
    public Map<String,Object> admin(@RequestParam MultiValueMap<String,String> params){return page(params,true);}
    @GetMapping("/me/bookings/{id}")
    public Map<String,Object> getOwn(@PathVariable String id,@RequestParam MultiValueMap<String,String> params){noParams(params);return service.get(id,false);}
    @GetMapping("/admin/bookings/{id}")
    public Map<String,Object> getAdmin(@PathVariable String id,@RequestParam MultiValueMap<String,String> params){noParams(params);return service.get(id,true);}
    @PostMapping("/me/bookings/{id}/cancel")
    public Map<String,Object> cancel(@PathVariable String id,@RequestBody(required=false) JsonNode body){noBody(body);return service.cancel(id);}
    private Map<String,Object> page(MultiValueMap<String,String> params,boolean admin){
        Set<String> allowed=Set.of("page","size","status","sort");
        if(params.entrySet().stream().anyMatch(e->!allowed.contains(e.getKey()) || e.getValue().size()!=1 || e.getValue().getFirst().isBlank()))throw invalid();
        try {
            String p=params.getFirst("page"),s=params.getFirst("size");
            if(p!=null && !p.matches("[0-9]{1,10}") || s!=null && !s.matches("[0-9]{1,2}"))throw invalid();
            return service.list(p==null?0:Integer.parseInt(p),s==null?20:Integer.parseInt(s),params.getFirst("status"),params.getFirst("sort")==null?"createdDesc":params.getFirst("sort"),admin);
        }catch(NumberFormatException e){throw invalid();}
    }
    private void noParams(MultiValueMap<String,String> p){if(!p.isEmpty())throw invalid();}
    private void noBody(JsonNode body){if(body!=null)throw invalid();}
    private V2ApiException invalid(){return new V2ApiException(400,"VALIDATION_FAILED","Проверьте поля запроса");}
}
