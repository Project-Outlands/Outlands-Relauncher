package top.outlands.relauncher.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

import static top.outlands.relauncher.bootstrap.InstallerMetadata.require;

final class VerifiedCache {
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
    private final Downloader downloader;

    VerifiedCache(Path root, Downloader downloader) throws IOException {
        this.root = Files.createDirectories(root).toRealPath();
        this.downloader = downloader;
    }

    Path obtain(InstallerMetadata.Artifact artifact, Source embedded) throws IOException {
        // Disjoint content keeps two manifests from replacing each other's JARs while a game is running.
        String relative = artifact.algorithm.toLowerCase(Locale.ROOT) + "/" + artifact.digest + "/" + artifact.path;
        Path target = resolveForWrite(root, relative);
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
        IOException firstFailure = null;
        for (URI uri : downloadUris(artifact)) {
            System.out.println("Fetching " + artifact.path + " from " + uri.getHost());
            try {
                copyVerified(artifact, () -> downloader.open(uri), tmp);
                return;
            } catch (IOException ex) {
                IOException failure = new IOException(uri.getHost() + ": " + ex.getMessage(), ex);
                if (firstFailure != null) {
                    failure.addSuppressed(firstFailure);
                    throw new IOException(firstFailure.getMessage() + "; fallback " + failure.getMessage(), failure);
                }
                firstFailure = failure;
            }
        }
        throw firstFailure;
    }

    private static void copyVerified(InstallerMetadata.Artifact artifact, Source source, Path tmp) throws IOException {
        try (InputStream input = source.open(); OutputStream output = Files.newOutputStream(tmp)) {
            MessageDigest digest = digest(artifact.algorithm);
            long count = copy(input, output, artifact.size, digest, System.nanoTime());
            require(count == artifact.size && artifact.digest.equals(hex(digest.digest())),
                    "Wrong size or digest for " + artifact.path);
        }
    }

    static List<URI> downloadUris(InstallerMetadata.Artifact artifact) throws IOException {
        URI original = checkHttps(artifact.uri);
        String path = artifact.path;
        validatePath(path);
        if ((original.getPort() == -1 || original.getPort() == 443) && original.getRawQuery() == null) {
            String originalPath = original.getRawPath();
            if ("maven.aliyun.com".equalsIgnoreCase(original.getHost())
                    && ("/repository/central/" + path).equals(originalPath)) {
                return Arrays.asList(URI.create("https://repo.maven.apache.org/maven2/" + path), original);
            }
            if ("nexus.jsdu.cn".equalsIgnoreCase(original.getHost()) && path.startsWith("top/outlands/")
                    && ("/repository/" + path).equals(originalPath)) {
                return Arrays.asList(URI.create("https://maven.outlands.top/releases/" + path), original);
            }
        }
        return Collections.singletonList(original);
    }

    private static boolean verified(Path file, InstallerMetadata.Artifact artifact) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && file.equals(file.toRealPath()),
                "Cache entry is not a regular file: " + artifact.path);
        if (Files.size(file) != artifact.size) return false;
        MessageDigest digest = digest(artifact.algorithm);
        try (InputStream input = Files.newInputStream(file)) {
            return copy(input, null, artifact.size, digest, 0) == artifact.size
                    && artifact.digest.equals(hex(digest.digest()));
        }
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
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            HttpsURLConnection connection = (HttpsURLConnection) checkHttps(uri).toURL().openConnection();
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
