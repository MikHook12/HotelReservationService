package com.kryukow.HotelReservationService.web.v2.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kryukow.HotelReservationService.integration.BaseIntegrationTest;
import com.kryukow.HotelReservationService.web.v2.common.V2ApiException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@TestPropertySource(properties={"app.web.v2.enabled=true","app.web.v2.registration-mode=TEST_ONLY","app.web.v2.test-registration-allowed=true","app.web.v2.quote-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class BrowserSecurityIntegrationTest extends BaseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired Clock clock;
    @Autowired BrowserRateLimiter limiter;
    @Autowired JdbcIndexedSessionRepository sessions;
    @Autowired PlatformTransactionManager transactions;
    Long userId;
    String username;
    @BeforeEach void fixture() {
        username="auth-"+UUID.randomUUID();
        userId=jdbc.queryForObject("INSERT INTO users(name,password,email,role) VALUES (?,?,?,'USER') RETURNING id",Long.class,
                username,encoder.encode("password"),username+"@example.invalid");
    }
    record Handshake(Cookie cookie,String token) {}
    Handshake csrf(Cookie cookie) throws Exception {
        var request=get("/api/v2/auth/csrf").with(BrowserAuthHttpTest.https());
        if(cookie!=null)request.cookie(cookie);
        var result=mvc.perform(request).andExpect(status().isOk()).andReturn();
        Cookie updated=result.getResponse().getCookie("__Host-hotel-session");
        return new Handshake(updated!=null?updated:cookie,json.readTree(result.getResponse().getContentAsString()).get("token").asText());
    }
    Cookie login() throws Exception {
        var csrf=csrf(null);
        var result=mvc.perform(post("/api/v2/auth/login").with(BrowserAuthHttpTest.https()).cookie(csrf.cookie())
                .header("X-CSRF-TOKEN",csrf.token()).contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("username",username,"password","password"))))
                .andExpect(status().isOk()).andReturn();
        var cookie=result.getResponse().getCookie("__Host-hotel-session");
        assertThat(cookie).isNotNull(); assertThat(cookie.getValue()).isNotEqualTo(csrf.cookie().getValue());
        assertThat(cookie.isHttpOnly()).isTrue(); assertThat(cookie.getSecure()).isTrue(); assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getDomain()).isNull();
        assertThat(result.getResponse().getHeaders("Set-Cookie")).anyMatch(value->value.contains("SameSite=Lax"));
        return cookie;
    }
    @Test void jdbcSessionSurvivesRepositoryReconstructionAndLogoutDeletesIt() throws Exception {
        Cookie cookie=login();
        String sessionId=jdbc.queryForObject("SELECT session_id FROM spring_session WHERE principal_name=?",String.class,username);
        assertThat(sessions.findById(sessionId)).isNotNull();
        var independent=new JdbcIndexedSessionRepository(jdbc,new TransactionTemplate(transactions));
        org.springframework.session.Session restored=independent.findById(sessionId);
        assertThat(restored).isNotNull();
        SecurityContext context=restored.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(context.getAuthentication().getName()).isEqualTo(username);
        assertThat(context.getAuthentication().getCredentials()).isNull();
        mvc.perform(get("/api/v2/auth/me").with(BrowserAuthHttpTest.https()).cookie(cookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(userId.toString()));
        var csrf=csrf(cookie);
        mvc.perform(post("/api/v2/auth/logout").with(BrowserAuthHttpTest.https()).cookie(csrf.cookie()).header("X-CSRF-TOKEN",csrf.token()))
                .andExpect(status().isNoContent());
        assertThat(sessions.findById(sessionId)).isNull();
        mvc.perform(get("/api/v2/auth/me").with(BrowserAuthHttpTest.https()).cookie(cookie)).andExpect(status().isUnauthorized());
    }
    @Test void nativeAndLegacyUpdatesRevokeEvenRoleRoundTrip() throws Exception {
        Cookie cookie=login();
        jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?",userId);
        jdbc.update("UPDATE users SET role='USER' WHERE id=?",userId);
        assertThat(jdbc.queryForObject("SELECT browser_security_version FROM users WHERE id=?",Long.class,userId)).isEqualTo(2L);
        mvc.perform(get("/api/v2/auth/me").with(BrowserAuthHttpTest.https()).cookie(cookie)).andExpect(status().isUnauthorized());
        cookie=login(); jdbc.update("UPDATE users SET password=? WHERE id=?",encoder.encode("different"),userId);
        mvc.perform(get("/api/v2/auth/me").with(BrowserAuthHttpTest.https()).cookie(cookie)).andExpect(status().isUnauthorized());
    }
    @Test void rateLimitsAreDurableSharedAndBoundedUnderConcurrentFailures() throws Exception {
        String source="isolated-test-"+UUID.randomUUID();
        for(int i=0;i<5;i++)limiter.loginFailed(source,username);
        var freshProcess=new BrowserRateLimiter(jdbc,clock);
        assertThatThrownBy(()->freshProcess.checkLogin(source,username)).isInstanceOfSatisfying(V2ApiException.class,
                ex->{assertThat(ex.getStatus()).isEqualTo(429);assertThat(ex.getCode()).isEqualTo("RATE_LIMITED");});
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM browser_rate_limits WHERE bucket_key LIKE ?",Long.class,"%"))
                .isGreaterThanOrEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM browser_rate_limits WHERE bucket_key LIKE ?",Long.class,"%"+username+"%"))
                .isZero(); // No raw login/source in persistence.
        var pool=Executors.newFixedThreadPool(6);
        var start=new CountDownLatch(1);
        try {
            var results=new java.util.ArrayList<Future<?>>();
            String second="concurrent-"+UUID.randomUUID();
            for(int i=0;i<6;i++)results.add(pool.submit(()->{start.await(5,TimeUnit.SECONDS);
                try {freshProcess.loginFailed(second,username);}catch(V2ApiException limited){assertThat(limited.getStatus()).isEqualTo(429);}return null;}));
            start.countDown();for(var result:results)result.get(15,TimeUnit.SECONDS);
            assertThatThrownBy(()->limiter.checkLogin(second,username)).isInstanceOf(V2ApiException.class);
        } finally {pool.shutdownNow();}
    }
    @Test void durableBookingBudgetsArePerOwnerAndOperation() {
        for(var entry:java.util.Map.of("quote",30,"create",10,"fence",10,"result",60).entrySet()) {
            for(int attempt=0;attempt<entry.getValue();attempt++)limiter.bookingAttempt(userId,entry.getKey());
            assertThatThrownBy(()->limiter.bookingAttempt(userId,entry.getKey()))
                    .isInstanceOfSatisfying(V2ApiException.class,ex->{
                        assertThat(ex.getStatus()).isEqualTo(429);
                        assertThat(ex.getDetails().get("retryAfterSeconds")).isInstanceOf(Number.class);
                    });
            // Different owner has its own durable budget, no session/process-local limiter state.
            limiter.bookingAttempt(userId+1000000L,entry.getKey());
        }
    }
    @Test void failedLoginHttpLimitCannotBeBypassedWithForwardedHeaders() throws Exception {
        var csrf=csrf(null);
        for(int attempt=0;attempt<6;attempt++) {
            var result=mvc.perform(post("/api/v2/auth/login").with(BrowserAuthHttpTest.https())
                    .with(request->{request.setRemoteAddr("203.0.113.44");return request;})
                    .cookie(csrf.cookie()).header("X-CSRF-TOKEN",csrf.token())
                    .header("X-Forwarded-For","198.51.100."+attempt).contentType("application/json")
                    .content(json.writeValueAsString(java.util.Map.of("username",username,"password","wrong-password"))))
                    .andExpect(status().is(attempt<5?401:429)).andReturn();
            if(attempt==5)assertThat(result.getResponse().getHeader("Retry-After")).isNotBlank();
        }
    }
    @Test void restrictedSignupPersistsUserOutboxAndDemoMarkerWithoutAuthentication() throws Exception {
        var csrf=csrf(null); String name="new-"+UUID.randomUUID();
        mvc.perform(post("/api/v2/auth/register").with(BrowserAuthHttpTest.https()).cookie(csrf.cookie())
                .header("X-CSRF-TOKEN",csrf.token()).contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("username",name,"password","password","email",name+"@example.invalid"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.roles[0]").value("USER"))
                .andExpect(jsonPath("$.emailVerified").value(false)).andExpect(jsonPath("$.isRestrictedDemoAccount").value(true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM browser_demo_accounts d JOIN users u ON u.id=d.user_id WHERE u.name=?",Long.class,name)).isEqualTo(1L);
        mvc.perform(get("/api/v2/auth/me").with(BrowserAuthHttpTest.https()).cookie(csrf.cookie())).andExpect(status().isUnauthorized());
    }
}
