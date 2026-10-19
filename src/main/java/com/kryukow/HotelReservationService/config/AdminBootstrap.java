package com.kryukow.HotelReservationService.config;

import com.kryukow.HotelReservationService.model.Role;
import com.kryukow.HotelReservationService.model.User;
import com.kryukow.HotelReservationService.repository.UserRepository;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import java.nio.charset.StandardCharsets;

/** Explicit one-time bootstrap; never promotes or resets an existing account. */
@Component
@ConditionalOnProperty(name = "app.bootstrap.admin.enabled", havingValue = "true")
@EnableConfigurationProperties(AdminBootstrap.Properties.class)
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Properties properties;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String password = properties.password();
        if (password == null || password.isBlank() || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            // Do not use binding validation here: its diagnostics may echo rejected secrets.
            throw new IllegalArgumentException("Bootstrap password requires at least 12 characters and at most 72 UTF-8 bytes");
        }
        var existing = users.findByName(properties.name());
        if (existing.isPresent()) {
            if (existing.get().getRole() != Role.ADMIN
                    || !existing.get().getEmail().equals(properties.email())) {
                throw new IllegalStateException("Bootstrap account conflicts with an existing user");
            }
            log.info("Administrator already exists; credentials unchanged");
            return;
        }
        if (users.existsByEmail(properties.email())) {
            throw new IllegalStateException("Bootstrap email belongs to an existing user");
        }
        User admin = new User();
        admin.setName(properties.name());
        admin.setEmail(properties.email());
        admin.setPassword(encoder.encode(properties.password()));
        admin.setRole(Role.ADMIN);
        users.save(admin);
        log.info("Initial administrator created; disable bootstrap and remove its password from configuration");
    }

    @Validated
    @ConfigurationProperties("app.bootstrap.admin")
    public record Properties(@NotBlank @Size(min = 3, max = 50) String name,
                             @NotBlank @Email String email,
                             String password) { }
}
