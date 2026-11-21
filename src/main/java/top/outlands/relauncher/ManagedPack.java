package top.outlands.relauncher;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;

/** A staged local pack, rolled back on close unless the caller retains it. */
final class ManagedPack implements AutoCloseable {
    private final Path path;
    private boolean discardOnClose;

    private ManagedPack(Path path, boolean discardOnClose) {
        this.path = path;
        this.discardOnClose = discardOnClose;
    }

    // Pack metadata validation belongs to the caller; staging only copies the archive.
    static ManagedPack stage(Path realGameDir, Path archive) throws IOException {
        requireRealDirectory(realGameDir);
        Path source = archive.toAbsolutePath();
        String suffix = archiveSuffix(source);
        RelaunchPaths.requireFile(source);

        Path directory = RelaunchPaths.directory(realGameDir, "outlands");
        if (source.startsWith(directory)) {
            return new ManagedPack(source, false);
        }

        Path destination = directory.resolve("pack-" + UUID.randomUUID() + suffix);
        try (ManagedPack pending = new ManagedPack(Files.createTempFile(directory, "pack-", ".tmp"), true)) {
            try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                 OutputStream output = Files.newOutputStream(pending.path, StandardOpenOption.WRITE,
                         LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Local pack copy interrupted");
                    output.write(buffer, 0, read);
                }
            }
            requireRealDirectory(directory);
            RelaunchPaths.requireFile(pending.path);
            Files.move(pending.path, destination);
            pending.retain();
            return new ManagedPack(destination, true);
        }
    }

    Path path() {
        return path;
    }

    void retain() {
        discardOnClose = false;
    }

    @Override
    public void close() throws IOException {
        if (!discardOnClose) return;
        requireRealDirectory(path.getParent());
        if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            RelaunchPaths.requireFile(path);
            Files.deleteIfExists(path);
        }
        discardOnClose = false;
    }

    private static void requireRealDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.equals(directory.toRealPath())) {
            throw new IOException("Managed pack directory is not a real directory: " + directory);
        }
    }

    private static String archiveSuffix(Path archive) throws IOException {
        Path fileName = archive.getFileName();
        if (fileName != null) {
            String name = fileName.toString();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".zip") || lower.endsWith(".jar")) {
                return name.substring(name.length() - 4);
            }
        }
        throw new IOException("Expected a ZIP or JAR archive: " + archive);
    }
}
