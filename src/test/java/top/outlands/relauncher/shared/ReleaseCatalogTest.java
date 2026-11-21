package top.outlands.relauncher.shared;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReleaseCatalogTest {
    private static final String OFFICIAL = "https://maven.outlands.top/releases/top/outlands/outlands/";
    private static final String HEADER = "outlands-release-catalog-v1\n";
    private static final String PINNED = PackSettings.DEFAULT_VERSION;
    private static final String KNOWN = "2026.10.23";
    private static final byte[] HASH = ascii("0123456789aBcDeF0123456789aBcDeF0123456789aBcDeF0123456789aBcDeF\r\n");

    @TempDir Path temp;

    @Test
    void probesOnlyValidDatedVersionsAtOfficialEndpointsAndSortsNumericRevisions() throws Exception {
        List<URI> probes = Collections.synchronizedList(new ArrayList<>());
        byte[] metadata = metadata("2026.11.20-2", "2026.10.25", "2026.11.20", "2026.11.20-10", "2026.10.24",
                "2026.11.20-10", "0.6.13-alpha+build.30", "../../bad", "2026.02.30", "2026.11.20-01", PINNED);
        List<String> presets = ReleaseCatalog.refresh(temp.toRealPath(), " http://proxy.example:8888 ", (uri, proxy, limit) -> {
            assertEquals(Proxy.Type.HTTP, proxy.type());
            assertEquals(InetSocketAddress.createUnresolved("proxy.example", 8888), proxy.address());
            if (uri.equals(URI.create(OFFICIAL + "maven-metadata.xml"))) {
                assertEquals(256 * 1024, limit);
                return metadata;
            }
            assertEquals(128, limit);
            probes.add(uri);
            if (uri.equals(sidecar(PINNED)) || uri.equals(sidecar("2026.10.25"))) return null;
            if (uri.equals(sidecar("2026.10.24"))) return ascii("not a checksum");
            return HASH;
        });
        assertEquals(Arrays.asList("2026.11.20-10", "2026.11.20-2", "2026.11.20", PINNED), presets);
        assertEquals(presets, ReleaseCatalog.cached(temp.toRealPath()));
        assertEquals(6, probes.size());
        assertTrue(probes.containsAll(Arrays.asList(sidecar("2026.11.20-10"), sidecar("2026.11.20-2"),
                sidecar("2026.11.20"), sidecar("2026.10.24"), sidecar("2026.10.25"), sidecar(PINNED))));
        assertThrows(UnsupportedOperationException.class, () -> presets.add(KNOWN));
    }

    @Test
    void boundsProbesWithoutForgettingVerifiedHistory() throws Exception {
        Path dir = temp.toRealPath();
        ReleaseCatalog.refresh(dir, "", withMetadata(metadata(KNOWN), HASH));
        String[] published = new String[41];
        published[0] = KNOWN;
        for (int n = 1; n < published.length; n++) published[n] = "2026.12.01-" + n;
        List<URI> probes = Collections.synchronizedList(new ArrayList<>());
        List<String> presets = ReleaseCatalog.refresh(dir, "", (uri, proxy, limit) -> {
            assertEquals(null, proxy);
            if (uri.equals(URI.create(OFFICIAL + "maven-metadata.xml"))) return metadata(published);
            probes.add(uri);
            return HASH;
        });
        assertEquals(32, probes.size());
        assertTrue(probes.contains(sidecar("2026.12.01-9")));
        assertFalse(probes.contains(sidecar("2026.12.01-8")));
        assertFalse(probes.contains(sidecar(KNOWN)));
        assertEquals(34, presets.size());
        assertEquals("2026.12.01-40", presets.get(0));
        assertEquals(KNOWN, presets.get(presets.size() - 1));
        assertEquals(presets, ReleaseCatalog.refresh(dir, "", withMetadata(metadata(KNOWN), null)));
        assertEquals(presets, ReleaseCatalog.cached(dir));
    }

    @Test
    void doesNotEvictAtHistoryCapacity() throws Exception {
        Path dir = temp.toRealPath();
        Path cache = dir.resolve("release-catalog.txt");
        StringBuilder contents = new StringBuilder(HEADER);
        for (int n = 2048; n > 0; n--) contents.append("2026.12.01-").append(n).append('\n');
        byte[] previous = ascii(contents.toString());
        Files.write(cache, previous);
        assertEquals(2049, ReleaseCatalog.cached(dir).size());
        assertThrows(IOException.class, () -> ReleaseCatalog.refresh(dir, "", withMetadata(metadata(KNOWN), HASH)));
        assertArrayEquals(previous, Files.readAllBytes(cache));
    }

    @Test
    void preservesCacheOnTransportFailureEvenWhenAnotherProbeSucceeds() throws Exception {
        Path dir = temp.toRealPath();
        List<String> before = ReleaseCatalog.refresh(dir, "", withMetadata(metadata(KNOWN), HASH));
        Path cache = dir.resolve("release-catalog.txt");
        byte[] previous = Files.readAllBytes(cache);
        IOException error = assertThrows(IOException.class, () -> ReleaseCatalog.refresh(dir, "", (uri, proxy, limit) -> {
            if (uri.equals(URI.create(OFFICIAL + "maven-metadata.xml"))) return metadata("2026.11.20", "2026.11.19");
            if (uri.equals(sidecar("2026.11.19"))) throw new IOException("simulated HTTP 503");
            return HASH;
        }));
        assertEquals("simulated HTTP 503", error.getMessage());
        assertArrayEquals(previous, Files.readAllBytes(cache));
        assertEquals(before, ReleaseCatalog.cached(dir));
    }

    @Test
    void rejectsUnsafeOrOversizedMetadataWithoutTouchingCache() throws Exception {
        Path dir = temp.toRealPath();
        List<String> before = ReleaseCatalog.refresh(dir, "", withMetadata(metadata(KNOWN), HASH));
        List<byte[]> invalid = Arrays.asList(null, new byte[256 * 1024 + 1], ascii("<broken>"),
                ascii("<metadata><versioning/></metadata>"),
                ascii("<!DOCTYPE metadata [<!ENTITY x SYSTEM 'file:///must-not-read'>]>"
                        + "<metadata><versioning><versions><version>&x;</version></versions></versioning></metadata>"));
        for (byte[] response : invalid) {
            assertThrows(IOException.class, () -> ReleaseCatalog.refresh(dir, "", (uri, proxy, limit) -> {
                assertEquals(URI.create(OFFICIAL + "maven-metadata.xml"), uri);
                return response;
            }));
            assertEquals(before, ReleaseCatalog.cached(dir));
        }
    }

    @Test
    void rejectsOversizedSidecarsAndUsesPackProxyValidation() throws Exception {
        Path dir = temp.toRealPath();
        assertThrows(IOException.class, () -> ReleaseCatalog.refresh(dir, "http://user@host:8080", (uri, proxy, limit) -> {
            throw new AssertionError("invalid proxy must not download");
        }));
        byte[] oversized = Arrays.copyOf(HASH, 129);
        Arrays.fill(oversized, HASH.length, oversized.length, (byte) ' ');
        assertEquals(Collections.singletonList(PINNED), ReleaseCatalog.refresh(dir, "socks://[::1]:1080", (uri, proxy, limit) -> {
            assertEquals(Proxy.Type.SOCKS, proxy.type());
            assertEquals(InetSocketAddress.createUnresolved("::1", 1080), proxy.address());
            return uri.equals(URI.create(OFFICIAL + "maven-metadata.xml")) ? metadata(KNOWN) : oversized;
        }));
    }

    @Test
    void cacheReadsAreNonCreatingAndStrict() throws Exception {
        Path dir = temp.toRealPath();
        Path missing = dir.resolve("missing").resolve("nested");
        assertEquals(Collections.singletonList(PINNED), ReleaseCatalog.cached(missing));
        assertFalse(Files.exists(missing.getParent()));
        assertEquals(Collections.singletonList(PINNED), ReleaseCatalog.cached(dir));
        Path cache = dir.resolve("release-catalog.txt");
        assertFalse(Files.exists(cache));
        for (String invalid : Arrays.asList("", KNOWN + "\n", HEADER + KNOWN, HEADER + "\n", HEADER + " " + KNOWN + "\n",
                HEADER + PINNED + "\n", HEADER + KNOWN + "\n" + KNOWN + "\n",
                HEADER + KNOWN + "\n2026.11.20\n", HEADER + "2026.02.30\n", HEADER + KNOWN + "\r\n")) {
            Files.write(cache, ascii(invalid));
            assertThrows(IOException.class, () -> ReleaseCatalog.cached(dir), invalid);
        }
        Files.write(cache, new byte[40 * 1024 + 1]);
        assertThrows(IOException.class, () -> ReleaseCatalog.cached(dir));
        Files.delete(cache);
        Files.createDirectory(cache);
        assertThrows(IOException.class, () -> ReleaseCatalog.cached(dir));
    }

    @Test
    void repairsOnlyCorruptRegularContentAfterSuccessfulRefresh() throws Exception {
        Path dir = temp.toRealPath();
        Path cache = dir.resolve("release-catalog.txt");
        List<String> expected = Arrays.asList(PINNED, KNOWN);
        for (byte[] corrupt : Arrays.asList(ascii(HEADER + "truncated"), new byte[40 * 1024 + 1])) {
            Files.write(cache, corrupt);
            assertThrows(IOException.class, () -> ReleaseCatalog.cached(dir));
            assertThrows(IOException.class, () -> ReleaseCatalog.refresh(dir, "", (uri, proxy, limit) -> {
                throw new IOException("offline");
            }));
            assertArrayEquals(corrupt, Files.readAllBytes(cache));
            assertEquals(expected, ReleaseCatalog.refresh(dir, "", withMetadata(metadata(KNOWN), HASH)));
            assertEquals(expected, ReleaseCatalog.cached(dir));
        }
        Files.delete(cache);
        Files.createDirectory(cache);
        assertThrows(IOException.class, () -> ReleaseCatalog.refresh(dir, "", withMetadata(metadata(KNOWN), HASH)));
        assertTrue(Files.isDirectory(cache));
    }

    @Test
    void rejectsSymlinkedCatalogAndRedirectedParentWhenSupported() throws Exception {
        Path dir = temp.toRealPath();
        Path target = Files.createDirectory(dir.resolve("real"));
        Path targetFile = Files.write(target.resolve("release-catalog.txt"), ascii(HEADER + KNOWN + "\n"));
        Path cache = dir.resolve("release-catalog.txt");
        Path redirected = dir.resolve("redirected");
        try {
            Files.createSymbolicLink(cache, targetFile);
            Files.createSymbolicLink(redirected, target);
        } catch (IOException | UnsupportedOperationException ex) {
            assumeTrue(false, "Symbolic links unavailable: " + ex);
        }
        assertThrows(IOException.class, () -> ReleaseCatalog.cached(dir));
        assertThrows(IOException.class, () -> ReleaseCatalog.cached(redirected));
        assertThrows(IOException.class, () -> ReleaseCatalog.refresh(dir, "", withMetadata(metadata(KNOWN), HASH)));
        assertArrayEquals(ascii(HEADER + KNOWN + "\n"), Files.readAllBytes(targetFile));
    }

    @Test
    void interruptionCancelsNetworkWorkWithoutWritingCache() throws Exception {
        Path dir = temp.toRealPath();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        Thread caller = new Thread(() -> {
            try {
                ReleaseCatalog.refresh(dir, "", (uri, proxy, limit) -> {
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                        throw new AssertionError();
                    } finally {
                        cancelled.countDown();
                    }
                });
                fail("refresh should have been interrupted");
            } catch (Throwable ex) {
                failure.set(ex);
            }
        });
        caller.setDaemon(true);
        caller.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        } finally {
            caller.interrupt();
            caller.join(5_000);
        }
        assertFalse(caller.isAlive());
        assertTrue(failure.get() instanceof InterruptedException, String.valueOf(failure.get()));
        assertTrue(cancelled.await(5, TimeUnit.SECONDS));
        assertFalse(Files.exists(dir.resolve("release-catalog.txt")));
    }

    private static ReleaseCatalog.Downloader withMetadata(byte[] metadata, byte[] checksum) {
        return (uri, proxy, limit) -> uri.equals(URI.create(OFFICIAL + "maven-metadata.xml")) ? metadata : checksum;
    }

    private static URI sidecar(String version) {
        return URI.create(OFFICIAL + version + "/outlands-" + version + "-mmc.zip.sha256");
    }

    private static byte[] metadata(String... versions) {
        return ascii("<metadata><versioning><versions><version>" + String.join("</version><version>", versions)
                + "</version></versions></versioning></metadata>");
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
