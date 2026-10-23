package com.kryukow.HotelReservationService.web.v2.auth;

import com.kryukow.HotelReservationService.dto.user.UserCreateUpdateRequestDto;
import com.kryukow.HotelReservationService.service.UserService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.sql.Timestamp;

@Service
@ConditionalOnProperty(name="app.web.v2.enabled", havingValue="true")
public class BrowserRegistrationService {
    private final UserService users;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public BrowserRegistrationService(UserService users, JdbcTemplate jdbc, Clock clock) {
        this.users=users; this.jdbc=jdbc; this.clock=clock;
    }
    @Transactional
    public Long register(String username, String email, String password) {
        var dto = new UserCreateUpdateRequestDto(); dto.setName(username); dto.setEmail(email); dto.setPassword(password);
        Long id = users.create(dto).getId();
        jdbc.update("INSERT INTO browser_demo_accounts(user_id,created_at) VALUES (?,?)", id,Timestamp.from(clock.instant()));
        return id;
    }
    public boolean isRestricted(Long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM browser_demo_accounts WHERE user_id=?)",Boolean.class,id));
    }
}
