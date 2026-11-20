package top.outlands.relauncher.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadOptionsTest {
    private static final String LIBRARY = "example/test/1/test-1.jar";
    private static final String CENTRAL = "https://repo.maven.apache.org/maven2/";
    private static final String ALIYUN = "https://maven.aliyun.com/repository/central/";
    private static final String MIRROR = "https://mirror.example/cache/central";
    private static final byte[] GOOD = {1, 2, 3};
    private static final byte[] BAD = {4, 5, 6};

    @TempDir
    Path root;

    @Test
    void outlandsReleaseAndChecksumFallBackToTheSameVersionOnTheReleaseMirror() throws IOException {
        String relative = "top/outlands/outlands/2026.11.14/outlands-2026.11.14-mmc.zip";
        String hash = VerifiedCache.hex(VerifiedCache.digest("SHA-256").digest(GOOD));
        List<URI> seen = new ArrayList<>();
        VerifiedCache.Downloader downloader = uri -> {
            seen.add(uri);
            if (uri.getHost().equals("maven.outlands.top")) throw new IOException("offline origin");
            return new ByteArrayInputStream(uri.getPath().endsWith(".sha256")
                    ? hash.getBytes(StandardCharsets.US_ASCII) : GOOD);
        };
        InstallerMetadata.Artifact artifact = PrepareMain.customRelease(root, downloader, "2026.11.14");
        assertArrayEquals(GOOD, Files.readAllBytes(new VerifiedCache(root, downloader).obtain(artifact, null)));
        assertEquals(Arrays.asList(URI.create("https://maven.outlands.top/releases/" + relative + ".sha256"),
                URI.create("https://nexus.jsdu.cn/repository/" + relative + ".sha256"),
                URI.create("https://maven.outlands.top/releases/" + relative),
                URI.create("https://nexus.jsdu.cn/repository/" + relative)), seen);
        assertEquals(hash, PrepareMain.customRelease(root, uri -> {
            throw new AssertionError("Cached descriptor should not use the network");
        }, "2026.11.14").digest);
    }

    @Test
    void resolvesDirectoryWithoutCreatingItAndNormalizesMirrorRoot() throws IOException {
        Properties properties = new Properties();
        assertNull(DownloadOptions.read(properties, root).mavenMirror);
        assertNull(DownloadOptions.read(properties, root).librariesDirectory);
        properties.setProperty("mavenMirror", MIRROR);
        properties.setProperty("librariesDirectory", "launcher/../shared libraries");
        DownloadOptions options = DownloadOptions.read(properties, root);
        assertEquals(URI.create(MIRROR + "/"), options.mavenMirror);
        assertEquals(root.resolve("shared libraries"), options.librariesDirectory);
        assertFalse(Files.exists(options.librariesDirectory));
    }

    @Test
    void rejectsUnsafeEndpointsWithoutEchoingConfiguration() {
        reject("proxy", "https://localhost:8888", "http://localhost", "http://localhost:0",
                "socks://localhost:65536", "http://user:secret@localhost:8888", "http://localhost:8888/",
                "http://localhost:8888?a=b", "http://localhost:8888#fragment");
        reject("mavenMirror", "http://mirror.example", "https://user:secret@mirror.example/repo",
                "https://mirror.example:", "https://mirror.example/repo?token=secret",
                "https://mirror.example/repo#secret", "https://mirror.example/repo/../central",
                "https://mirror.example/%2e%2e/central", "https://mirror.example//", "https://mirror.example/%2fcentral");
    }

    @Test
    void readsVerifiedSharedLibraryBeforePrivateCacheWithoutWritingSharedFiles() throws IOException {
        Path privateRoot = root.resolve("cache");
        Path sharedRoot = root.resolve("shared");
        Path shared = sharedRoot.resolve(LIBRARY);
        Files.createDirectories(shared.getParent());
        Files.write(shared, BAD);
        List<URI> requests = new ArrayList<>();
        VerifiedCache cache = new VerifiedCache(privateRoot, uri -> {
            requests.add(uri);
            return new ByteArrayInputStream(GOOD);
        }, read("librariesDirectory", sharedRoot.toString()));
        InstallerMetadata.Artifact artifact = artifact(CENTRAL + LIBRARY);
        Path local = cache.obtain(artifact, null);
        assertTrue(local.startsWith(privateRoot.toRealPath()));
        assertEquals(Collections.singletonList(artifact.uri), requests);
        assertArrayEquals(BAD, Files.readAllBytes(shared));
        assertEquals(local, cache.obtain(artifact, null));
        assertEquals(1, requests.size());

        Files.write(shared, GOOD);
        assertEquals(shared.toRealPath(), cache.obtain(artifact, null));
        assertArrayEquals(GOOD, Files.readAllBytes(shared));
        assertEquals(1, requests.size());
    }

    @Test
    void sharedHitDoesNotCreatePrivateCache() throws IOException {
        Path privateRoot = root.resolve("unused-cache");
        Path shared = root.resolve("shared").resolve(LIBRARY);
        Files.createDirectories(shared.getParent());
        Files.write(shared, GOOD);
        VerifiedCache cache = new VerifiedCache(privateRoot, uri -> {
            throw new AssertionError("Should use the shared library without downloading");
        }, read("librariesDirectory", "shared"));
        assertEquals(shared.toRealPath(), cache.obtain(artifact(CENTRAL + LIBRARY), null));
        assertFalse(Files.exists(privateRoot));
    }

    @Test
    void refusesOverlappingRootsBeforeCreatingDirectories() throws IOException {
        Path shared = root.resolve("shared");
        Path nested = shared.resolve("private-cache");
        assertThrows(IOException.class, () -> new VerifiedCache(nested, uri -> null,
                read("librariesDirectory", shared.toString())));
        assertThrows(IOException.class, () -> new VerifiedCache(shared, uri -> null,
                read("librariesDirectory", nested.toString())));
        assertFalse(Files.exists(shared));
    }

    @Test
    void verifiesMirrorDownloadAndFallsBackToOfficialAndOriginalUrls() throws IOException {
        InstallerMetadata.Artifact artifact = artifact(ALIYUN + LIBRARY);
        List<URI> requests = new ArrayList<>();
        VerifiedCache cache = new VerifiedCache(root.resolve("cache"), uri -> {
            requests.add(uri);
            if (requests.size() == 1) return new ByteArrayInputStream(BAD);
            if (requests.size() == 2) throw new IOException("Unavailable");
            return new ByteArrayInputStream(GOOD);
        }, read("mavenMirror", MIRROR));
        assertArrayEquals(GOOD, Files.readAllBytes(cache.obtain(artifact, null)));
        assertEquals(Arrays.asList(URI.create(MIRROR + "/" + LIBRARY), URI.create(CENTRAL + LIBRARY), artifact.uri), requests);
    }

    @Test
    void digestOnlyArtifactRejectsWrongDigestAndReusesVerifiedDownload() throws IOException {
        InstallerMetadata.Artifact artifact = new InstallerMetadata.Artifact(LIBRARY, -1, "SHA-256",
                VerifiedCache.hex(VerifiedCache.digest("SHA-256").digest(GOOD)), URI.create(CENTRAL + LIBRARY),
                Collections.emptyList());
        Path cacheRoot = root.resolve("cache");
        VerifiedCache invalid = new VerifiedCache(cacheRoot, uri -> new ByteArrayInputStream(BAD));
        assertThrows(IOException.class, () -> invalid.obtain(artifact, null));
        VerifiedCache valid = new VerifiedCache(cacheRoot, uri -> new ByteArrayInputStream(GOOD));
        Path prepared = valid.obtain(artifact, null);
        assertArrayEquals(GOOD, Files.readAllBytes(prepared));
        assertEquals(prepared, new VerifiedCache(cacheRoot, uri -> {
            throw new AssertionError("Should reuse the verified digest-only artifact");
        }).obtain(artifact, null));
    }

    @Test
    void onlyMirrorsExactCentralAndAliyunUrls() throws IOException {
        assertRequests("https://repo1.maven.org/maven2/" + LIBRARY, MIRROR + "/" + LIBRARY);
        assertRequests(CENTRAL + LIBRARY, MIRROR + "/" + LIBRARY);
        assertRequests(ALIYUN + LIBRARY, MIRROR + "/" + LIBRARY);
        assertRequests(CENTRAL + LIBRARY + "?key=1", CENTRAL + LIBRARY + "?key=1");
        assertRequests(CENTRAL + "other/" + LIBRARY, CENTRAL + "other/" + LIBRARY);
        assertRequests("https://repo.maven.apache.org:444/maven2/" + LIBRARY,
                "https://repo.maven.apache.org:444/maven2/" + LIBRARY);
        assertRequests("https://repo.maven.apache.org.example/maven2/" + LIBRARY,
                "https://repo.maven.apache.org.example/maven2/" + LIBRARY);
        assertRequests("https://maven.aliyun.com/repository/public/" + LIBRARY,
                "https://maven.aliyun.com/repository/public/" + LIBRARY);
    }

    @Test
    void explicitHttpProxyReceivesHttpsConnect() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket server = loopbackServer()) {
            Future<String> request = worker.submit(() -> {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(5_000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(),
                            StandardCharsets.US_ASCII));
                    String line = reader.readLine();
                    for (String header; (header = reader.readLine()) != null && !header.isEmpty();) {
                        // Consume the CONNECT headers before replying.
                    }
                    client.getOutputStream().write("HTTP/1.1 502 Test\r\nContent-Length: 0\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    return line;
                }
            });
            DownloadOptions options = read("proxy", "http://127.0.0.1:" + server.getLocalPort());
            assertThrows(IOException.class, () -> options.download(URI.create(CENTRAL + LIBRARY)));
            assertTrue(request.get(5, TimeUnit.SECONDS).startsWith("CONNECT repo.maven.apache.org:443 "));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void explicitSocksProxyReceivesHttpsConnection() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket server = loopbackServer()) {
            Future<Integer> version = worker.submit(() -> {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(5_000);
                    InputStream input = client.getInputStream();
                    int protocol = input.read();
                    int methods = input.read();
                    for (int i = 0; i < methods; i++) input.read();
                    client.getOutputStream().write(new byte[] {5, (byte) 0xff});
                    return protocol;
                }
            });
            DownloadOptions options = read("proxy", "socks://127.0.0.1:" + server.getLocalPort());
            assertThrows(IOException.class, () -> options.download(URI.create(CENTRAL + LIBRARY)));
            assertEquals(5, version.get(5, TimeUnit.SECONDS).intValue());
        } finally {
            worker.shutdownNow();
        }
    }

    private static ServerSocket loopbackServer() throws IOException {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(5_000);
        return server;
    }

    private void assertRequests(String original, String first) throws IOException {
        List<URI> attempted = new ArrayList<>();
        VerifiedCache cache = new VerifiedCache(Files.createTempDirectory(root, "cache"), uri -> {
            attempted.add(uri);
            return new ByteArrayInputStream(GOOD);
        }, read("mavenMirror", MIRROR));
        cache.obtain(artifact(original), null);
        assertEquals(Collections.singletonList(URI.create(first)), attempted);
    }

    private void reject(String key, String... values) {
        for (String value : values) {
            IOException error = assertThrows(IOException.class, () -> read(key, value), value);
            assertTrue(error.getMessage().startsWith("proxy".equals(key) ? "Proxy" : "Maven Central mirror"));
            assertFalse(error.getMessage().contains("secret"));
        }
    }

    private DownloadOptions read(String key, String value) throws IOException {
        Properties properties = new Properties();
        properties.setProperty(key, value);
        return DownloadOptions.read(properties, root);
    }

    private static InstallerMetadata.Artifact artifact(String url) {
        return new InstallerMetadata.Artifact(LIBRARY, GOOD.length, "SHA-256",
                VerifiedCache.hex(VerifiedCache.digest("SHA-256").digest(GOOD)), URI.create(url), Collections.emptyList());
    }
}
