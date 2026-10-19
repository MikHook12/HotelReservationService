package com.kryukow.HotelReservationService.security;

import com.kryukow.HotelReservationService.config.SecurityConfig;
import com.kryukow.HotelReservationService.controller.*;
import com.kryukow.HotelReservationService.exception.EntityNotFoundException;
import com.kryukow.HotelReservationService.model.*;
import com.kryukow.HotelReservationService.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({UserRestController.class, BookingRestController.class, AuthRestController.class})
@Import({SecurityConfig.class, CurrentUser.class, BookingService.class})
class ApiSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @MockitoBean CustomUserDetailsService detailsService;
    @MockitoBean UserService userService;
    @MockitoBean com.kryukow.HotelReservationService.repository.BookingRepository bookings;
    @MockitoBean com.kryukow.HotelReservationService.repository.RoomRepository rooms;
    @MockitoBean com.kryukow.HotelReservationService.repository.UserRepository users;
    @MockitoBean DomainEventPublisher events;

    private static final String USER_JSON = """
            {"name":"alice","email":"alice@example.com","password":"Password123!"}
            """;

    @BeforeEach void setUp() {
        when(detailsService.loadUserByUsername("alice")).thenReturn(details(7L, Role.USER));
        when(detailsService.loadUserByUsername("admin")).thenReturn(details(1L, Role.ADMIN));
    }

    private CustomUserDetails details(Long id, Role role) {
        User user = new User(); user.setId(id); user.setRole(role);
        user.setName(role == Role.ADMIN ? "admin" : "alice");
        user.setPassword(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("secret"));
        return new CustomUserDetails(user);
    }

    @Test void unauthenticatedReadsAreUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/bookings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/7")).andExpect(status().isUnauthorized());
    }

    @Test void userCannotListSearchManageOthersOrPromoteSelf() throws Exception {
        mvc.perform(get("/api/v1/users").with(httpBasic("alice", "secret"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/users/by-name").param("name", "alice").with(httpBasic("alice", "secret")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/users/8").with(httpBasic("alice", "secret"))).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/users/8").contentType("application/json").content(USER_JSON)
                .with(httpBasic("alice", "secret"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/users").contentType("application/json").content(USER_JSON)
                .with(httpBasic("alice", "secret"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/users/7").with(httpBasic("alice", "secret"))).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/users/7/role").param("role", "ADMIN").with(httpBasic("alice", "secret")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(userService);
    }

    @Test void userCanReadAndUpdateOwnProfile() throws Exception {
        mvc.perform(get("/api/v1/users/7").with(httpBasic("alice", "secret"))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/users/7").contentType("application/json").content(USER_JSON)
                .with(httpBasic("alice", "secret"))).andExpect(status().isOk());
        verify(userService).getById(7L); verify(userService).update(eq(7L), any());
    }

    @Test void adminCanManageUsersAndRoles() throws Exception {
        mvc.perform(post("/api/v1/users").contentType("application/json").content(USER_JSON)
                .with(httpBasic("admin", "secret"))).andExpect(status().isCreated());
        mvc.perform(put("/api/v1/users/7").contentType("application/json").content(USER_JSON)
                .with(httpBasic("admin", "secret"))).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/users/7").with(httpBasic("admin", "secret"))).andExpect(status().isNoContent());
        mvc.perform(patch("/api/v1/users/7/role").param("role", "ADMIN").with(httpBasic("admin", "secret")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/users").with(httpBasic("admin", "secret"))).andExpect(status().isOk());
        verify(userService).changeRole(7L, Role.ADMIN);
    }

    @Test void browserBasicMutationRequiresCsrfButRestBasicJsonDoesNot() throws Exception {
        mvc.perform(put("/api/v1/users/7").contentType("application/json").content(USER_JSON)
                .header("Origin", "https://evil.example").with(httpBasic("alice", "secret")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/users/7").contentType("application/json").content(USER_JSON)
                .header("Sec-Fetch-Site", "same-origin").with(httpBasic("alice", "secret")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/users/7").contentType("application/json").content(USER_JSON)
                .header("Origin", "https://hotel.example").with(httpBasic("alice", "secret")).with(realCsrf()))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/users/7").contentType("application/json").content(USER_JSON)
                .with(httpBasic("alice", "secret"))).andExpect(status().isOk());
    }

    @Test void simpleContentTypesAndRefererCannotUseRestExemption() throws Exception {
        for (String type : new String[]{"text/plain", "application/x-www-form-urlencoded", "multipart/form-data"}) {
            mvc.perform(post("/api/v1/users").contentType(type).content(USER_JSON)
                    .with(httpBasic("admin", "secret"))).andExpect(status().isForbidden());
        }
        mvc.perform(delete("/api/v1/users/7").header("Referer", "https://evil.example/")
                .with(httpBasic("admin", "secret"))).andExpect(status().isForbidden());
        verifyNoInteractions(userService);
    }

    private String bookingJson(Long claimedOwner) {
        return "{\"roomId\":1,\"checkInDate\":\"" + java.time.LocalDate.now().plusDays(5)
                + "\",\"checkOutDate\":\"" + java.time.LocalDate.now().plusDays(8) + "\""
                + (claimedOwner == null ? "" : ",\"userId\":" + claimedOwner) + "}";
    }

    @Test void bookingOwnerComesFromRealBasicAuthentication() throws Exception {
        Room room = new Room(); room.setId(1L);
        User owner = new User(); owner.setId(7L);
        when(rooms.findByIdForUpdate(1L)).thenReturn(java.util.Optional.of(room));
        when(users.findById(7L)).thenReturn(java.util.Optional.of(owner));
        when(bookings.save(any())).thenAnswer(invocation -> {
            Booking booking = invocation.getArgument(0); booking.setId(11L); return booking;
        });
        mvc.perform(post("/api/v1/bookings").contentType("application/json").content(bookingJson(null))
                .with(httpBasic("alice", "secret"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(7));
        verify(events).publishBookingCreated(eq(7L), eq(1L), any(), any());
    }

    @Test void forgedBookingOwnerIsForbiddenBeforeDatabaseAccess() throws Exception {
        mvc.perform(post("/api/v1/bookings").contentType("application/json").content(bookingJson(99L))
                .with(httpBasic("alice", "secret"))).andExpect(status().isForbidden());
        verifyNoInteractions(bookings, rooms, users, events);
    }

    @Test void overlappingBookingIsConflict() throws Exception {
        Room room = new Room(); room.setId(1L);
        User owner = new User(); owner.setId(7L);
        when(rooms.findByIdForUpdate(1L)).thenReturn(java.util.Optional.of(room));
        when(users.findById(7L)).thenReturn(java.util.Optional.of(owner));
        when(bookings.existsOverlappingBooking(anyLong(), any(), any())).thenReturn(true);
        mvc.perform(post("/api/v1/bookings").contentType("application/json").content(bookingJson(null))
                .with(httpBasic("alice", "secret"))).andExpect(status().isConflict());
        verify(bookings, never()).save(any()); verifyNoInteractions(events);
    }

    @Test void unexpectedIllegalStateRemainsServerError() throws Exception {
        when(userService.getById(7L)).thenThrow(new IllegalStateException("sensitive implementation detail"));
        mvc.perform(get("/api/v1/users/7").with(httpBasic("alice", "secret")))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive"))));
    }

    @Test void restRegistrationWorksWithoutCsrfAndBrowserRegistrationRequiresToken() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(USER_JSON))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(USER_JSON)
                .header("Origin", "https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(USER_JSON)
                .header("Origin", "https://hotel.example").with(realCsrf())).andExpect(status().isCreated());
    }

    @Test void oversizedUtf8PasswordIsRejectedWithoutEchoingIt() throws Exception {
        String password = "я".repeat(37);
        String json = USER_JSON.replace("Password123!", password);
        mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(json))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(password))));
        verifyNoInteractions(userService);
    }

    // Use the real cookie repository instead of SecurityMockMvc csrf(), which replaces
    // the CsrfFilter repository on the shared filter chain and contaminates later tests.
    private org.springframework.test.web.servlet.request.RequestPostProcessor realCsrf() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN")).andReturn().getResponse();
        var cookie = response.getCookie("XSRF-TOKEN");
        var body = objectMapper.readTree(response.getContentAsString());
        String headerName = body.get("headerName").asText();
        String token = body.get("token").asText();
        return request -> {
            request.setCookies(cookie);
            request.addHeader(headerName, token);
            return request;
        };
    }

    @Test void csrfEndpointReturnsTokenAndCookieWithoutAuthentication() throws Exception {
        mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
                .andExpect(cookie().exists("XSRF-TOKEN"));
        mvc.perform(put("/api/v1/users/7").contentType("application/json").content(USER_JSON)
                .header("Origin", "https://hotel.example").with(httpBasic("alice", "secret")).with(realCsrf()))
                .andExpect(status().isOk());
        verify(userService).update(eq(7L), any());
    }

    @Test void bookingHistoryConflictUses409Handler() throws Exception {
        doThrow(new com.kryukow.HotelReservationService.exception.BookingHistoryConflictException("user"))
                .when(userService).delete(7L);
        mvc.perform(delete("/api/v1/users/7").with(httpBasic("admin", "secret")))
                .andExpect(status().isConflict());
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void malformedJsonAndIllegalArgumentsDoNotEchoPasswords(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String secret = "should-never-appear-in-error";
        mvc.perform(post("/api/v1/auth/register").contentType("application/json")
                .content("{\"password\":\"" + secret + "\",INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(secret))));
        when(userService.getById(7L)).thenThrow(new IllegalArgumentException(secret));
        mvc.perform(get("/api/v1/users/7").with(httpBasic("alice", "secret")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid request parameters"));
        org.assertj.core.api.Assertions.assertThat(output.getAll()).doesNotContain(secret);
    }

    @Test void domainNotFoundConflictsAndValidationHaveCorrectStatus() throws Exception {
        when(userService.getById(7L)).thenThrow(new EntityNotFoundException("User", 7L));
        mvc.perform(get("/api/v1/users/7").with(httpBasic("alice", "secret")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        doThrow(new org.springframework.dao.DataIntegrityViolationException("secret SQL"))
                .when(userService).delete(7L);
        mvc.perform(delete("/api/v1/users/7").with(httpBasic("admin", "secret")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Request conflicts with the current resource state"));
        when(userService.update(eq(7L), any())).thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(User.class, 7L));
        mvc.perform(put("/api/v1/users/7").contentType("application/json").content(USER_JSON)
                .with(httpBasic("alice", "secret"))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/bookings").contentType("application/json").content("{}")
                .with(httpBasic("alice", "secret"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/bookings").param("page", "bad").with(httpBasic("alice", "secret")))
                .andExpect(status().isBadRequest());
    }
}
