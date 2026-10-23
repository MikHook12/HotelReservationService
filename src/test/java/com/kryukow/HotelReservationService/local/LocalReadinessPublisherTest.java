package com.kryukow.HotelReservationService.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalReadinessPublisherTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publishesOnlyLaunchIdentityWithPrivatePermissionsAndNeverOverwrites() throws Exception {
        Path marker = directory.resolve("ready.json");
        MockEnvironment environment = environment(marker);
        environment.setProperty("app.local.password", "secret-must-not-appear-in-marker");
        var publisher = new LocalReadinessPublisher(environment, mapper);
        publisher.publish();
        var content = mapper.readTree(Files.readString(marker));
        assertThat(content.size()).isEqualTo(2);
        assertThat(content.get("environmentId").asText()).isEqualTo("hotel-local-app-v1");
        assertThat(content.get("runId").asText()).isEqualTo("first-launch-id-123456");
        assertThat(Files.getPosixFilePermissions(marker))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));

        environment.setProperty("app.local.run-id", "second-launch-id-12345");
        assertThatThrownBy(publisher::publish).isInstanceOf(IllegalStateException.class);
        assertThat(mapper.readTree(Files.readString(marker))).isEqualTo(content);
    }

    @Test
    void refusesSymlinkedParentAndInvalidRunIdentity() throws Exception {
        Path alias = directory.resolve("alias");
        Path actual = Files.createDirectory(directory.resolve("actual"));
        Files.createSymbolicLink(alias, actual);
        var environment = environment(alias.resolve("ready.json"));
        var publisher = new LocalReadinessPublisher(environment, mapper);
        assertThatThrownBy(publisher::publish).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(actual.resolve("ready.json"))).isFalse();
        environment.setProperty("app.local.ready-file", directory.resolve("ready.json").toString());
        environment.setProperty("app.local.run-id", "short");
        assertThatThrownBy(publisher::publish).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(directory.resolve("ready.json"))).isFalse();
    }

    @Test
    void doesNothingWithoutExplicitReadinessPath() {
        new LocalReadinessPublisher(new MockEnvironment(), mapper).publish();
    }

    private MockEnvironment environment(Path marker) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        environment.setProperty("app.local.environment-id", "hotel-local-app-v1");
        environment.setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:15433/hotel_local");
        environment.setProperty("spring.datasource.username", "hotel_local");
        environment.setProperty("server.address", "127.0.0.1");
        environment.setProperty("app.local.ready-file", marker.toString());
        environment.setProperty("app.local.run-id", "first-launch-id-123456");
        return environment;
    }
}
