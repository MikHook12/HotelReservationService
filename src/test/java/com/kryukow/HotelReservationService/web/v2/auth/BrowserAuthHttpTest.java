package com.kryukow.HotelReservationService.web.v2.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kryukow.HotelReservationService.config.SecurityConfig;
import com.kryukow.HotelReservationService.security.CustomUserDetailsService;
import com.kryukow.HotelReservationService.web.v2.common.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.time.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value=BrowserAuthController.class,properties="app.web.v2.enabled=true")
@Import({SecurityConfig.class,V2Errors.class,V2JsonAdvice.class,V2ExceptionAdvice.class,BrowserAuthHttpTest.TimeConfig.class})
class BrowserAuthHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;
    @Autowired MutableClock clock;
    @MockitoBean BrowserIdentityStore identities;
    @MockitoBean BrowserRegistrationService registrations;
    @MockitoBean BrowserRateLimiter limits;
    @MockitoBean CustomUserDetailsService legacy;
    BrowserIdentityStore.Identity identity;
    @TestConfiguration static class TimeConfig { @Bean MutableClock browserClock() { return new MutableClock(); } }
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-20T09:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    @BeforeEach void prepare() {
        clock.now=Instant.parse("2026-10-20T09:00:00Z");
        identity=new BrowserIdentityStore.Identity(7L,"alice",encoder.encode("password"),"alice@example.invalid","USER",0);
        when(identities.byUsername("alice")).thenReturn(java.util.Optional.of(identity));
        when(identities.byId(7L)).thenReturn(java.util.Optional.of(identity));
    }
    static RequestPostProcessor https() {
        return request->{request.setScheme("https");request.setSecure(true);request.setServerPort(443);return request;};
    }
    record Csrf(MockHttpSession session,String token) {}
    Csrf csrf(MockHttpSession session) throws Exception {
        var request=get("/api/v2/auth/csrf").with(https()); if(session!=null) request.session(session);
        var result=mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store")).andReturn();
        return new Csrf((MockHttpSession)result.getRequest().getSession(false),json.readTree(result.getResponse().getContentAsString()).get("token").asText());
    }
    MockHttpSession login() throws Exception {
        var csrf=csrf(null);
        var result=mvc.perform(post("/api/v2/auth/login").with(https()).session(csrf.session()).header("X-CSRF-TOKEN",csrf.token())
                .contentType("application/json").content("{\"username\":\"alice\",\"password\":\"password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value("7")).andReturn();
        return (MockHttpSession)result.getRequest().getSession(false);
    }
    @Test void anonymousCsrfLoginRotatesSessionAndOldTokenThenLogoutRevokes() throws Exception {
        var old=csrf(null); String oldId=old.session().getId();
        var result=mvc.perform(post("/api/v2/auth/login").with(https()).session(old.session()).header("X-CSRF-TOKEN",old.token())
                .contentType("application/json").content("{\"username\":\"alice\",\"password\":\"password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.absoluteExpiresAt").value("2026-10-20T21:00:00Z")).andReturn();
        var session=(MockHttpSession)result.getRequest().getSession(false);
        assertThat(session.getId()).isNotEqualTo(oldId);
        mvc.perform(get("/api/v2/auth/me").with(https()).session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/v2/auth/logout").with(https()).session(session).header("X-CSRF-TOKEN",old.token()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        var current=csrf(session);
        mvc.perform(post("/api/v2/auth/logout").with(https()).session(session).header("X-CSRF-TOKEN",current.token()))
                .andExpect(status().isNoContent()).andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("Max-Age=0")));
        assertThat(session.isInvalid()).isTrue();
    }
    @Test void basicNeverAuthenticatesV2AndCsrfAlwaysRequired() throws Exception {
        mvc.perform(get("/api/v2/auth/me").with(https()).with(httpBasic("alice","password")))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(post("/api/v2/auth/login").with(https()).with(httpBasic("alice","password"))
                .contentType("application/json").content("{\"username\":\"alice\",\"password\":\"password\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(legacy);
    }
    @Test void crossedOriginAndExpiredCsrfCannotMutate() throws Exception {
        var csrf=csrf(null);
        mvc.perform(post("/api/v2/auth/login").with(https()).session(csrf.session()).header("X-CSRF-TOKEN",csrf.token())
                .header("Origin","https://evil.example").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        clock.now=clock.now.plusSeconds(1800);
        mvc.perform(post("/api/v2/auth/login").with(https()).session(csrf.session()).header("X-CSRF-TOKEN",csrf.token())
                .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }
    @Test void identityVersionPasswordRoleOrUserDeletionRevokesSession() throws Exception {
        var session=login();
        when(identities.byId(7L)).thenReturn(java.util.Optional.of(new BrowserIdentityStore.Identity(
                7L,"alice",identity.passwordHash(),identity.email(),"USER",1)));
        mvc.perform(get("/api/v2/auth/me").with(https()).session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
        when(identities.byId(7L)).thenReturn(java.util.Optional.of(identity));
        session=login(); when(identities.byId(7L)).thenReturn(java.util.Optional.empty());
        mvc.perform(get("/api/v2/auth/me").with(https()).session(session)).andExpect(status().isUnauthorized());
    }
    @Test void idleAndAbsoluteExpiryAreServerControlled() throws Exception {
        var session=login(); clock.now=clock.now.plusSeconds(1800);
        mvc.perform(get("/api/v2/auth/me").with(https()).session(session)).andExpect(status().isUnauthorized());
        clock.now=Instant.parse("2026-10-20T09:00:00Z"); session=login();
        clock.now=clock.now.plusSeconds(43200); session.setAttribute(BrowserSessionState.LAST,clock.now.getEpochSecond());
        mvc.perform(get("/api/v2/auth/me").with(https()).session(session)).andExpect(status().isUnauthorized());
    }
    @Test void publicRegistrationIsClosedAndErrorsNeverEchoPassword() throws Exception {
        var csrf=csrf(null); String password="secret-never-echo";
        mvc.perform(post("/api/v2/auth/register").with(https()).session(csrf.session()).header("X-CSRF-TOKEN",csrf.token())
                .contentType("application/json").content(json.writeValueAsString(Map.of("username","alice","password",password,"email","alice@example.invalid"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REGISTRATION_CLOSED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(password))));
        verifyNoInteractions(registrations); verify(limits,never()).registrationAttempt(anyString());
    }
    @Test void jsonRejectsUnknownDuplicateTrailingScalarCoercionAndOversizedPayload() throws Exception {
        var csrf=csrf(null);
        for(String body:new String[]{"{\"username\":\"alice\",\"password\":\"password\",\"role\":\"ADMIN\"}",
                "{\"username\":\"alice\",\"username\":\"admin\",\"password\":\"password\"}",
                "{\"username\":\"alice\",\"password\":\"password\"} {}", "{\"username\":123,\"password\":\"password\"}"}) {
            mvc.perform(post("/api/v2/auth/login").with(https()).session(csrf.session()).header("X-CSRF-TOKEN",csrf.token())
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v2/auth/login").with(https()).session(csrf.session()).header("X-CSRF-TOKEN",csrf.token())
                .contentType("application/json").content("x".repeat(16385))).andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(limits);
    }
    @Test void legacyCookieCannotLoadV2SessionAndCutoverIsExplicit() throws Exception {
        var session=login();
        mvc.perform(get("/api/v1/users/7").with(https()).session(session)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/users/7").with(https()).cookie(new jakarta.servlet.http.Cookie("__Host-hotel-session","opaque")))
                .andExpect(status().isForbidden());
        for(String path:new String[]{"/api/v1/auth/register","/api/v1/bookings"})
            mvc.perform(post(path).with(https()).contentType("application/json").content("{}"))
                    .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("LEGACY_WRITE_CLOSED"));
    }
    @Test void traceAndRateLimitAreStableSafeAndNoStore() throws Exception {
        var csrf=csrf(null);
        doThrow(new V2ApiException(429,"RATE_LIMITED","Повторите позже.",Map.of("retryAfterSeconds",900)))
                .when(limits).checkLogin(anyString(),anyString());
        var result=mvc.perform(post("/api/v2/auth/login").with(https()).session(csrf.session()).header("X-CSRF-TOKEN",csrf.token())
                .contentType("application/json").content("{\"username\":\"alice\",\"password\":\"password\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","900"))
                .andExpect(header().string("Cache-Control","private, no-store")).andReturn();
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("traceId").asText())
                .isEqualTo(result.getResponse().getHeader("X-Trace-Id"));
    }
}
