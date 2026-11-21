package top.outlands.relauncher;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Properties;

import top.outlands.relauncher.shared.PackSettings;

/** An installation receipt published only after bootstrap has verified the package and dependencies. */
final class InstalledPack {
    private static final String FILE = "installation.properties";
    private static final String VERSION_PREFIX = "1.12.2-Outlands-";
    private static final long MAX_RECORD_BYTES = 8192;

    final String version;
    final InstallRequest source;

    InstalledPack(String version, InstallRequest source) {
        this.version = version;
        this.source = source;
    }

    static InstalledPack read(Path gameDir) throws IOException {
        Path folder = gameDir.resolve("outlands");
        Path record = folder.resolve(FILE);
        try {
            Files.readAttributes(folder, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return null;
        }
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) || !folder.equals(folder.toRealPath())) {
            throw new IOException("Unsafe Outlands installation directory");
        }
        try {
            Files.readAttributes(record, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return null;
        }
        Properties data = readProperties(record);
        try {
            if (!"1".equals(data.getProperty("format"))) throw new IOException("Unknown Outlands installation format");
            InstallRequest source = new InstallRequest(InstallRequest.Kind.valueOf(data.getProperty("method", "")),
                    data.getProperty("source", ""));
            InstalledPack result = new InstalledPack(data.getProperty("version", ""), source);
            result.validate(gameDir);
            return result;
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid Outlands installation record", malformed);
        }
    }

    static String preparedVersion(Path launchProperties) throws IOException {
        String id = readProperties(launchProperties).getProperty("version", "");
        if (!id.startsWith(VERSION_PREFIX)) throw new IOException("Missing prepared Outlands version");
        return id.substring(VERSION_PREFIX.length());
    }

    void save(Path gameDir) throws IOException {
        Path folder = RelaunchPaths.directory(gameDir, "outlands");
        validate(gameDir);
        Path record = folder.resolve(FILE);
        if (Files.exists(record, LinkOption.NOFOLLOW_LINKS)) RelaunchPaths.requireFile(record);
        Properties data = new Properties();
        data.setProperty("format", "1");
        data.setProperty("version", version);
        data.setProperty("method", source.kind.name());
        data.setProperty("source", source.selection);
        Path temporary = Files.createTempFile(folder, "installation-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                data.store(writer, "Outlands installation");
            }
            if (Files.size(temporary) > MAX_RECORD_BYTES) throw new IOException("Outlands installation record too large");
            Files.move(temporary, record, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void validate(Path gameDir) throws IOException {
        if (!version.matches("[A-Za-z0-9._-]{1,200}")) throw new IOException("Invalid installed version");
        if (source.kind == InstallRequest.Kind.ONLINE) {
            if (!version.equals(source.selection) || !version.equals(PackSettings.releaseVersion(version))) {
                throw new IOException("Installed release and source do not match");
            }
            return;
        }
        Path folder = gameDir.resolve("outlands");
        Path path = RelaunchConfig.resolvePath(gameDir, source.selection);
        String suffix = source.kind == InstallRequest.Kind.MMC ? ".zip" : ".jar";
        if (path == null || !path.startsWith(folder) || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix)) {
            throw new IOException("Installed package is outside the managed directory or has the wrong format");
        }
        RelaunchPaths.requireFile(path);
    }

    private static Properties readProperties(Path file) throws IOException {
        RelaunchPaths.requireFile(file);
        if (Files.size(file) > MAX_RECORD_BYTES) throw new IOException("Outlands metadata file too large");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Properties data = new Properties();
            data.load(reader);
            return data;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Malformed Outlands metadata", invalid);
        }
    }
}
