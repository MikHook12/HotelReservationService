package com.kryukow.HotelReservationService.web.v2.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kryukow.HotelReservationService.integration.BaseIntegrationTest;
import com.kryukow.HotelReservationService.model.User;
import com.kryukow.HotelReservationService.repository.UserRepository;
import com.kryukow.HotelReservationService.web.v2.booking.BookingSelection;
import com.kryukow.HotelReservationService.web.v2.booking.BrowserBookingService;
import com.kryukow.HotelReservationService.web.v2.common.V2ApiException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.util.*;
import static com.kryukow.HotelReservationService.web.v2.admin.AdminCatalogDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@TestPropertySource(properties={"app.web.v2.enabled=true","app.web.v2.quote-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class AdminCatalogIntegrationTest extends BaseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired AdminCatalogService catalog;
    @Autowired BrowserBookingService bookings;
    @Autowired UserRepository users;
    @AfterEach void clear() {SecurityContextHolder.clearContext();}
    static RequestPostProcessor https() {return r->{r.setScheme("https");r.setSecure(true);r.setServerPort(443);return r;};}
    record Session(Cookie cookie,String token) {}
    Session csrf(Cookie cookie) throws Exception {
        var req=get("/api/v2/auth/csrf").with(https());if(cookie!=null)req.cookie(cookie);
        var response=mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse();
        Cookie updated=response.getCookie("__Host-hotel-session");
        return new Session(updated==null?cookie:updated,json.readTree(response.getContentAsString()).get("token").asText());
    }
    Session login(String role) throws Exception {
        String name="admin-test-"+UUID.randomUUID();
        jdbc.update("INSERT INTO users(name,password,email,role) VALUES(?,?,?,?)",name,encoder.encode("password"),name+"@example.invalid",role);
        var start=csrf(null);
        var response=mvc.perform(post("/api/v2/auth/login").with(https()).cookie(start.cookie()).header("X-CSRF-TOKEN",start.token())
                .contentType("application/json").content(json.writeValueAsString(Map.of("username",name,"password","password"))))
                .andExpect(status().isOk()).andReturn().getResponse();
        return csrf(response.getCookie("__Host-hotel-session"));
    }
    HotelInput hotelInput(Long version,String slug,String name,boolean published) {
        return new HotelInput(version,slug,name,"Тестовый город","test-city","Europe/Moscow","Вымышленный адрес","Описание",0.0,published,List.of());
    }
    HotelView hotel(boolean published) {
        String slug="admin-"+UUID.randomUUID();return catalog.createHotel(hotelInput(null,slug,slug,published));
    }
    RoomInput roomInput(Long version,String price,boolean published) {return new RoomInput(version,"Стандарт",1,2,price,"Описание комнаты",published,List.of());}
    @Test void adminUsesRealJdbcSessionRoleAndCsrf() throws Exception {
        mvc.perform(get("/api/v2/admin/hotels").with(https())).andExpect(status().isUnauthorized());
        var user=login("USER");
        mvc.perform(get("/api/v2/admin/hotels").with(https()).cookie(user.cookie())).andExpect(status().isForbidden());
        var admin=login("ADMIN");
        mvc.perform(get("/api/v2/admin/hotels").with(https()).cookie(admin.cookie())).andExpect(status().isOk());
        var input=hotelInput(null,"secure-"+UUID.randomUUID(),"Безопасный отель "+UUID.randomUUID(),false);
        mvc.perform(post("/api/v2/admin/hotels").with(https()).cookie(admin.cookie()).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v2/admin/hotels").with(https()).cookie(admin.cookie()).header("X-CSRF-TOKEN",admin.token()).contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(0));
    }
    @Test void publishAndArchiveControlPublicVisibilityWithoutDeleting() throws Exception {
        var h=hotel(false);
        mvc.perform(get("/api/v2/hotels/"+h.slug()).with(https())).andExpect(status().isNotFound());
        var published=catalog.updateHotel(Long.parseLong(h.id()),hotelInput(h.version(),h.slug(),h.name(),true));
        mvc.perform(get("/api/v2/hotels/"+h.slug()).with(https())).andExpect(status().isOk());
        catalog.updateHotel(Long.parseLong(h.id()),hotelInput(published.version(),h.slug(),h.name(),false));
        mvc.perform(get("/api/v2/hotels/"+h.slug()).with(https())).andExpect(status().isNotFound());
        assertThat(catalog.hotel(Long.parseLong(h.id())).published()).isFalse();
    }
    @Test void staleHotelAndRoomVersionsCannotOverwriteNewerEdits() {
        var h=hotel(true);var room=catalog.createRoom(Long.parseLong(h.id()),roomInput(null,"5000.00",true));
        catalog.updateHotel(Long.parseLong(h.id()),hotelInput(h.version(),h.slug(),"Обновлённый "+h.name(),true));
        assertCode(()->catalog.updateHotel(Long.parseLong(h.id()),hotelInput(h.version(),h.slug(),h.name(),true)),"RESOURCE_CONFLICT");
        var newer=catalog.updateRoom(Long.parseLong(room.id()),roomInput(room.version(),"6000.00",true));
        assertThat(newer.version()).isGreaterThan(room.version());
        assertCode(()->catalog.updateRoom(Long.parseLong(room.id()),roomInput(room.version(),"5000.00",true)),"RESOURCE_CONFLICT");
        assertThat(catalog.rooms(Long.parseLong(h.id()),0,20).items().getFirst().nightlyPrice()).isEqualTo("6000.00");
    }
    @Test void validationRejectsPrecisionUnsafePathsDuplicateNumbersAndChangedSlug() {
        var h=hotel(true);long hotelId=Long.parseLong(h.id());
        assertCode(()->catalog.createRoom(hotelId,roomInput(null,"1.001",true)),"VALIDATION_FAILED");
        catalog.createRoom(hotelId,roomInput(null,"1.00",true));
        assertCode(()->catalog.createRoom(hotelId,roomInput(null,"2.00",true)),"RESOURCE_CONFLICT");
        assertCode(()->catalog.updateHotel(hotelId,hotelInput(h.version(),"new-slug",h.name(),true)),"VALIDATION_FAILED");
        assertThatThrownBy(()->catalog.createRoom(hotelId,new RoomInput(null,"Unsafe",2,2,"1.00","",true,List.of(new Photo("https://evil.invalid/a.jpg","a","a","a")))))
                .isInstanceOf(V2ApiException.class);
        var bad=hotelInput(null,"zone-"+UUID.randomUUID(),"Badzone",true);
        assertCode(()->catalog.createHotel(new HotelInput(null,bad.slug(),bad.name(),bad.city(),bad.cityCode(),"UTC+03:00",bad.address(),bad.description(),0.0,true,List.of())),"VALIDATION_FAILED");
        assertCode(()->catalog.hotels(0,101),"VALIDATION_FAILED");
        assertThat(catalog.createRoom(hotelId,new RoomInput(null,"Большая комната",3,100,"1.00","",true,List.of())).capacity()).isEqualTo(100);
        assertCode(()->catalog.createRoom(hotelId,new RoomInput(null,"Слишком большая",4,101,"1.00","",true,List.of())),"VALIDATION_FAILED");
    }
    @Test void adminEditsPreserveExistingBookingSnapshotsAndInvalidateOldQuote() {
        var h=hotel(true);var room=catalog.createRoom(Long.parseLong(h.id()),roomInput(null,"5000.00",true));
        String name="booking-admin-"+UUID.randomUUID();
        Long uid=jdbc.queryForObject("INSERT INTO users(name,password,email,role) VALUES(?,?,?,'USER') RETURNING id",Long.class,name,encoder.encode("password"),name+"@example.invalid");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name,"",List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        LocalDate date=LocalDate.now(java.time.ZoneId.of("Europe/Moscow")).plusDays(10);
        var stay=new BookingSelection(Long.parseLong(room.id()),date,date.plusDays(2),2);
        var quote=bookings.quote(stay,UUID.randomUUID());
        var created=bookings.create(stay,(String)quote.body().get("quoteToken"),UUID.randomUUID());
        var otherStay=new BookingSelection(Long.parseLong(room.id()),date.plusDays(3),date.plusDays(5),2);
        var oldQuote=bookings.quote(otherStay,UUID.randomUUID());
        catalog.updateRoom(Long.parseLong(room.id()),roomInput(room.version(),"8000.00",true));
        assertCode(()->bookings.create(otherStay,(String)oldQuote.body().get("quoteToken"),UUID.randomUUID()),"PRICE_CHANGED");
        var receipt=bookings.get((String)created.body().get("bookingId"),false);
        assertThat(((Map<?,?>)receipt.get("pricing")).get("nightlyPrice")).isEqualTo("5000.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE user_id=?",Long.class,uid)).isEqualTo(1L);
    }
    static void assertCode(Runnable action,String code) {assertThatThrownBy(action::run).isInstanceOfSatisfying(V2ApiException.class,e->assertThat(e.getCode()).isEqualTo(code));}
}
