package com.kryukow.HotelReservationService.local;

import org.springframework.core.env.Environment;

/** Shared by the early bean-factory check and the initializer's final write guard. */
final class LocalEnvironmentGuard {
    private LocalEnvironmentGuard() { }

    static void verify(Environment environment) {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length != 1 || !"local".equals(profiles[0])
                || !"hotel-local-app-v1".equals(environment.getProperty("app.local.environment-id"))
                || !"jdbc:postgresql://127.0.0.1:15433/hotel_local".equals(environment.getProperty("spring.datasource.url"))
                || !"hotel_local".equals(environment.getProperty("spring.datasource.username"))
                || !"127.0.0.1".equals(environment.getProperty("server.address"))) {
            throw new IllegalStateException("Local profile is allowed only in the dedicated loopback local environment");
        }
    }
}
