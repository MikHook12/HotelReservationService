package com.kryukow.HotelReservationService.local;

import com.kryukow.HotelReservationService.model.Hotel;
import com.kryukow.HotelReservationService.model.Role;
import com.kryukow.HotelReservationService.model.Room;
import com.kryukow.HotelReservationService.model.User;
import com.kryukow.HotelReservationService.repository.HotelRepository;
import com.kryukow.HotelReservationService.repository.RoomRepository;
import com.kryukow.HotelReservationService.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** One-time data for the dedicated local database; existing data is never repaired or reset. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "app.local.seed-enabled", havingValue = "true")
@RequiredArgsConstructor
@org.springframework.core.annotation.Order(-100)
public class LocalDataInitializer implements ApplicationRunner {
    private final Environment environment;
    private final UserRepository users;
    private final HotelRepository hotels;
    private final RoomRepository rooms;
    private final PasswordEncoder encoder;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        LocalEnvironmentGuard.verify(environment);
        if (!jdbc.queryForList("select environment_id from local_environment_state where environment_id = ?", "hotel-local-app-v1").isEmpty()) return;
        String username = environment.getProperty("app.local.username", "local-user");
        if (!username.matches("[a-z][a-z0-9-]{2,39}")) {
            throw new IllegalArgumentException("Local username must contain 3 to 40 lowercase letters, digits or hyphens");
        }
        String password = environment.getProperty("app.local.password");
        if (password == null || password.length() < 24 || password.length() > 64
                || !password.chars().allMatch(character -> character >= 33 && character <= 126)) {
            // Never use binding validation that could include the rejected secret in diagnostics.
            throw new IllegalArgumentException("Local password requires 24 to 64 printable ASCII characters without spaces");
        }
        String email = username + "@example.invalid";
        var existing = users.findByName(username);
        if (existing.isPresent()) {
            if (existing.get().getRole() != Role.USER || !email.equals(existing.get().getEmail())) {
                throw new IllegalStateException("Local seed account conflicts with an existing user; no changes applied");
            }
            // Upgrade a previously initialized local environment without resetting any data.
            markInitialized();
            return;
        }
        if (users.existsByEmail(email) || users.count() != 0 || hotels.count() != 0 || rooms.count() != 0) {
            throw new IllegalStateException("Local initialization requires an empty dedicated database; no changes applied");
        }

        User user = new User();
        user.setName(username);
        user.setEmail(email);
        user.setPassword(encoder.encode(password));
        user.setRole(Role.USER);
        users.save(user);

        Hotel north = hotel("demo-severny-svet", "Демо: Северный свет", "Москва", "moscow",
                "Вымышленный адрес: Садовая улица, 12", 1.2);
        room(north, "Двухместная", 101, "5000.00", 2);
        room(north, "Семейная", 102, "7200.00", 4);
        Hotel pine = hotel("demo-sosnovy-dvor", "Демо: Сосновый двор", "Санкт-Петербург", "saint-petersburg",
                "Вымышленный адрес: Сосновая улица, 8", 2.4);
        room(pine, "Двухместная комфорт", 201, "6200.00", 2);
        room(pine, "Семейная", 202, "8100.00", 4);
        Hotel river = hotel("demo-rechnoy", "Демо: Речной", "Казань", "kazan",
                "Вымышленный адрес: Набережная улица, 5", 0.8);
        room(river, "Одноместная", 301, "3900.00", 1);
        room(river, "Двухместная", 302, "5400.00", 2);
        markInitialized();
    }

    private void markInitialized() {
        jdbc.update("insert into local_environment_state(environment_id) values (?) on conflict do nothing", "hotel-local-app-v1");
    }

    private Hotel hotel(String slug, String name, String city, String cityCode, String address, double distance) {
        Hotel hotel = new Hotel();
        hotel.setSlug(slug);
        hotel.setName(name);
        hotel.setTitle(name);
        hotel.setCity(city);
        hotel.setCityCode(cityCode);
        hotel.setAddress(address);
        hotel.setDistanceFromCityCenter(distance);
        hotel.setTimeZone("Europe/Moscow");
        hotel.setDescription("Вымышленный отель для локального знакомства с сервисом. Реального размещения нет.");
        hotel.setPublished(true);
        hotel.setDemo(true);
        return hotels.save(hotel);
    }

    private void room(Hotel hotel, String name, int number, String price, int capacity) {
        Room room = new Room();
        room.setHotel(hotel);
        room.setName(name);
        room.setNumber(number);
        room.setPrice(new BigDecimal(price));
        room.setCapacity(capacity);
        room.setDescription("Вымышленная комната для локального знакомства с бронированием.");
        room.setPublished(true);
        rooms.save(room);
    }
}
