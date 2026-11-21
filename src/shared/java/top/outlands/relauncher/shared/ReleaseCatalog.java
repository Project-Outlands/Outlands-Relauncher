package top.outlands.relauncher.shared;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Official Outlands MMC release presets shared by the UI and the preparation process. */
public final class ReleaseCatalog {
    private static final URI OFFICIAL = URI.create("https://maven.outlands.top/releases/top/outlands/outlands/");
    private static final String CACHE_FILE = "release-catalog.txt";
    private static final String CACHE_HEADER = "outlands-release-catalog-v1\n";
    private static final int MAX_METADATA_BYTES = 256 * 1024;
    private static final int MAX_CHECKSUM_BYTES = 128;
    private static final int MAX_CACHE_BYTES = 40 * 1024;
    private static final int MAX_CACHED_VERSIONS = 2048;
    private static final int MAX_CANDIDATES = 32;
    private static final int PROBE_THREADS = 6;
    private static final int SOCKET_TIMEOUT_MS = 4_000;
    private static final int NETWORK_TIMEOUT_SECONDS = 25;
    private static final Pattern SHA256 = Pattern.compile("[ \t\r\n]*[0-9a-fA-F]{64}[ \t\r\n]*");
    private static final Comparator<String> NEWEST_FIRST = Comparator
            .comparing((String version) -> version.substring(0, 10))
            .thenComparingInt(version -> version.length() == 10 ? 0 : Integer.parseInt(version.substring(11)))
            .reversed();

    private ReleaseCatalog() {}

    /**
     * Probes the newest 32 dated metadata entries for usable official MMC checksums, then atomically
     * adds the verified versions to the existing cache in an already validated, real directory.
     * Retains earlier verified versions even if they are now absent or outside the scan window.
     * Missing or malformed sidecars are skipped; transport failures abort the refresh. If the
     * history limit is reached, additions that would exceed it fail rather than evicting presets.
     * A successful scan repairs corrupt content in a safe regular cache file, but never replaces
     * an unsafe entry. Returns the same immutable, pinned-inclusive ordering as {@link #cached(Path)}.
     */
    public static List<String> refresh(Path cacheDir, String proxy) throws IOException, InterruptedException {
        return refresh(cacheDir, proxy, ReleaseCatalog::download);
    }

    /**
     * Reads the local catalog without creating anything or accessing the network. The result is
     * immutable, newest first, and always includes the pinned default, even without a cache.
     * An existing unsafe, malformed or oversized catalog causes IOException, not a silent fallback.
     */
    public static List<String> cached(Path cacheDir) throws IOException {
        return presets(readCache(cacheDir.toAbsolutePath().normalize()));
    }

    // Null denotes HTTP 404/410 only; other request failures throw. Implementations may be called concurrently.
    interface Downloader {
        byte[] get(URI uri, Proxy proxy, int limit) throws IOException, InterruptedException;
    }

    static List<String> refresh(Path cacheDir, String proxy, Downloader downloader)
            throws IOException, InterruptedException {
        Path dir = cacheDir.toAbsolutePath().normalize();
        requireRealDirectory(dir);
        Proxy selectedProxy = parseProxy(proxy);
        checkInterrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(NETWORK_TIMEOUT_SECONDS);
        ExecutorService workers = Executors.newFixedThreadPool(PROBE_THREADS, task -> {
            Thread thread = new Thread(task, "outlands-release-catalog");
            thread.setDaemon(true);
            return thread;
        });
        List<String> verified = new ArrayList<>();
        try {
            Future<Set<String>> metadata = workers.submit(() -> parseMetadata(
                    downloader.get(OFFICIAL.resolve("maven-metadata.xml"), selectedProxy, MAX_METADATA_BYTES)));
            List<Callable<String>> probes = new ArrayList<>();
            for (String version : await(metadata, deadline)) {
                if (probes.size() == MAX_CANDIDATES) break;
                probes.add(() -> hasChecksum(version, selectedProxy, downloader) ? version : null);
            }
            for (Future<String> probe : workers.invokeAll(probes, remaining(deadline), TimeUnit.NANOSECONDS)) {
                if (probe.isCancelled()) throw new SocketTimeoutException("Outlands release catalog timed out");
                String version = await(probe, deadline);
                if (version != null) verified.add(version);
            }
        } finally {
            workers.shutdownNow();
        }
        return mergeAndWrite(dir, verified);
    }

