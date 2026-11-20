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
import java.util.Properties;

final class RelaunchConfig {
    enum Mode { ASK, AUTO, FORGE }

    private final Path gameDir;
    private final Path file;
    private final Properties properties = new Properties();
    final Mode mode;

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
        }
        mode = Mode.valueOf(properties.getProperty("mode", "ask").trim().toUpperCase(Locale.ROOT));
    }

    Path javaExecutable() {
        return optionalPath("javaExecutable");
    }

    Path localInstaller() {
        return optionalPath("localInstaller");
    }

    private Path optionalPath(String key) {
        String value = properties.getProperty(key, "").trim();
        return value.isEmpty() ? null : gameDir.resolve(Paths.get(value)).normalize().toAbsolutePath();
    }

    void saveAuto(Path pickedJava) throws IOException {
        if (mode == Mode.AUTO && pickedJava == null) {
            return;
        }
        Path dir = RelaunchPaths.directory(gameDir, "config");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Configuration is not a regular file");
        }
        properties.setProperty("mode", "auto");
        if (pickedJava != null) {
            properties.setProperty("javaExecutable", pickedJava.toString());
        }
        Path temporary = Files.createTempFile(dir, "outlands-relauncher-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "Outlands Relauncher: mode=ask|auto|forge");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
