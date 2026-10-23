package com.kryukow.HotelReservationService.web.v2.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kryukow.HotelReservationService.integration.BaseIntegrationTest;
import com.kryukow.HotelReservationService.model.*;
import com.kryukow.HotelReservationService.repository.*;
import com.kryukow.HotelReservationService.web.v2.common.V2ApiException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

@TestPropertySource(properties={"app.web.v2.enabled=true","app.web.v2.quote-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","app.web.v2.recovery-interval-ms=3600000"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Import(BrowserBookingIntegrationTest.TimeConfig.class)
class BrowserBookingIntegrationTest extends BaseIntegrationTest {
    @Autowired BrowserBookingService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired RoomRepository rooms;
    @Autowired HotelRepository hotels;
    @Autowired UserRepository users;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    User user;
    Room room;
    BookingSelection stay;
    static final Instant NOW=Instant.parse("2026-10-20T09:00:00Z");
    @BeforeEach void setup(){
        clock.set(NOW);
        String suffix=UUID.randomUUID().toString();
        user=new User();user.setName("browser-"+suffix);user.setEmail(suffix+"@example.test");user.setPassword(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("demoPassword123"));user.setRole(Role.USER);user=users.saveAndFlush(user);
        Hotel hotel=new Hotel();hotel.setName("Demo "+suffix);hotel.setTitle("Demo");hotel.setCity("Тестовый город");hotel.setAddress("Вымышленный адрес");hotel.setDistanceFromCityCenter(0.0);
        hotel.setSlug("demo-"+suffix);hotel.setTimeZone("Europe/Moscow");hotel.setCityCode("test-city");hotel.setDemo(true);hotel.setDescription("Fictional");hotel.setPublished(true);hotel=hotels.saveAndFlush(hotel);
        room=new Room();room.setName("Standard");room.setDescription("Demo room");room.setNumber(1);room.setPrice(new BigDecimal("5000.00"));room.setCapacity(2);room.setHotel(hotel);room.setPublished(true);room=rooms.saveAndFlush(room);
        stay=new BookingSelection(room.getId(),LocalDate.of(2026,11,10),LocalDate.of(2026,11,12),2);
        authenticate(user);
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private void authenticate(User u){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u.getName(),"",List.of(new SimpleGrantedAuthority("ROLE_"+u.getRole().name()))));}
    private BrowserBookingService.Reply quote(){return service.quote(stay,UUID.randomUUID());}
    private String token(BrowserBookingService.Reply q){return (String)q.body().get("quoteToken");}
    private BrowserBookingService.Reply create(){return service.create(stay,token(quote()),UUID.randomUUID());}
    private V2ApiException failure(Runnable call,String code){V2ApiException e=catchThrowableOfType(call::run,V2ApiException.class);assertThat(e).isNotNull();assertThat(e.getCode()).isEqualTo(code);return e;}
    private long ownerCount(){return jdbc.queryForObject("SELECT count(*) FROM bookings WHERE user_id=?",Long.class,user.getId());}
    @SuppressWarnings("unchecked") private Map<String,Object> object(Object value){return (Map<String,Object>)value;}

    @Test void quoteUsesDecimalCalendarNightsAndProtectedStorage(){
        var q=quote().body();
        assertThat(object(q.get("pricing"))).containsEntry("nightlyPrice","5000.00").containsEntry("totalPrice","10000.00").containsEntry("nights",2);
        assertThat(q).containsEntry("holdsRoom",false).containsEntry("cancellationDeadlineAt","2026-11-09T21:00:00Z");
        assertThat(ownerCount()).isZero();
        String raw=jdbc.queryForObject("SELECT quote_cipher FROM browser_booking_quotes WHERE token_hash=?",String.class,QuoteCipher.hash((String)q.get("quoteToken")));
        assertThat(raw).doesNotContain((String)q.get("quoteToken")).doesNotContain("5000.00");
    }
    @Test void sameQuoteKeyReplaysOriginalTokenAndRejectsDifferentStay(){
        UUID key=UUID.randomUUID();var first=service.quote(stay,key);clock.set(NOW.plusSeconds(60));
        var second=service.quote(stay,key);assertThat(second.replayed()).isTrue();assertThat(second.body()).isEqualTo(first.body());
        failure(()->service.quote(new BookingSelection(room.getId(),stay.checkInDate(),stay.checkOutDate(),1),key),"IDEMPOTENCY_CONFLICT");
        clock.set(NOW.plusSeconds(601));failure(()->service.quote(stay,key),"QUOTE_EXPIRED");
    }
    @Test void snapshotSurvivesCatalogPriceAndNameChanges(){
        var receipt=create().body();String id=(String)receipt.get("bookingId");
        jdbc.update("UPDATE rooms SET price=8000,name='Renamed' WHERE id=?",room.getId());
        var booking=service.get(id,false);
        assertThat(object(booking.get("pricing"))).containsEntry("totalPrice","10000.00");
        assertThat(object(booking.get("snapshot"))).containsEntry("roomName","Standard");
        assertThat(booking).containsEntry("origin","V2").containsEntry("guests",2);
    }
    @Test void successfulReplayWorksAfterQuoteExpiryRemovalAndCatalogHiding(){
        var q=quote();UUID key=UUID.randomUUID();var created=service.create(stay,token(q),key);
        clock.set(NOW.plusSeconds(2*86400));service.recover();
        jdbc.update("UPDATE rooms SET published=false,price=9000 WHERE id=?",room.getId());
        var withBody=service.create(stay,token(q),key);var headerOnly=service.create(null,null,key);
        assertThat(withBody.body()).isEqualTo(created.body());assertThat(headerOnly.body()).isEqualTo(created.body());assertThat(headerOnly.replayed()).isTrue();assertThat(ownerCount()).isOne();
    }
    @Test void duplicateKeyDifferentPayloadIsConflict(){
        UUID key=UUID.randomUUID();String t=token(quote());service.create(stay,t,key);
        failure(()->service.create(new BookingSelection(room.getId(),stay.checkInDate(),stay.checkOutDate(),1),t,key),"IDEMPOTENCY_CONFLICT");assertThat(ownerCount()).isOne();
    }
    @Test void priceChangeNeedsNewQuoteAndExplicitNewKey(){
        String t=token(quote());UUID key=UUID.randomUUID();jdbc.update("UPDATE rooms SET price=5500 WHERE id=?",room.getId());
        var e=failure(()->service.create(stay,t,key),"PRICE_CHANGED");assertThat(ownerCount()).isZero();
        var replacement=object(e.getDetails().get("replacementQuote"));
        assertThat(object(replacement.get("pricing"))).containsEntry("totalPrice","11000.00");
        failure(()->service.create(stay,(String)replacement.get("quoteToken"),key),"IDEMPOTENCY_CONFLICT");
        var success=service.create(stay,(String)replacement.get("quoteToken"),UUID.randomUUID());
        assertThat(object(success.body().get("pricing"))).containsEntry("totalPrice","11000.00");
    }
    @Test void expiryDoesNotCreateBookingAndKeyRemainsTerminal(){
        String t=token(quote());UUID key=UUID.randomUUID();clock.set(NOW.plusSeconds(601));
        failure(()->service.create(stay,t,key),"QUOTE_EXPIRED");
        assertThat(service.result(key)).containsEntry("state","FAILED").containsEntry("errorCode","QUOTE_EXPIRED");
        failure(()->service.create(null,null,key),"QUOTE_EXPIRED");assertThat(ownerCount()).isZero();
    }
    @Test void cancelKeepsHistoryReleasesDatesAndDoesNotRewriteReceipt(){
        String t=token(quote());UUID key=UUID.randomUUID();var first=service.create(stay,t,key).body();String id=(String)first.get("bookingId");
        assertThat(service.cancel(id)).containsEntry("status","CANCELLED");
        String cancelledAt=(String)service.get(id,false).get("cancelledAt");
        assertThat(service.cancel(id)).containsEntry("cancelledAt",cancelledAt);
        assertThat(service.create(null,null,key).body()).containsEntry("statusAtCreation","CONFIRMED");
        assertThat(service.create(null,null,key).body()).isEqualTo(first);
        assertThat(create().body().get("bookingId")).isNotEqualTo(id);assertThat(ownerCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type='BOOKING_CANCELLED' AND payload LIKE ?",Long.class,"%\"bookingId\":"+id+"%" )).isOne();
    }
    @Test void cancellationClosesAtSavedInstantAndRepeatCancelledStillSucceeds(){
        String id=(String)create().body().get("bookingId");clock.set(Instant.parse("2026-11-09T21:00:00Z"));
        failure(()->service.cancel(id),"CANCELLATION_CLOSED");
        assertThat(object(service.get(id,false).get("cancellation"))).containsEntry("canCancel",false);
    }
    @Test void legacyUnknownFactsRemainNullAndCannotSelfCancel(){
        Long id=jdbc.queryForObject("INSERT INTO bookings(user_id,room_id,check_in_date,check_out_date) VALUES(?,?,?,?) RETURNING id",Long.class,user.getId(),room.getId(),stay.checkInDate(),stay.checkOutDate());
        var b=service.get(id.toString(),false);assertThat(b).containsEntry("origin","LEGACY").containsEntry("pricing",null).containsEntry("snapshot",null).containsEntry("guests",null);
        failure(()->service.cancel(id.toString()),"LEGACY_TIMEZONE_UNKNOWN");assertThat(object(b.get("cancellation"))).containsEntry("canCancel",false);
    }
    @Test void fencePermanentlyStopsDelayedRequestAndUnknownBodylessDoesNotConsumeKey(){
        UUID key=UUID.randomUUID();String t=token(quote());
        failure(()->service.create(null,null,key),"BODY_REQUIRED");failure(()->service.result(key),"NOT_FOUND");
        assertThat(service.fence(key)).containsEntry("state","FAILED").containsEntry("errorCode","REQUEST_ABORTED");
        failure(()->service.create(stay,t,key),"REQUEST_ABORTED");assertThat(ownerCount()).isZero();
    }
    @Test void fenceReturnsExistingSuccessAndDoesNotCancel(){
        UUID key=UUID.randomUUID();service.create(stay,token(quote()),key);
        var result=service.fence(key);assertThat(result).containsEntry("state","SUCCEEDED");
        assertThat(service.get((String)object(result.get("receipt")).get("bookingId"),false)).containsEntry("status","CONFIRMED");
    }
    @Test void ownerScopeConcealsForeignBookingsAndAdminMeStillOwn(){
        String id=(String)create().body().get("bookingId");User other=new User();other.setName("other-"+UUID.randomUUID());other.setEmail(UUID.randomUUID()+"@example.test");other.setPassword("fixture");other.setRole(Role.ADMIN);other=users.saveAndFlush(other);authenticate(other);
        failure(()->service.get(id,false),"NOT_FOUND");failure(()->service.cancel(id),"NOT_FOUND");assertThat(service.list(0,20,null,"createdDesc",false).get("totalElements")).isEqualTo(0L);
        assertThat(service.get(id,true)).containsEntry("id",id);
    }
    @Test void databaseConstraintRejectsDirectOverlappingWriterButAllowsAdjacent(){
        create();
        assertThatThrownBy(()->jdbc.update("INSERT INTO bookings(user_id,room_id,check_in_date,check_out_date) VALUES(?,?,?,?)",user.getId(),room.getId(),stay.checkInDate(),stay.checkOutDate())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO bookings(user_id,room_id,check_in_date,check_out_date) VALUES(?,?,?,?)",user.getId(),room.getId(),stay.checkOutDate(),stay.checkOutDate().plusDays(1));
        assertThat(ownerCount()).isEqualTo(2);
    }
    @Test void parallelIndependentKeysCannotOverlap() throws Exception {
        String t1=token(quote()),t2=token(quote());User owner=user;CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            List<Future<String>> futures=new ArrayList<>();
            for(String token:List.of(t1,t2)) futures.add(executor.submit(()->{authenticate(owner);start.await();try{service.create(stay,token,UUID.randomUUID());return "SUCCESS";}catch(V2ApiException e){return e.getCode();}finally{SecurityContextHolder.clearContext();}}));
            start.countDown();assertThat(List.of(futures.get(0).get(20,TimeUnit.SECONDS),futures.get(1).get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder("SUCCESS","ROOM_UNAVAILABLE");
        }
        assertThat(ownerCount()).isOne();
    }
    @Test void parallelSameKeyResolvesExactlyOneReceipt() throws Exception {
        String token=token(quote());UUID key=UUID.randomUUID();User owner=user;CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            List<Future<String>> futures=new ArrayList<>();
            for(int i=0;i<2;i++) futures.add(executor.submit(()->{authenticate(owner);start.await();try{return (String)service.create(stay,token,key).body().get("bookingId");}catch(V2ApiException e){return e.getCode();}finally{SecurityContextHolder.clearContext();}}));
            start.countDown();for(var f:futures) assertThat(f.get(20,TimeUnit.SECONDS)).doesNotMatch("ROOM_UNAVAILABLE|IDEMPOTENCY_CONFLICT");
        }
        assertThat(service.result(key)).containsEntry("state","SUCCEEDED");assertThat(ownerCount()).isOne();
    }
    @Test void recoveryDoesNotClaimFailureWhileWorkerHoldsOperationLock() throws Exception {
        UUID key=UUID.randomUUID();Instant old=NOW.minusSeconds(40);
        jdbc.update("INSERT INTO browser_booking_operations(user_id,request_key,payload_hash,state,attempt_token,lease_until,created_at) VALUES(?,?,?,'PROCESSING',?,?,?)",user.getId(),key,"hash",UUID.randomUUID(),Timestamp.from(old),Timestamp.from(old));
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);long owner=user.getId();
        try(var executor=Executors.newSingleThreadExecutor()){
            Future<?> worker=executor.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{jdbc.queryForMap("SELECT * FROM browser_booking_operations WHERE user_id=? AND request_key=? FOR UPDATE",owner,key);locked.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}}));
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            try{assertThat(service.result(key)).containsEntry("state","PROCESSING");}finally{release.countDown();}
            worker.get(10,TimeUnit.SECONDS);
        }
        assertThat(service.result(key)).containsEntry("state","FAILED").containsEntry("errorCode","REQUEST_ABORTED");
    }
    @Test void realSessionCsrfCatalogueQuoteCreateAndCancelOverHttp() throws Exception {
        SecurityContextHolder.clearContext();
        var bootstrap=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v2/auth/csrf").secure(true)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        var anonymous=bootstrap.getResponse().getCookie("__Host-hotel-session");
        assertThat(anonymous).isNotNull();
        String csrf=json.readTree(bootstrap.getResponse().getContentAsString()).get("token").asText();
        var login=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/auth/login").secure(true).cookie(anonymous).header("X-CSRF-TOKEN",csrf)
                .contentType("application/json").content(json.writeValueAsString(Map.of("username",user.getName(),"password","demoPassword123"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        var cookie=login.getResponse().getCookie("__Host-hotel-session");assertThat(cookie).isNotNull();
        var fresh=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v2/auth/csrf").secure(true).cookie(cookie))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        csrf=json.readTree(fresh.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v2/hotels").secure(true).param("city","test-city"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        var quote=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/bookings/quote").secure(true).cookie(cookie)
                .header("X-CSRF-TOKEN",csrf).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(stay.stay())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()).andReturn();
        String token=json.readTree(quote.getResponse().getContentAsString()).get("quoteToken").asText();
        Map<String,Object> body=new HashMap<>(stay.stay());body.put("quoteToken",token);UUID key=UUID.randomUUID();
        var created=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/bookings").secure(true).cookie(cookie)
                .header("X-CSRF-TOKEN",csrf).header("Idempotency-Key",key).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()).andReturn();
        String id=json.readTree(created.getResponse().getContentAsString()).get("bookingId").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/bookings").secure(true).cookie(cookie).header("X-CSRF-TOKEN",csrf).header("Idempotency-Key",key))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Idempotency-Replayed","true"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/me/bookings/"+id+"/cancel").secure(true).cookie(cookie).header("X-CSRF-TOKEN",csrf))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("CANCELLED"));
        assertThat(ownerCount()).isOne();
    }
    @TestConfiguration static class TimeConfig {@Bean @Primary MutableClock time(){return new MutableClock();}}
    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now=new AtomicReference<>(NOW);
        void set(Instant value){now.set(value);}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(now.get(),zone);}
        public Instant instant(){return now.get();}
    }
}
