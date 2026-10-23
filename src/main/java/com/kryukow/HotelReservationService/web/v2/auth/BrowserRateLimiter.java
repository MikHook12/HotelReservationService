package com.kryukow.HotelReservationService.web.v2.auth;

import com.kryukow.HotelReservationService.web.v2.common.V2ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

@Service
@ConditionalOnProperty(name="app.web.v2.enabled", havingValue="true")
public class BrowserRateLimiter {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public BrowserRateLimiter(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    private String bucket(String category, String source, String username) {
        byte[] fresh = new byte[32]; new SecureRandom().nextBytes(fresh);
        jdbc.update("INSERT INTO browser_security_settings(name,secret) VALUES ('rate-hmac',?) ON CONFLICT(name) DO NOTHING", fresh);
        byte[] key = jdbc.queryForObject("SELECT secret FROM browser_security_settings WHERE name='rate-hmac'", byte[].class);
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((category + '\0' + source + '\0'
                    + username.strip().toLowerCase(Locale.ROOT)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("Rate store unavailable"); }
    }
    private void limit(String key, int max, int seconds, boolean increment) {
        Instant now = clock.instant();
        jdbc.update("DELETE FROM browser_rate_limits WHERE window_start < ?", Timestamp.from(now.minusSeconds(86400)));
        if (increment) jdbc.update("""
                INSERT INTO browser_rate_limits(bucket_key,window_start,attempts) VALUES (?,?,1)
                ON CONFLICT(bucket_key) DO UPDATE SET
                attempts=CASE WHEN browser_rate_limits.window_start <= ? THEN 1 ELSE LEAST(browser_rate_limits.attempts+1,2147483646) END,
                window_start=CASE WHEN browser_rate_limits.window_start <= ? THEN EXCLUDED.window_start ELSE browser_rate_limits.window_start END
                """, key, Timestamp.from(now), Timestamp.from(now.minusSeconds(seconds)), Timestamp.from(now.minusSeconds(seconds)));
        var rows = jdbc.query("SELECT window_start,attempts FROM browser_rate_limits WHERE bucket_key=?",
                (rs, n) -> Map.entry(rs.getTimestamp(1).toInstant(), rs.getInt(2)), key);
        if (rows.isEmpty()) return;
        var row = rows.getFirst();
        if (now.isBefore(row.getKey().plusSeconds(seconds)) && (increment ? row.getValue() > max : row.getValue() >= max)) {
            long wait = Math.max(1, row.getKey().plusSeconds(seconds).getEpochSecond() - now.getEpochSecond());
            throw new V2ApiException(429, "RATE_LIMITED", "Слишком много попыток. Повторите позже.", Map.of("retryAfterSeconds", wait));
        }
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW, noRollbackFor=V2ApiException.class)
    public void checkLogin(String source, String username) {
        limit(bucket("login-user", source, username), 5, 900, false);
        limit(bucket("login-source", source, ""), 30, 900, false);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW, noRollbackFor=V2ApiException.class)
    public void loginFailed(String source, String username) {
        // Commit both counters even if either budget is exceeded.
        V2ApiException exceeded = null;
        try { limit(bucket("login-source", source, ""), 30, 900, true); } catch (V2ApiException ex) { exceeded = ex; }
        try { limit(bucket("login-user", source, username), 5, 900, true); } catch (V2ApiException ex) { exceeded = ex; }
        if (exceeded != null) throw exceeded;
    }
    /** Per-user budgets bound durable operation/quote rows, including recovery traffic. */
    @Transactional(propagation=Propagation.REQUIRES_NEW, noRollbackFor=V2ApiException.class)
    public void bookingAttempt(Long owner, String operation) {
        String name = operation.toLowerCase(Locale.ROOT);
        int maximum = switch (name) {
            case "quote" -> 30;
            case "create", "fence" -> 10;
            case "result" -> 60;
            default -> throw new IllegalArgumentException("Unknown booking operation");
        };
        limit(bucket("booking-" + name, owner.toString(), ""), maximum, 60, true);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW, noRollbackFor=V2ApiException.class)
    public void registrationAttempt(String source) { limit(bucket("signup", source, ""), 3, 3600, true); }
}
