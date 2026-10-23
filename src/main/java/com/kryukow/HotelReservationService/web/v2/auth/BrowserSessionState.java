package com.kryukow.HotelReservationService.web.v2.auth;

import jakarta.servlet.http.HttpSession;
import java.time.Instant;

public final class BrowserSessionState {
    private BrowserSessionState() {}
    public static final String LOGIN = "v2.loginEpoch";
    public static final String LAST = "v2.lastEpoch";
    public static final String ANONYMOUS = "v2.anonymousEpoch";
    public static final long IDLE_SECONDS = 1800;
    public static final long ABSOLUTE_SECONDS = 43200;
    public static long epoch(HttpSession session, String key, long fallback) {
        Object value = session.getAttribute(key);
        return value instanceof Long number ? number : fallback;
    }
    public static Instant absolute(HttpSession session, Instant now) {
        long login = epoch(session, LOGIN, -1);
        return login >= 0 ? Instant.ofEpochSecond(login + ABSOLUTE_SECONDS)
                : Instant.ofEpochSecond(epoch(session, ANONYMOUS, now.getEpochSecond()) + IDLE_SECONDS);
    }
}
