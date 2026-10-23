package com.kryukow.HotelReservationService.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;

/** Readiness is a lifecycle fact, independent of how many hotels the user keeps in the catalogue. */
@Component
@Profile("local")
@RequiredArgsConstructor
public class LocalReadinessPublisher {
    private final Environment environment;
    private final ObjectMapper objectMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void publish() {
        String configuredPath = environment.getProperty("app.local.ready-file");
        if (configuredPath == null || configuredPath.isBlank()) return;
        LocalEnvironmentGuard.verify(environment);
        String runId = environment.getProperty("app.local.run-id");
        if (runId == null || !runId.matches("[A-Za-z0-9_-]{16,128}")) {
            throw new IllegalArgumentException("Local readiness requires a valid per-launch run identifier");
        }
        Path path = Path.of(configuredPath);
        Path parent = path.getParent();
        if (!path.isAbsolute() || !path.equals(path.normalize()) || parent == null
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            throw new IllegalArgumentException("Local readiness requires an absolute normalized path in an existing regular directory");
        }
        try {
            byte[] json = objectMapper.writeValueAsBytes(Map.of(
                    "environmentId", "hotel-local-app-v1", "runId", runId));
            // CREATE_NEW also refuses existing symbolic links; never replace another launch's marker.
            try (SeekableByteChannel channel = Files.newByteChannel(path,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                ByteBuffer contents = ByteBuffer.wrap(json);
                while (contents.hasRemaining()) channel.write(contents);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to publish local readiness marker", failure);
        }
    }
}
