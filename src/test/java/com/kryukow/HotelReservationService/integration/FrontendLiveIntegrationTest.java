package com.kryukow.HotelReservationService.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kryukow.HotelReservationService.model.Hotel;
import com.kryukow.HotelReservationService.model.Role;
import com.kryukow.HotelReservationService.model.Room;
import com.kryukow.HotelReservationService.model.User;
import com.kryukow.HotelReservationService.repository.HotelRepository;
import com.kryukow.HotelReservationService.repository.RoomRepository;
import com.kryukow.HotelReservationService.repository.UserRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Opt-in local browser harness. Uses only BaseIntegrationTest's disposable Testcontainers and
 * publishes credentials after Spring's random-port server and all fixtures are ready.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "app.web.v2.enabled=true",
        "app.web.v2.quote-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.kafka.listener.auto-startup=true",
        "app.outbox.enabled=true",
        "server.address=127.0.0.1"
})
@EnabledIfSystemProperty(named = "frontend.live", matches = "true")
@Tag("manual")
class FrontendLiveIntegrationTest extends BaseIntegrationTest {
    private static final String TEST_USERNAME = "frontend-browser-user";
    private static final String TEST_PASSWORD = "local-browser-test-password";

    @LocalServerPort int port;
    @Autowired HotelRepository hotels;
    @Autowired RoomRepository rooms;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @Test
    void publishReadyFixturesAndKeepBackendAvailableForLocalFrontend() throws Exception {
        assertThat(postgres.isRunning()).isTrue();
        assertThat(mongo.isRunning()).isTrue();
        assertThat(kafka.isRunning()).isTrue();
        assertThat(port).isBetween(1, 65535);

        String username = TEST_USERNAME;
        String email = "frontend-browser-user@example.invalid";
        if (users.existsByName(username) || users.existsByEmail(email)) {
            throw new IllegalStateException("Opt-in frontend Testcontainer unexpectedly contains a reserved fixture account");
        }
        User user = new User();
        user.setName(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setRole(Role.USER);
        users.saveAndFlush(user);

        FixtureHotel primary = createHotel("demo-severny-svet", "Демо: Северный свет", "Двухместная", 101, "5000.00");
        createHotel("demo-sosnovy-dvor", "Демо: Сосновый двор", "Семейная", 201, "6200.00");

        LocalDate checkIn = LocalDate.now(ZoneId.of("Europe/Moscow")).plusDays(10);
        LocalDate checkOut = checkIn.plusDays(2);
        Path readyFile = requiredAbsoluteProperty("frontend.ready-file");
        Path stopFile = optionalAbsoluteProperty("frontend.stop-file");
        if (stopFile != null && stopFile.equals(readyFile)) {
            throw new IllegalArgumentException("frontend.stop-file must be different from frontend.ready-file");
        }
        if (stopFile != null && Files.exists(stopFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Remove the stale frontend stop marker before starting the opt-in backend");
        }
        long timeoutSeconds = timeoutSeconds();

        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("backendOrigin", "http://127.0.0.1:" + port);
        ready.put("username", TEST_USERNAME);
        ready.put("password", TEST_PASSWORD);
        ready.put("hotelSlug", primary.hotel().getSlug());
        ready.put("roomId", primary.room().getId().toString());
        ready.put("checkInDate", checkIn.toString());
        ready.put("checkOutDate", checkOut.toString());
        ready.put("guests", 2);
        publishCreateNew(readyFile, objectMapper.writeValueAsBytes(ready));

        long deadline = System.nanoTime() + Math.multiplyExact(timeoutSeconds, 1_000_000_000L);
        try {
            while (true) {
                if (stopFile != null && Files.exists(stopFile, LinkOption.NOFOLLOW_LINKS)) return;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) fail("Timed out waiting for frontend.stop-file after " + timeoutSeconds + " seconds");
                Thread.sleep(Math.min(1_000L, Math.max(1L, remaining / 1_000_000L)));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Opt-in frontend backend wait was interrupted", interrupted);
        }
    }

    private record FixtureHotel(Hotel hotel, Room room) {}

    private FixtureHotel createHotel(String slug, String name, String roomName, int roomNumber, String price) {
        Hotel hotel = new Hotel();
        hotel.setName(name);
        hotel.setTitle(name);
        hotel.setCity("Москва");
        hotel.setAddress("Условный адрес для локального frontend preview");
        hotel.setDistanceFromCityCenter(1.0);
        hotel.setSlug(slug);
        hotel.setTimeZone("Europe/Moscow");
        hotel.setCityCode("moscow");
        hotel.setDemo(true);
        hotel.setDescription("Вымышленный отель для локальной проверки интерфейса. Реального размещения нет.");
        hotel.setPublished(true);
        hotel = hotels.saveAndFlush(hotel);

        Room room = new Room();
        room.setName(roomName);
        room.setDescription("Вымышленная комната для локальной проверки интерфейса.");
        room.setNumber(roomNumber);
        room.setPrice(new BigDecimal(price));
        room.setCapacity(2);
        room.setHotel(hotel);
        room.setPublished(true);
        room = rooms.saveAndFlush(room);
        return new FixtureHotel(hotel, room);
    }

    private long timeoutSeconds() {
        String configured = System.getProperty("frontend.timeout-seconds", "1800");
        try {
            long seconds = Long.parseLong(configured);
            if (seconds < 1 || seconds > 10_800) throw new NumberFormatException();
            return seconds;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("frontend.timeout-seconds must be an integer from 1 to 10800");
        }
    }

    private Path requiredAbsoluteProperty(String property) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) throw new IllegalArgumentException("System property -D" + property + " is required");
        return validatePath(configured, property, true);
    }

    private Path optionalAbsoluteProperty(String property) {
        String configured = System.getProperty(property);
        return configured == null || configured.isBlank() ? null : validatePath(configured, property, false);
    }

    private Path validatePath(String configured, String property, boolean requireNewFileParent) {
        Path path = Path.of(configured);
        if (!path.isAbsolute()) throw new IllegalArgumentException("-D" + property + " must be an absolute path");
        Path normalized = path.normalize();
        Path parent = normalized.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IllegalArgumentException("Parent directory for -D" + property + " must already exist");
        if (requireNewFileParent && Files.exists(normalized, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("Refusing to overwrite existing frontend ready file");
        return normalized;
    }

    private void publishCreateNew(Path readyFile, byte[] contents) throws IOException {
        Set<PosixFilePermission> ownerOnly = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        try {
            Files.createFile(readyFile, PosixFilePermissions.asFileAttribute(ownerOnly));
        } catch (UnsupportedOperationException noPosixPermissions) {
            Files.createFile(readyFile);
        }
        try {
            // On a non-POSIX filesystem, restrict permissions where the provider supports it.
            try { Files.setPosixFilePermissions(readyFile, ownerOnly); }
            catch (UnsupportedOperationException ignored) { /* provider does not expose POSIX modes */ }
            Files.write(readyFile, contents, StandardOpenOption.WRITE);
        } catch (IOException failure) {
            Files.deleteIfExists(readyFile); // Only the file this test just created.
            throw failure;
        }
    }
}
