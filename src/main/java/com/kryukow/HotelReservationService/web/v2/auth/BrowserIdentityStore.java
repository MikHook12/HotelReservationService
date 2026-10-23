package com.kryukow.HotelReservationService.web.v2.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name="app.web.v2.enabled", havingValue="true")
public class BrowserIdentityStore {
    public record Identity(Long id, String username, String passwordHash, String email, String role, long version) {}
    private final JdbcTemplate jdbc;
    public BrowserIdentityStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private Optional<Identity> lookup(String suffix, Object value) {
        return jdbc.query("SELECT id,name,password,email,role,browser_security_version FROM users WHERE " + suffix,
                (rs, n) -> new Identity(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6)), value)
                .stream().findFirst();
    }
    public Optional<Identity> byUsername(String username) { return lookup("name = ?", username); }
    public Optional<Identity> byId(Long id) { return lookup("id = ?", id); }
}
