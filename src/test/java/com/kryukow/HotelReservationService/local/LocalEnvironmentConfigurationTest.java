package com.kryukow.HotelReservationService.local;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalEnvironmentConfigurationTest {
    @Test
    void rejectsWrongDatabaseBeforeDatabaseSingletonCreationEvenWithSeedDisabled() {
        AtomicBoolean databaseStarted = new AtomicBoolean();
        MockEnvironment environment = localEnvironment();
        environment.setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/working_database");
        environment.setProperty("app.local.seed-enabled", "false");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.register(LocalEnvironmentConfiguration.class);
            context.registerBean("fakeDatabase", Object.class, () -> {
                databaseStarted.set(true);
                return new Object();
            });
            assertThatThrownBy(context::refresh)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dedicated loopback local environment");
            assertThat(databaseStarted).isFalse();
        }
    }

    @Test
    void acceptsDedicatedLocalEnvironmentBeforeSingletonCreation() {
        AtomicBoolean databaseStarted = new AtomicBoolean();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(localEnvironment());
            context.register(LocalEnvironmentConfiguration.class);
            context.registerBean("fakeDatabase", Object.class, () -> {
                databaseStarted.set(true);
                return new Object();
            });
            context.refresh();
            assertThat(databaseStarted).isTrue();
        }
    }

    private MockEnvironment localEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        environment.setProperty("app.local.environment-id", "hotel-local-app-v1");
        environment.setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:15433/hotel_local");
        environment.setProperty("spring.datasource.username", "hotel_local");
        environment.setProperty("server.address", "127.0.0.1");
        return environment;
    }
}
