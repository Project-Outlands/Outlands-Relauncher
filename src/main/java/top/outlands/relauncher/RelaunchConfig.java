package top.outlands.relauncher;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

final class RelaunchConfig {
    enum Mode { ASK, AUTO, FORGE }

    private static final String LOCK_ADVANCED = "author.lockAdvanced";
    private static final String LOCK_JVM_ARGUMENTS = "author.lockJvmArguments";
    private static final String MINIMUM_JAVA = "author.minimumJava";

    private final Path gameDir;
    private final Path file;
    private Properties properties = new Properties();
    private PackAuthorSettings authorSettings;
    final Mode mode; // Startup mode; subsequent saves update the persisted launch settings.

    static RelaunchConfig read(Path gameDir) throws IOException {
        return new RelaunchConfig(gameDir);
    }

    private RelaunchConfig(Path gameDir) throws IOException {
        this.gameDir = gameDir;
        this.file = gameDir.resolve("config/outlands-relauncher.properties");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (NoSuchFileException missing) {
            // An unconfigured instance defaults to ask, without creating a file.
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Malformed configuration properties", malformed);
        }
        try {
            mode = Mode.valueOf(properties.getProperty("mode", "ask").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid configuration mode", malformed);
        }
        authorSettings = readAuthorSettings(properties);
    }

    private static PackAuthorSettings readAuthorSettings(Properties properties) throws IOException {
        if (!properties.containsKey(LOCK_ADVANCED) && !properties.containsKey(LOCK_JVM_ARGUMENTS)
                && !properties.containsKey(MINIMUM_JAVA)) {
            return PackAuthorSettings.DEFAULT;
        }
        boolean lockAdvanced = readBoolean(properties, LOCK_ADVANCED);
        boolean lockJvmArguments = readBoolean(properties, LOCK_JVM_ARGUMENTS);
        String minimumJava = properties.getProperty(MINIMUM_JAVA);
        try {
            return new PackAuthorSettings(lockAdvanced, lockJvmArguments,
                    minimumJava == null ? PackAuthorSettings.MINIMUM_JAVA : Integer.parseInt(minimumJava.trim()));
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid configuration value for " + MINIMUM_JAVA, malformed);
        }
    }

    private static boolean readBoolean(Properties properties, String key) throws IOException {
        String raw = properties.getProperty(key);
        if (raw == null) return false;
        String value = raw.trim();
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IOException("Invalid configuration value for " + key);
    }

    PackAuthorSettings authorSettings() {
        return authorSettings;
    }

    void saveAuthorSettings(PackAuthorSettings settings) throws IOException {
        Objects.requireNonNull(settings, "settings");
        Properties updated = copyProperties();
        updated.setProperty(LOCK_ADVANCED, Boolean.toString(settings.lockAdvanced));
        updated.setProperty(LOCK_JVM_ARGUMENTS, Boolean.toString(settings.lockJvmArguments));
        updated.setProperty(MINIMUM_JAVA, Integer.toString(settings.minimumJava));
        write(updated, true);
        authorSettings = settings;
    }

    LaunchSettings settings() {
        return new LaunchSettings(value("javaExecutable"), value("localInstaller"), value("xms"), value("xmx"),
                value("jvmArgs"), "auto".equalsIgnoreCase(properties.getProperty("mode", "auto").trim()),
                value("localMmcPack"), value("releaseVersion"), value("proxy"), value("mavenMirror"),
                value("librariesDirectory"));
    }

    Path javaExecutable() {
        return resolvePath(gameDir, value("javaExecutable"));
    }

    Path localInstaller() {
        return resolvePath(gameDir, value("localInstaller"));
    }

    private String value(String key) {
        return properties.getProperty(key, "");
    }

    static Path resolvePath(Path gameDir, String input) {
        String value = input.trim();
        return value.isEmpty() ? null : gameDir.resolve(Paths.get(value)).normalize().toAbsolutePath();
    }

    void save(LaunchSettings settings) throws IOException {
        Properties updated = copyProperties();
        updated.setProperty("mode", settings.automatic ? "auto" : "ask");
        setOptional(updated, "javaExecutable", settings.javaExecutable);
        setOptional(updated, "localInstaller", settings.localInstaller);
        setOptional(updated, "xms", settings.xms);
        setOptional(updated, "xmx", settings.xmx);
        setOptional(updated, "jvmArgs", settings.jvmArgs);
        setOptional(updated, "localMmcPack", settings.localMmcPack);
        setOptional(updated, "releaseVersion", settings.releaseVersion);
        setOptional(updated, "proxy", settings.proxy);
        setOptional(updated, "mavenMirror", settings.mavenMirror);
        setOptional(updated, "librariesDirectory", settings.librariesDirectory);
        write(updated, false);
    }

    private Properties copyProperties() {
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }

    private void write(Properties updated, boolean requireAtomic) throws IOException {
        Path dir = RelaunchPaths.directory(gameDir, "config");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Configuration is not a regular file");
        }
        Path temporary = Files.createTempFile(dir, "outlands-relauncher-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                updated.store(writer, "Outlands Relauncher: mode=ask|auto|forge");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                if (requireAtomic) throw unsupported;
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        properties = updated;
    }

    private static void setOptional(Properties properties, String key, String value) {
        if (value.trim().isEmpty()) properties.remove(key);
        else properties.setProperty(key, value);
    }
}
