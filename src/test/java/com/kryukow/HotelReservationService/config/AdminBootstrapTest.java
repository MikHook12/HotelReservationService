package com.kryukow.HotelReservationService.config;

import com.kryukow.HotelReservationService.model.Role;
import com.kryukow.HotelReservationService.model.User;
import com.kryukow.HotelReservationService.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminBootstrapTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private AdminBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        bootstrap = new AdminBootstrap(users, encoder,
                new AdminBootstrap.Properties("admin", "admin@example.com", "a-long-secret-password"));
        when(users.findByName("admin")).thenReturn(Optional.empty());
    }

    @Test
    void createsAdminWithEncodedPassword() {
        when(encoder.encode("a-long-secret-password")).thenReturn("encoded");
        bootstrap.run(null);
        var capture = ArgumentCaptor.forClass(User.class);
        verify(users).save(capture.capture());
        assertThat(capture.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(capture.getValue().getPassword()).isEqualTo("encoded");
    }

    @Test
    void neverPromotesExistingOrdinaryUser() {
        User existing = new User(); existing.setRole(Role.USER);
        when(users.findByName("admin")).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> bootstrap.run(null)).isInstanceOf(IllegalStateException.class);
        verify(users, never()).save(any());
        verifyNoInteractions(encoder);
    }

    @Test
    void neverResetsExistingAdministratorPassword() {
        User existing = new User(); existing.setRole(Role.ADMIN); existing.setEmail("admin@example.com");
        existing.setPassword("original");
        when(users.findByName("admin")).thenReturn(Optional.of(existing));
        bootstrap.run(null);
        assertThat(existing.getPassword()).isEqualTo("original");
        verify(users, never()).save(any());
        verifyNoInteractions(encoder);
    }

    @Test
    void refusesEmailAlreadyUsedByAnotherAccount() {
        when(users.existsByEmail("admin@example.com")).thenReturn(true);
        assertThatThrownBy(() -> bootstrap.run(null)).isInstanceOf(IllegalStateException.class);
        verify(users, never()).save(any());
    }

    @Test
    void rejectsPasswordExceedingBcryptByteLimit() {
        bootstrap = new AdminBootstrap(users, encoder,
                new AdminBootstrap.Properties("admin", "admin@example.com", "я".repeat(40)));
        assertThatThrownBy(() -> bootstrap.run(null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(encoder);
    }
}
