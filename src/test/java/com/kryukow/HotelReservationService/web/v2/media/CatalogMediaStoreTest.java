package com.kryukow.HotelReservationService.web.v2.media;

import com.kryukow.HotelReservationService.web.v2.common.V2ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogMediaStoreTest {
    @TempDir Path temporary;

    private Path root() throws Exception { return temporary.toRealPath().resolve("media"); }
    private CatalogMediaStore store() throws Exception { return new CatalogMediaStore(root().toString()); }
    private static byte[] image(String format) throws Exception {
        var output = new ByteArrayOutputStream();
        var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x336699);
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }
    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "../../untrusted.svg", "image/svg+xml", bytes);
    }
    private static void rejects(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(V2ApiException.class,
                exception -> assertThat(exception.getStatus()).isEqualTo(status));
    }

    @Test void acceptsRealPngDespiteMisleadingNameAndMimeAndDropsTrailingPayload() throws Exception {
        byte[] original = image("png");
        byte[] payload = "<script>metadata-that-must-not-survive</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] withPayload = Arrays.copyOf(original, original.length + payload.length);
        System.arraycopy(payload, 0, withPayload, original.length, payload.length);
        var store = store();
        String url = store.upload(file(withPayload));
        assertThat(url).matches("/media/[0-9a-f-]{36}\\.png");
        store.assertUsablePath(url);
        var saved = store.read(url.substring(7));
        assertThat(saved.contentType()).isEqualTo("image/png");
        assertThat(new String(saved.bytes(), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("metadata-that-must-not-survive");
        var decoded = ImageIO.read(new java.io.ByteArrayInputStream(saved.bytes()));
        assertThat(decoded.getWidth()).isEqualTo(3);
        assertThat(decoded.getRGB(0, 0) & 0xffffff).isEqualTo(0x336699);
        assertThat(Files.getPosixFilePermissions(root())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(root().resolve(url.substring(7))))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        try (var files = Files.list(root())) { assertThat(files.count()).isEqualTo(1); }
    }

    @Test void acceptsJpegAndUsesFreshFileNamesForRepeatedUploads() throws Exception {
        var store = store();
        String first = store.upload(file(image("jpg")));
        String second = store.upload(file(image("jpg")));
        assertThat(first).endsWith(".jpg").isNotEqualTo(second);
        assertThat(store.read(first.substring(7)).contentType()).isEqualTo("image/jpeg");
    }

    @Test void rejectsSvgGifEmptyAndFalseMagicWithoutCreatingDirectory() throws Exception {
        var store = store();
        for (byte[] bytes : new byte[][] {new byte[0], "<svg/>".getBytes(), image("gif"),
                new byte[] {(byte)255, (byte)216, (byte)255, 1, 2, 3}}) {
            rejects(400, () -> store.upload(file(bytes)));
        }
        assertThat(Files.exists(root())).isFalse();
    }

    @Test void rejectsTruncatedImageWithoutCreatingDirectory() throws Exception {
        var store = store();
        byte[] truncated = Arrays.copyOf(image("png"), 35);
        rejects(400, () -> store.upload(file(truncated)));
        assertThat(Files.exists(root())).isFalse();
    }

    @Test void rejectsOversizedReportedOrActualBytes() throws Exception {
        var store = store();
        byte[] oversized = new byte[CatalogMediaStore.MAX_UPLOAD_BYTES + 1];
        rejects(400, () -> store.upload(file(oversized)));
        var misleading = new MockMultipartFile("file", oversized) {
            @Override public long getSize() { return 1; }
        };
        rejects(400, () -> store.upload(misleading));
        assertThat(Files.exists(root())).isFalse();
    }

    @Test void rejectsDimensionAndPixelBombsBeforeDecode() throws Exception {
        var store = store();
        for (int[] bounds : new int[][] {{6001, 1}, {1, 6001}, {6000, 4000}, {0, 1}}) {
            byte[] png = image("png");
            ByteBuffer.wrap(png, 16, 8).putInt(bounds[0]).putInt(bounds[1]);
            CRC32 checksum = new CRC32();
            checksum.update(png, 12, 17);
            ByteBuffer.wrap(png, 29, 4).putInt((int) checksum.getValue());
            rejects(400, () -> store.upload(file(png)));
        }
        assertThat(Files.exists(root())).isFalse();
    }

    @Test void rejectsTraversalQueriesExternalUrlsAndMissingReferences() throws Exception {
        var store = store();
        for (String value : new String[] {"/media/../secret", "/media/%2e%2e", "https://host/image.png",
                "/media/00000000-0000-0000-0000-000000000000.png?x=1", "/media/00000000-0000-0000-0000-000000000000.png"}) {
            rejects(400, () -> store.assertUsablePath(value));
        }
        rejects(404, () -> store.read("../secret"));
        rejects(404, () -> store.read("00000000-0000-0000-0000-000000000000.png"));
    }

    @Test void rejectsSymbolicFileLinksWithoutReadingTarget() throws Exception {
        var store = store();
        String url = store.upload(file(image("png")));
        Path image = root().resolve(url.substring(7));
        Files.delete(image);
        Path external = temporary.toRealPath().resolve("secret");
        Files.writeString(external, "private");
        Files.createSymbolicLink(image, external);
        rejects(400, () -> store.assertUsablePath(url));
        rejects(404, () -> store.read(url.substring(7)));
        assertThat(Files.readString(external)).isEqualTo("private");
    }

    @Test void rejectsSymbolicDirectoryAndAncestors() throws Exception {
        Path actual = Files.createDirectory(temporary.toRealPath().resolve("actual"));
        Path linked = temporary.toRealPath().resolve("linked");
        Files.createSymbolicLink(linked, actual);
        for (Path path : new Path[] {linked, linked.resolve("nested")}) {
            var store = new CatalogMediaStore(path.toString());
            var upload = file(image("png"));
            rejects(503, () -> store.upload(upload));
        }
        try (var files = Files.list(actual)) { assertThat(files.count()).isZero(); }
    }

    @Test void unconfiguredStorageFailsClosedWithoutFilesystemPaths() {
        var store = new CatalogMediaStore("");
        assertThatThrownBy(() -> store.upload(file(new byte[] {1})))
                .isInstanceOfSatisfying(V2ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(503);
                    assertThat(exception.getMessage()).doesNotContain("/Users", "/tmp");
                });
        rejects(404, () -> store.read("not-a-file"));
    }
}
