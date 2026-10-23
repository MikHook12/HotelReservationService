package com.kryukow.HotelReservationService.web.v2.auth;

import jakarta.servlet.http.*;
import org.springframework.security.web.csrf.*;
import java.io.Serializable;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/** Serializable state is persisted by Spring Session JDBC, not a process-local token map. */
public final class BrowserCsrfRepository implements CsrfTokenRepository {
    private static final String ATTRIBUTE = "v2.csrf";
    private record Stored(String token, long expires) implements Serializable {}
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    public BrowserCsrfRepository(Clock clock) { this.clock = clock; }
    @Override public CsrfToken generateToken(HttpServletRequest request) {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }
    @Override public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(token != null);
        if (session == null) return;
        if (token == null) { session.removeAttribute(ATTRIBUTE); return; }
        Instant now = clock.instant();
        if (session.getAttribute(BrowserSessionState.LOGIN) == null && session.getAttribute(BrowserSessionState.ANONYMOUS) == null)
            session.setAttribute(BrowserSessionState.ANONYMOUS, now.getEpochSecond());
        session.setMaxInactiveInterval(1800);
        long expiry = Math.min(now.plusSeconds(1800).getEpochSecond(), BrowserSessionState.absolute(session, now).getEpochSecond());
        session.setAttribute(ATTRIBUTE, new Stored(token.getToken(), expiry));
    }
    @Override public CsrfToken loadToken(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Object state = session.getAttribute(ATTRIBUTE);
        if (!(state instanceof Stored stored) || clock.instant().getEpochSecond() >= stored.expires()) return null;
        return new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", stored.token());
    }
    public Instant expiresAt(HttpSession session) {
        Object state = session.getAttribute(ATTRIBUTE);
        return state instanceof Stored stored ? Instant.ofEpochSecond(stored.expires()) : clock.instant();
    }
}
