package top.outlands.relauncher.shared;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/** Options passed from the setup window to the isolated MMC preparation process. */
public final class PackSettings {
    public static final int INVALID_LOCAL_PACK_EXIT = 2;
    public static final String FILE_NAME = "pack-options.properties";
    public static final String DEFAULT_VERSION = "2026.11.16";
    public static final String RELEASE_VERSION = "releaseVersion";
    public static final String PROXY = "proxy";
    public static final String MAVEN_MIRROR = "mavenMirror";
    public static final String LIBRARIES_DIRECTORY = "librariesDirectory";

    private PackSettings() {}

    public static String releaseVersion(String value) throws IOException {
        String version = value.trim();
        if (version.isEmpty()) return DEFAULT_VERSION;
        if (!version.matches("20[0-9]{2}\\.(?:0[1-9]|1[0-2])\\.(?:0[1-9]|[12][0-9]|3[01])(?:-[1-9][0-9]{0,3})?")) {
            throw new ValidationException("error.validation.pack.releaseVersion",
                    "Use an Outlands release version like " + DEFAULT_VERSION + " or leave the field empty.",
                    DEFAULT_VERSION);
        }
        return version;
    }

    public static URI proxy(String value) throws IOException {
        if (value.trim().isEmpty()) return null;
        String key = "error.validation.pack.proxy";
        String error = "Proxy must use http://host:port or socks://host:port without credentials.";
        URI uri = endpoint(value, key, error);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "socks".equalsIgnoreCase(uri.getScheme()))
                || uri.getPort() < 1 || uri.getPort() > 65535 || !uri.getRawPath().isEmpty()) {
            throw new ValidationException(key, error);
        }
        return uri;
    }

    public static URI mirror(String value) throws IOException {
        if (value.trim().isEmpty()) return null;
        String key = "error.validation.pack.mavenMirror";
        String error = "Maven Central mirror must be an HTTPS repository URL without credentials or query parameters.";
        URI uri = endpoint(value, key, error);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getPort() == 0 || uri.getPort() > 65535
                || !uri.getRawPath().matches("(?:/[A-Za-z0-9_-][A-Za-z0-9_.~-]*)*/?")) {
            throw new ValidationException(key, error);
        }
        return URI.create(uri.toASCIIString().replaceFirst("/*$", "/"));
    }

    private static URI endpoint(String value, String key, String error) throws ValidationException {
        try {
            URI uri = new URI(value.trim());
            if (uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null) return uri;
        } catch (URISyntaxException invalid) {
            throw new ValidationException(key, error, invalid);
        }
        throw new ValidationException(key, error);
    }
}
