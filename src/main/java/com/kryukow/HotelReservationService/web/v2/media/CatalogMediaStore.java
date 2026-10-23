package com.kryukow.HotelReservationService.web.v2.media;

import com.kryukow.HotelReservationService.web.v2.common.V2ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Local, immutable catalogue images. Uploaded metadata and names are never persisted. */
@Service
@ConditionalOnProperty(name = "app.web.v2.enabled", havingValue = "true")
public class CatalogMediaStore {
    public static final int MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
    private static final int MAX_STORED_BYTES = 10 * 1024 * 1024;
    private static final byte[] PNG_MAGIC = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final Pattern FILE_NAME = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)");
    private final Path directory;

    public CatalogMediaStore(@Value("${app.media.directory:}") String directory) {
        this.directory = directory == null || directory.isBlank() ? null : Path.of(directory).toAbsolutePath().normalize();
    }

    public String upload(MultipartFile file) {
        requireConfigured();
        if (file == null || file.isEmpty() || file.getSize() > MAX_UPLOAD_BYTES) throw invalid();
        byte[] input;
        try (var stream = file.getInputStream()) {
            input = stream.readNBytes(MAX_UPLOAD_BYTES + 1);
        } catch (IOException ex) {
            throw invalid();
        }
        if (input.length == 0 || input.length > MAX_UPLOAD_BYTES) throw invalid();
        String format = imageFormat(input);
        byte[] sanitized = sanitize(input, format);
        Path temporary = null;
        try {
            prepareDirectory();
            temporary = Files.createTempFile(directory, ".upload-", ".tmp", PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
            Files.write(temporary, sanitized, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS);
            String filename = UUID.randomUUID() + "." + format;
            ensureSafeDirectory();
            Files.move(temporary, directory.resolve(filename), StandardCopyOption.ATOMIC_MOVE);
            return "/media/" + filename;
        } catch (IOException | UnsupportedOperationException ex) {
            throw unavailable();
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* No user data is removed. */ }
            }
        }
    }

    public void assertUsablePath(String url) {
        if (url == null || !url.startsWith("/media/") || !validFilename(url.substring(7))) throw invalid();
        requireConfigured();
        try {
            ensureSafeDirectory();
            Path image = directory.resolve(url.substring(7));
            if (!Files.isRegularFile(image, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(image)) throw invalid();
        } catch (IOException ex) {
            throw invalid();
        }
    }

    public StoredMedia read(String filename) {
        if (!validFilename(filename)) throw notFound();
        requireConfigured();
        try {
            ensureSafeDirectory();
            Path image = directory.resolve(filename);
            if (!Files.isRegularFile(image, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(image)) throw notFound();
            try (SeekableByteChannel channel = Files.newByteChannel(image, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                long size = channel.size();
                if (size <= 0 || size > MAX_STORED_BYTES) throw notFound();
                ByteBuffer buffer = ByteBuffer.allocate((int) size);
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) throw notFound();
                }
                return new StoredMedia(buffer.array(), filename.endsWith(".jpg") ? "image/jpeg" : "image/png");
            }
        } catch (IOException ex) {
            throw notFound();
        }
    }

    private static String imageFormat(byte[] input) {
        if (input.length >= PNG_MAGIC.length && Arrays.equals(PNG_MAGIC, Arrays.copyOf(input, PNG_MAGIC.length))) return "png";
        if (input.length >= 3 && (input[0] & 255) == 255 && (input[1] & 255) == 216 && (input[2] & 255) == 255) return "jpg";
        throw invalid();
    }

    private static byte[] sanitize(byte[] input, String format) {
        ImageReader reader = null;
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalid();
            reader = readers.next();
            String detected = reader.getFormatName();
            if (!(format.equals("png") && detected.equalsIgnoreCase("png")) &&
                    !(format.equals("jpg") && detected.equalsIgnoreCase("JPEG"))) throw invalid();
            reader.setInput(stream, true, true);
            boolean[] warning = {false};
            reader.addIIOReadWarningListener((source, message) -> warning[0] = true);
            int width = reader.getWidth(0), height = reader.getHeight(0);
            if (width < 1 || height < 1 || width > 6000 || height > 6000 || (long) width * height > 20_000_000) throw invalid();
            BufferedImage decoded = reader.read(0);
            if (decoded == null || warning[0]) throw invalid();
            // A new pixel buffer drops metadata, ancillary payloads, colour profiles and extra frames.
            BufferedImage clean = new BufferedImage(width, height,
                    format.equals("jpg") ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = clean.createGraphics();
            try { graphics.drawImage(decoded, 0, 0, null); } finally { graphics.dispose(); }
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(clean, format, output) || output.size() > MAX_STORED_BYTES) throw invalid();
            return output.toByteArray();
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof V2ApiException api) throw api;
            throw invalid();
        } finally {
            if (reader != null) reader.dispose();
        }
    }

    private void prepareDirectory() throws IOException {
        ensureNoSymlinkAncestors();
        Files.createDirectories(directory);
        ensureSafeDirectory();
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }

    private void ensureSafeDirectory() throws IOException {
        ensureNoSymlinkAncestors();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unavailable media directory");
    }

    private void ensureNoSymlinkAncestors() throws IOException {
        Path current = directory.getRoot();
        for (Path component : directory) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) throw new IOException("Unsafe media directory");
        }
    }

    private void requireConfigured() { if (directory == null) throw unavailable(); }
    private static boolean validFilename(String value) { return value != null && FILE_NAME.matcher(value).matches(); }
    private static V2ApiException invalid() {
        return new V2ApiException(400, "VALIDATION_FAILED", "Загрузите корректное фото JPEG или PNG до 5 МБ, не больше 6000 × 6000 и 20 миллионов пикселей.")
                .withFieldError("photos", "INVALID_FORMAT", "Некорректное фото.");
    }
    private static V2ApiException unavailable() { return new V2ApiException(503, "SERVICE_UNAVAILABLE", "Хранилище фотографий недоступно."); }
    private static V2ApiException notFound() { return new V2ApiException(404, "NOT_FOUND", "Фотография не найдена."); }
    public record StoredMedia(byte[] bytes, String contentType) { }
}
