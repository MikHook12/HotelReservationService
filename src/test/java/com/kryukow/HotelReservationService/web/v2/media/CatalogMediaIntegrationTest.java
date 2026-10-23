package com.kryukow.HotelReservationService.web.v2.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kryukow.HotelReservationService.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP multipart parsing, durable sessions and the complete browser security filter chain. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"app.web.v2.enabled=true",
        "app.web.v2.quote-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class CatalogMediaIntegrationTest extends BaseIntegrationTest {
    private static final Path MEDIA = temporaryDirectory();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired CatalogMediaStore store;

    @DynamicPropertySource static void media(DynamicPropertyRegistry properties) {
        properties.add("app.media.directory", MEDIA::toString);
    }
    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("hotel-media-integration-").toRealPath(); }
        catch (IOException ex) { throw new IllegalStateException(ex); }
    }
    @AfterAll static void cleanupOwnTemporaryImages() throws IOException {
        try (var paths = Files.walk(MEDIA)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    record Session(String cookie, String csrf) { }
    record Multipart(String contentType, byte[] bytes) { }
    URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    HttpResponse<byte[]> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    Session csrf(String cookie) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/v2/auth/csrf")).GET();
        if (cookie != null) request.header("Cookie", cookie);
        var response = send(request);
        assertThat(response.statusCode()).isEqualTo(200);
        String updated = response.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith("__Host-hotel-session="))
                .map(value -> value.split(";", 2)[0]).findFirst().orElse(cookie);
        return new Session(updated, json.readTree(response.body()).get("token").asText());
    }
    Session login(String role) throws Exception {
        String name = "media-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(name,password,email,role) VALUES(?,?,?,?)", name,
                encoder.encode("password"), name + "@example.invalid", role);
        var before = csrf(null);
        var response = send(HttpRequest.newBuilder(uri("/api/v2/auth/login"))
                .header("Cookie", before.cookie()).header("X-CSRF-TOKEN", before.csrf())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username", name, "password", "password")))));
        assertThat(response.statusCode()).isEqualTo(200);
        String cookie = response.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith("__Host-hotel-session="))
                .map(value -> value.split(";", 2)[0]).findFirst().orElseThrow();
        return csrf(cookie);
    }
    Multipart multipart(byte[] image) throws IOException {
        String boundary = "hotel-test-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.png\"\r\n" +
                "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.write(image);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return new Multipart("multipart/form-data; boundary=" + boundary, body.toByteArray());
    }
    HttpResponse<byte[]> upload(byte[] image, Session session, boolean includeCsrf) throws Exception {
        var body = multipart(image);
        var request = HttpRequest.newBuilder(uri("/api/v2/admin/media"))
                .header("Content-Type", body.contentType()).POST(HttpRequest.BodyPublishers.ofByteArray(body.bytes()));
        if (session != null) {
            request.header("Cookie", session.cookie());
            if (includeCsrf) request.header("X-CSRF-TOKEN", session.csrf());
        }
        return send(request);
    }
    byte[] image() throws IOException {
        var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, 0x336699);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    @Test void multipartRequiresAuthenticatedAdminAndHeaderCsrf() throws Exception {
        byte[] image = image();
        long before;
        try (var files = Files.list(MEDIA)) { before = files.count(); }
        // An anonymous CSRF handshake distinguishes authentication denial from missing-token denial.
        assertThat(upload(image, csrf(null), true).statusCode()).isEqualTo(401);
        assertThat(upload(image, login("USER"), true).statusCode()).isEqualTo(403);
        assertThat(upload(image, login("ADMIN"), false).statusCode()).isEqualTo(403);
        try (var files = Files.list(MEDIA)) { assertThat(files.count()).isEqualTo(before); }
    }

    @Test void realMultipartUploadReturnsPublicSanitizedImageThroughSecurityChain() throws Exception {
        var response = upload(image(), login("ADMIN"), true);
        assertThat(response.statusCode()).isEqualTo(201);
        String url = json.readTree(response.body()).get("url").asText();
        assertThat(url).matches("/media/[0-9a-f-]{36}\\.png");
        var publicResponse = send(HttpRequest.newBuilder(uri("/api/v2" + url)).GET());
        assertThat(publicResponse.statusCode()).isEqualTo(200);
        assertThat(publicResponse.headers().firstValue("content-type")).contains("image/png");
        assertThat(publicResponse.headers().firstValue("x-content-type-options")).contains("nosniff");
        assertThat(publicResponse.body()).isEqualTo(store.read(url.substring(7)).bytes());
        var decoded = ImageIO.read(new java.io.ByteArrayInputStream(publicResponse.body()));
        assertThat(decoded.getRGB(1, 1) & 0xffffff).isEqualTo(0x336699);
    }

    @Test void realMultipartRejectsSvgAndOversizedBody() throws Exception {
        var admin = login("ADMIN");
        var svg = upload("<svg onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8), admin, true);
        assertThat(svg.statusCode()).isEqualTo(400);
        assertThat(json.readTree(svg.body()).get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(upload(new byte[6 * 1024 * 1024 + 1], admin, true).statusCode()).isEqualTo(413);
    }
}
