package com.kryukow.HotelReservationService.local;

import com.kryukow.HotelReservationService.model.Hotel;
import com.kryukow.HotelReservationService.model.Role;
import com.kryukow.HotelReservationService.model.Room;
import com.kryukow.HotelReservationService.model.User;
import com.kryukow.HotelReservationService.repository.HotelRepository;
import com.kryukow.HotelReservationService.repository.RoomRepository;
import com.kryukow.HotelReservationService.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LocalDataInitializerTest {
    private final UserRepository users = mock(UserRepository.class);
    private final HotelRepository hotels = mock(HotelRepository.class);
    private final RoomRepository rooms = mock(RoomRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final org.springframework.jdbc.core.JdbcTemplate jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
    private LocalDataInitializer initializer;

    @BeforeEach
    void setUp() {
        environment.setActiveProfiles("local");
        environment.setProperty("app.local.environment-id", "hotel-local-app-v1");
        environment.setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:15433/hotel_local");
        environment.setProperty("spring.datasource.username", "hotel_local");
        environment.setProperty("server.address", "127.0.0.1");
        environment.setProperty("app.local.password", "0123456789abcdefghijklmnop");
        initializer = new LocalDataInitializer(environment, users, hotels, rooms, encoder, jdbc);
    }

    @ParameterizedTest
    @CsvSource({
            "app.local.environment-id,another-environment",
            "spring.datasource.url,jdbc:postgresql://127.0.0.1:5432/working_database",
            "spring.datasource.username,postgres",
            "server.address,0.0.0.0"
    })
    void refusesWrongEnvironmentBeforeAnyRepositoryAccess(String property, String value) {
        environment.setProperty(property, value);
        assertThatThrownBy(() -> initializer.run(null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(users, hotels, rooms, encoder);
    }

    @Test
    void refusesAdditionalProfilesAndMissingExplicitProfileBeforeRepositoryAccess() {
        environment.setActiveProfiles("local", "production");
        assertThatThrownBy(() -> initializer.run(null)).isInstanceOf(IllegalStateException.class);
        environment.setActiveProfiles();
        assertThatThrownBy(() -> initializer.run(null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(users, hotels, rooms, encoder);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "this password contains forbidden spaces", "пароль-достаточно-длинный-для-теста"})
    void refusesInvalidPasswordWithoutEchoingItOrAccessingRepositories(String password) {
        if (password == null) {
            ((MapPropertySource) environment.getPropertySources().get("mockProperties"))
                    .getSource().remove("app.local.password");
        }
        else environment.setProperty("app.local.password", password);
        assertThatThrownBy(() -> initializer.run(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Local password requires 24 to 64 printable ASCII characters without spaces");
        verifyNoInteractions(users, hotels, rooms, encoder);
    }

    @Test
    void existingSeedUserPreservesPasswordAndAllCatalogueEditsIncludingDeletions() {
        User existing = new User();
        existing.setRole(Role.USER);
        existing.setEmail("local-user@example.invalid");
        existing.setPassword("changed-by-user");
        when(users.findByName("local-user")).thenReturn(Optional.of(existing));
        initializer.run(null);
        assertThat(existing.getPassword()).isEqualTo("changed-by-user");
        verify(users).findByName("local-user");
        verifyNoMoreInteractions(users);
        verifyNoInteractions(hotels, rooms, encoder);
    }

    @Test
    void refusesExistingAdministratorAndEmailConflictsWithoutChangingAccount() {
        User existing = new User();
        existing.setRole(Role.ADMIN);
        existing.setEmail("local-user@example.invalid");
        when(users.findByName("local-user")).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> initializer.run(null)).isInstanceOf(IllegalStateException.class);
        existing.setRole(Role.USER);
        existing.setEmail("someone@example.invalid");
        assertThatThrownBy(() -> initializer.run(null)).isInstanceOf(IllegalStateException.class);
        verify(users, never()).save(any());
        verifyNoInteractions(hotels, rooms, encoder);
    }

    @Test
    void refusesNonemptyDatabaseWhenSeedAccountIsMissing() {
        when(hotels.count()).thenReturn(1L);
        assertThatThrownBy(() -> initializer.run(null)).isInstanceOf(IllegalStateException.class);
        verify(users, never()).save(any());
        verify(hotels, never()).save(any());
        verify(rooms, never()).save(any());
        verifyNoInteractions(encoder);
    }

    @Test
    void durableMarkerPreservesEvenDeletionOfInitialAccount() {
        when(jdbc.queryForList(anyString(), eq("hotel-local-app-v1")))
                .thenReturn(java.util.List.of(java.util.Map.of("environment_id", "hotel-local-app-v1")));
        initializer.run(null);
        verifyNoInteractions(users, hotels, rooms, encoder);
    }

    @Test
    void createsOnlyOrdinaryUserAndPublishedFictionalCatalogueWithCorrectGeography() {
        when(encoder.encode("0123456789abcdefghijklmnop")).thenReturn("encoded-secret");
        when(hotels.save(any())).thenAnswer(call -> call.getArgument(0));
        initializer.run(null);
        var user = ArgumentCaptor.forClass(User.class);
        verify(users).save(user.capture());
        assertThat(user.getValue().getRole()).isEqualTo(Role.USER);
        assertThat(user.getValue().getPassword()).isEqualTo("encoded-secret");
        assertThat(user.getValue().getEmail()).isEqualTo("local-user@example.invalid");
        var hotel = ArgumentCaptor.forClass(Hotel.class);
        verify(hotels, times(3)).save(hotel.capture());
        assertThat(hotel.getAllValues()).extracting(Hotel::getCity)
                .containsExactly("Москва", "Санкт-Петербург", "Казань");
        assertThat(hotel.getAllValues()).allSatisfy(value -> {
            assertThat(value.getPublished()).isTrue();
            assertThat(value.getDemo()).isTrue();
            assertThat(value.getTimeZone()).isEqualTo("Europe/Moscow");
        });
        var room = ArgumentCaptor.forClass(Room.class);
        verify(rooms, times(6)).save(room.capture());
        Room primary = room.getAllValues().getFirst();
        assertThat(primary.getHotel().getSlug()).isEqualTo("demo-severny-svet");
        assertThat(primary.getNumber()).isEqualTo(101);
        assertThat(primary.getPrice()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(primary.getCapacity()).isEqualTo(2);
        assertThat(room.getAllValues()).allSatisfy(value -> assertThat(value.getPublished()).isTrue());
    }
}
