package top.outlands.relauncher.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import top.outlands.relauncher.shared.PackSettings;

import static top.outlands.relauncher.bootstrap.InstallerMetadata.require;

/** Optional network and read-only library sources for the preparation process. */
final class DownloadOptions {
    static final DownloadOptions DEFAULT = new DownloadOptions(null, null, null);

    private final Proxy proxy;
    final URI mavenMirror;
    final Path librariesDirectory;

    private DownloadOptions(Proxy proxy, URI mavenMirror, Path librariesDirectory) {
        this.proxy = proxy;
        this.mavenMirror = mavenMirror;
        this.librariesDirectory = librariesDirectory;
    }

    static DownloadOptions read(Properties opts, Path gameDir) throws IOException {
        URI proxy = PackSettings.proxy(opts.getProperty(PackSettings.PROXY, ""));
        URI mirror = PackSettings.mirror(opts.getProperty(PackSettings.MAVEN_MIRROR, ""));
        require(mirror == null || !mirror.getRawAuthority().endsWith(":"),
                "Maven Central mirror must not have an empty port");
        String libraries = opts.getProperty(PackSettings.LIBRARIES_DIRECTORY, "").trim();
        return new DownloadOptions(proxy == null ? null : createProxy(proxy), mirror,
                libraries.isEmpty() ? null : readLibraries(libraries, gameDir));
    }

    InputStream download(URI uri) throws IOException {
        return VerifiedCache.download(uri, proxy);
    }

    private static Proxy createProxy(URI uri) {
        String host = uri.getHost();
        if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
        Proxy.Type type = "http".equalsIgnoreCase(uri.getScheme()) ? Proxy.Type.HTTP : Proxy.Type.SOCKS;
        return new Proxy(type, InetSocketAddress.createUnresolved(host, uri.getPort()));
    }

    private static Path readLibraries(String value, Path gameDir) throws IOException {
        try {
            Path path = gameDir.resolve(Paths.get(value)).toAbsolutePath().normalize();
            require(!Files.exists(path) || Files.isDirectory(path), "librariesDirectory must be a directory");
            return path;
        } catch (InvalidPathException ex) {
            throw new IOException("librariesDirectory is not a valid path");
        }
    }
}
