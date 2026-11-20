package top.outlands.relauncher.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

import static top.outlands.relauncher.bootstrap.InstallerMetadata.MAX_ARTIFACT_BYTES;
import static top.outlands.relauncher.bootstrap.InstallerMetadata.require;

final class VerifiedCache {
    private static final String CENTRAL = "https://repo.maven.apache.org/maven2/";
    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final long TRANSFER_NANOS = 120_000_000_000L;

    interface Downloader {
        InputStream open(URI uri) throws IOException;
    }

    interface Source {
        InputStream open() throws IOException;
    }

    private final Path root;
    private final Path libraries;
    private final Downloader downloader;
    private final DownloadOptions options;

    VerifiedCache(Path root, Downloader downloader) throws IOException {
        this(root, downloader, DownloadOptions.DEFAULT);
    }

    VerifiedCache(Path root, Downloader downloader, DownloadOptions options) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        libraries = options.librariesDirectory == null ? null : resolveExistingAncestors(options.librariesDirectory);
        checkPrivateRoot();
        this.downloader = downloader;
        this.options = options;
    }

    private void checkPrivateRoot() throws IOException {
        if (libraries == null) return;
        Path resolved = resolveExistingAncestors(root);
        require(!resolved.startsWith(libraries) && !libraries.startsWith(resolved),
                "librariesDirectory and the private cache must not overlap");
    }

    private static Path resolveExistingAncestors(Path path) throws IOException {
        Path existing = path;
        while (true) {
            try {
                return existing.toRealPath().resolve(existing.relativize(path));
            } catch (NoSuchFileException ex) {
                existing = existing.getParent();
                if (existing == null) throw ex;
            }
        }
    }

    private Path findShared(InstallerMetadata.Artifact artifact) {
        if (libraries == null) return null;
        Path candidate = libraries.resolve(artifact.path);
        try {
            return verified(candidate, artifact) ? candidate : null;
        } catch (IOException | SecurityException ignored) {
            // Unreadable or redirected shared entries are misses; never repair them in place.
            return null;
        }
    }

    Path obtain(InstallerMetadata.Artifact artifact, Source embedded) throws IOException {
        validatePath(artifact.path);
        Path shared = findShared(artifact);
        if (shared != null) return shared;
        // Disjoint content keeps two manifests from replacing each other's JARs while a game is running.
        String relative = artifact.algorithm.toLowerCase(Locale.ROOT) + "/" + artifact.digest + "/" + artifact.path;
        checkPrivateRoot();
        Path target = resolveForWrite(Files.createDirectories(root).toRealPath(), relative);
        if (verified(target, artifact)) return target;
        Path tmp = Files.createTempFile(target.getParent(), ".outlands-", ".part");
        try {
            if (embedded == null) {
                fetch(artifact, tmp);
            } else {
                copyVerified(artifact, embedded, tmp);
            }
            try {
                commit(tmp, target);
            } catch (IOException ex) {
                // Another prepare process may already have published and opened identical bytes.
                if (!verified(target, artifact)) throw ex;
            }
            return target;
        } catch (IOException ex) {
            throw new IOException("Could not prepare " + artifact.path + ": " + ex.getMessage(), ex);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void fetch(InstallerMetadata.Artifact artifact, Path tmp) throws IOException {
        List<IOException> failures = new ArrayList<>();
        for (URI uri : downloadUris(artifact, options)) {
            System.out.println("Fetching " + artifact.path + " from " + uri.getHost());
            try {
                copyVerified(artifact, () -> downloader.open(uri), tmp);
                return;
            } catch (IOException ex) {
                failures.add(new IOException(uri.getHost() + ": " + ex.getMessage(), ex));
            }
        }
        if (failures.size() == 1) throw failures.get(0);
        List<String> messages = new ArrayList<>();
        for (IOException failure : failures) messages.add(failure.getMessage());
        IOException failure = new IOException(String.join("; ", messages));
        for (IOException cause : failures) failure.addSuppressed(cause);
        throw failure;
    }

    private static void copyVerified(InstallerMetadata.Artifact artifact, Source source, Path tmp) throws IOException {
        try (InputStream input = source.open(); OutputStream output = Files.newOutputStream(tmp)) {
            MessageDigest digest = digest(artifact.algorithm);
            long count = copy(input, output, sizeLimit(artifact), digest, System.nanoTime());
            require(validSize(artifact, count) && artifact.digest.equals(hex(digest.digest())),
                    "Wrong size or digest for " + artifact.path);
        }
    }

    static List<URI> downloadUris(InstallerMetadata.Artifact artifact) throws IOException {
        return downloadUris(artifact, DownloadOptions.DEFAULT);
    }

    private static List<URI> downloadUris(InstallerMetadata.Artifact artifact, DownloadOptions options)
            throws IOException {
        URI original = checkHttps(artifact.uri);
        String path = artifact.path;
        validatePath(path);
        if ((original.getPort() == -1 || original.getPort() == 443) && original.getRawQuery() == null) {
            String host = original.getHost();
            String originalPath = original.getRawPath();
            boolean aliyun = "maven.aliyun.com".equalsIgnoreCase(host)
                    && ("/repository/central/" + path).equals(originalPath);
            boolean central = ("repo.maven.apache.org".equalsIgnoreCase(host) || "repo1.maven.org".equalsIgnoreCase(host))
                    && ("/maven2/" + path).equals(originalPath);
            if (aliyun || (central && options.mavenMirror != null)) {
                Set<URI> candidates = new LinkedHashSet<>();
                if (options.mavenMirror != null) candidates.add(options.mavenMirror.resolve(path));
                candidates.add(aliyun ? URI.create(CENTRAL + path) : original);
                candidates.add(original);
                return new ArrayList<>(candidates);
            }
            if ("maven.outlands.top".equalsIgnoreCase(host) && ("/releases/" + path).equals(originalPath)
                    && path.matches("top/outlands/outlands/[A-Za-z0-9._-]+/outlands-[A-Za-z0-9._-]+-mmc\\.zip")) {
                return releaseSources(path);
            }
            if ("nexus.jsdu.cn".equalsIgnoreCase(host) && path.startsWith("top/outlands/")
                    && ("/repository/" + path).equals(originalPath)) {
                return Arrays.asList(URI.create("https://maven.outlands.top/releases/" + path), original);
            }
        }
        return Collections.singletonList(original);
    }

    static List<URI> releaseSources(String path) {
        return Arrays.asList(URI.create("https://maven.outlands.top/releases/" + path),
                URI.create("https://nexus.jsdu.cn/repository/" + path));
    }

    private static boolean verified(Path file, InstallerMetadata.Artifact artifact) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && file.equals(file.toRealPath()),
                "Cache entry is not a regular file: " + artifact.path);
        if (!validSize(artifact, Files.size(file))) return false;
        MessageDigest digest = digest(artifact.algorithm);
        try (InputStream input = Files.newInputStream(file)) {
            return validSize(artifact, copy(input, null, sizeLimit(artifact), digest, 0))
                    && artifact.digest.equals(hex(digest.digest()));
        }
    }

    // Synthetic artifacts with an independently published digest (e.g. an MMC ZIP) can omit size.
    private static long sizeLimit(InstallerMetadata.Artifact artifact) {
        return artifact.size == -1 ? MAX_ARTIFACT_BYTES : artifact.size;
    }

    private static boolean validSize(InstallerMetadata.Artifact artifact, long actual) {
        return artifact.size == -1 ? actual > 0 && actual <= MAX_ARTIFACT_BYTES : actual == artifact.size;
    }

    static URI httpsUri(String value) throws IOException {
        return checkHttps(parseUri(value));
    }

    private static URI parseUri(String value) throws IOException {
        try {
            return new URI(value);
        } catch (URISyntaxException ex) {
            throw new IOException("Invalid artifact URL", ex);
        }
    }

    private static URI checkHttps(URI uri) throws IOException {
        require("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                && uri.getFragment() == null, "Artifact URLs and redirects must use HTTPS without credentials");
        return uri;
    }

    static InputStream download(URI uri) throws IOException {
        return download(uri, null);
    }

    static InputStream download(URI uri, Proxy proxy) throws IOException {
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            URL url = checkHttps(uri).toURL();
            HttpsURLConnection connection = (HttpsURLConnection) (proxy == null
                    ? url.openConnection() : url.openConnection(proxy));
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept-Encoding", "identity");
            boolean streaming = false;
            try {
                int status = connection.getResponseCode();
                if (status == 200) {
                    InputStream input = connection.getInputStream();
                    streaming = true;
                    return new java.io.FilterInputStream(input) {
                        @Override
                        public void close() throws IOException {
                            try {
                                super.close();
                            } finally {
                                connection.disconnect();
                            }
                        }
                    };
                }
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    require(location != null, "Artifact redirect had no Location");
                    uri = checkHttps(uri.resolve(parseUri(location)));
                } else {
                    throw new IOException("HTTP " + status + " from " + uri.getHost());
                }
            } finally {
                if (!streaming) connection.disconnect();
            }
        }
        throw new IOException("Too many HTTPS redirects");
    }

    static void validatePath(String relative) throws IOException {
        require(relative != null && relative.length() <= 1024, "Missing or oversized metadata path");
        for (String segment : relative.split("/", -1)) {
            String stem = segment.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            require(segment.matches("[A-Za-z0-9_.+\\-]{1,255}") && !segment.endsWith(".")
                    && !stem.matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]"), "Unsafe relative metadata path");
        }
    }

    static Path resolveForWrite(Path root, String relative) throws IOException {
        validatePath(relative);
        String[] pieces = relative.split("/");
        Path parent = root;
        for (int i = 0; i < pieces.length - 1; i++) {
            parent = parent.resolve(pieces[i]);
            try {
                Files.createDirectory(parent);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Check existing path below, including links / Windows junctions.
            }
            require(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) && parent.equals(parent.toRealPath()),
                    "Unsafe cache or extraction directory");
        }
        return parent.resolve(pieces[pieces.length - 1]);
    }

    static void commit(Path temporary, Path target) throws IOException {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static byte[] readBytes(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        copy(input, bytes, limit, null, 0);
        return bytes.toByteArray();
    }

    static long copy(InputStream input, OutputStream output, long limit, MessageDigest digest, long start)
            throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long size = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            require(start == 0 || System.nanoTime() - start <= TRANSFER_NANOS, "Artifact transfer timed out");
            size += count;
            require(size <= limit, "Input exceeds the permitted size");
            if (digest != null) digest.update(buffer, 0, count);
            if (output != null) output.write(buffer, 0, count);
        }
        return size;
    }

    static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Missing digest algorithm: " + algorithm, ex);
        }
    }

    static String hex(byte[] bytes) {
        final char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[2 * i] = digits[(bytes[i] & 0xf0) >>> 4];
            result[2 * i + 1] = digits[bytes[i] & 0xf];
        }
        return new String(result);
    }
}