    private static long remaining(long deadline) throws SocketTimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new SocketTimeoutException("Outlands release catalog timed out");
        return nanos;
    }

    private static <T> T await(Future<T> task, long deadline) throws IOException, InterruptedException {
        try {
            return task.get(remaining(deadline), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            throw new SocketTimeoutException("Outlands release catalog timed out");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException) throw (IOException) cause;
            if (cause instanceof InterruptedException) throw (InterruptedException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IOException("Could not refresh Outlands release catalog", cause);
        }
    }

    private static Proxy parseProxy(String value) throws IOException {
        URI uri = PackSettings.proxy(value);
        if (uri == null) return null;
        String host = uri.getHost();
        if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
        Proxy.Type type = "http".equalsIgnoreCase(uri.getScheme()) ? Proxy.Type.HTTP : Proxy.Type.SOCKS;
        return new Proxy(type, InetSocketAddress.createUnresolved(host, uri.getPort()));
    }

    private static byte[] download(URI uri, Proxy proxy, int limit) throws IOException, InterruptedException {
        HttpsURLConnection connection = (HttpsURLConnection) (proxy == null
                ? uri.toURL().openConnection() : uri.toURL().openConnection(proxy));
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(SOCKET_TIMEOUT_MS);
        connection.setReadTimeout(SOCKET_TIMEOUT_MS);
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            checkInterrupted();
            int status = connection.getResponseCode();
            if (status == 404 || status == 410) return null;
            if (status != 200) throw new IOException("Official Outlands Maven returned HTTP " + status + " for " + uri);
            try (InputStream input = connection.getInputStream()) {
                return readBounded(input, limit);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static boolean hasChecksum(String version, Proxy proxy, Downloader downloader)
            throws IOException, InterruptedException {
        URI uri = OFFICIAL.resolve(version + "/outlands-" + version + "-mmc.zip.sha256");
        try {
            byte[] bytes = downloader.get(uri, proxy, MAX_CHECKSUM_BYTES);
            return bytes != null && bytes.length <= MAX_CHECKSUM_BYTES
                    && SHA256.matcher(new String(bytes, StandardCharsets.US_ASCII)).matches();
        } catch (BodyTooLarge invalid) {
            return false;
        }
    }

    private static Set<String> parseMetadata(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_METADATA_BYTES) throw new IOException("Missing or oversized Outlands Maven metadata");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            DocumentBuilder parser = factory.newDocumentBuilder();
            parser.setErrorHandler(new DefaultHandler() {
                @Override
                public void fatalError(SAXParseException error) throws SAXException {
                    throw error;
                }
            });
            Node root = parser.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            if (!"metadata".equals(root.getNodeName())) throw new IOException("Invalid Outlands Maven metadata root");
            Node versions = requiredChild(requiredChild(root, "versioning"), "versions");
            Set<String> result = new TreeSet<>(NEWEST_FIRST);
            for (Node node = versions.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node.getNodeType() != Node.ELEMENT_NODE || !"version".equals(node.getNodeName())) continue;
                Node text = node.getFirstChild();
                if (text == null || text.getNextSibling() != null
                        || (text.getNodeType() != Node.TEXT_NODE && text.getNodeType() != Node.CDATA_SECTION_NODE)) continue;
                String version = text.getNodeValue().trim();
                if (isVersion(version)) result.add(version);
            }
            return result;
        } catch (ParserConfigurationException | SAXException | IllegalArgumentException | UnsupportedOperationException ex) {
            throw new IOException("Could not parse Outlands Maven metadata safely", ex);
        }
    }

    private static Node requiredChild(Node parent, String name) throws IOException {
        Node match = null;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() != Node.ELEMENT_NODE || !name.equals(node.getNodeName())) continue;
            if (match != null) throw new IOException("Duplicate Outlands Maven metadata element: " + name);
            match = node;
        }
        if (match == null) throw new IOException("Missing Outlands Maven metadata element: " + name);
        return match;
    }

    private static boolean isVersion(String version) {
        if (version.length() < 10 || version.length() > 15) return false;
        try {
            if (!version.equals(PackSettings.releaseVersion(version))) return false;
            LocalDate.of(Integer.parseInt(version.substring(0, 4)), Integer.parseInt(version.substring(5, 7)),
                    Integer.parseInt(version.substring(8, 10)));
            return true;
        } catch (IOException | DateTimeException invalid) {
            return false;
        }
    }

    private static void requireRealDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || !dir.equals(dir.toRealPath())) {
            throw new IOException("Release catalog directory is not a plain directory: " + dir);
        }
    }

    private static Set<String> readCache(Path dir) throws IOException {
        Set<String> versions = new TreeSet<>(NEWEST_FIRST);
        try {
            Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return versions;
        }
        requireRealDirectory(dir);
        Path file = dir.resolve(CACHE_FILE);
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return versions;
        }
        if (!attrs.isRegularFile() || !file.equals(file.toRealPath())) {
            throw new IOException("Unsafe Outlands release catalog: " + file);
        }
        if (attrs.size() > MAX_CACHE_BYTES) throw new CorruptCache(file);
        String contents;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            contents = new String(readBounded(input, MAX_CACHE_BYTES), StandardCharsets.US_ASCII);
        } catch (BodyTooLarge oversized) {
            throw new CorruptCache(file);
        }
        if (!contents.startsWith(CACHE_HEADER) || !contents.endsWith("\n")) throw new CorruptCache(file);
        String previous = null;
        for (int start = CACHE_HEADER.length(); start < contents.length();) {
            int end = contents.indexOf('\n', start);
            String version = contents.substring(start, end);
            if (!isVersion(version) || PackSettings.DEFAULT_VERSION.equals(version)
                    || (previous != null && NEWEST_FIRST.compare(previous, version) >= 0)
                    || versions.size() == MAX_CACHED_VERSIONS) throw new CorruptCache(file);
            versions.add(version);
            previous = version;
            start = end + 1;
        }
        return versions;
    }

    private static final class CorruptCache extends IOException {
        CorruptCache(Path file) {
            super("Malformed or oversized Outlands release catalog: " + file);
        }
    }

    // Serialize read/merge/replace among refreshes in this JVM, not their network requests.
    private static synchronized List<String> mergeAndWrite(Path dir, List<String> verified)
            throws IOException, InterruptedException {
        checkInterrupted();
        requireRealDirectory(dir);
        Set<String> versions;
        try {
            versions = readCache(dir);
        } catch (CorruptCache corrupt) {
            // Only discard invalid content read from an otherwise safe regular file.
            versions = new TreeSet<>(NEWEST_FIRST);
        }
        versions.addAll(verified);
        versions.remove(PackSettings.DEFAULT_VERSION);
        if (versions.size() > MAX_CACHED_VERSIONS) throw new IOException("Outlands release catalog history is full");
        String contents = CACHE_HEADER;
        if (!versions.isEmpty()) contents += String.join("\n", versions) + "\n";
        Path temporary = Files.createTempFile(dir, "release-catalog-", ".tmp");
        try {
            Files.write(temporary, contents.getBytes(StandardCharsets.US_ASCII),
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            checkInterrupted();
            Files.move(temporary, dir.resolve(CACHE_FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return presets(versions);
    }

    private static List<String> presets(Set<String> versions) {
        versions.add(PackSettings.DEFAULT_VERSION);
        return Collections.unmodifiableList(new ArrayList<>(versions));
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[Math.min(4096, limit + 1)];
        while (!Thread.currentThread().isInterrupted()) {
            int count = input.read(buffer, 0, Math.min(buffer.length, limit - output.size() + 1));
            if (count == -1) return output.toByteArray();
            if (count > limit - output.size()) throw new BodyTooLarge(limit);
            output.write(buffer, 0, count);
        }
        throw new InterruptedIOException("Interrupted while reading Outlands release catalog");
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
    }

    private static final class BodyTooLarge extends IOException {
        BodyTooLarge(int limit) {
            super("Outlands release catalog response exceeds " + limit + " bytes");
        }
    }
}
