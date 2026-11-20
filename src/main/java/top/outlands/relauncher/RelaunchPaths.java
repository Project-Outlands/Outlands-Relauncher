package top.outlands.relauncher;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

final class RelaunchPaths {
    private RelaunchPaths() {}

    // parent must already be a real directory. The real-path check also catches Windows junctions.
    static Path directory(Path parent, String name) throws IOException {
        Path child = parent.resolve(name);
        try {
            Files.createDirectory(child);
        } catch (FileAlreadyExistsException existing) {
            // Validate the existing entry below before using it.
        }
        if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) || !child.equals(child.toRealPath())) {
            throw new IOException("Managed path is not a plain directory");
        }
        return child;
    }

    static void requireFile(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.equals(file.toRealPath())) {
            throw new IOException("Missing or unsafe preparation output");
        }
    }

    static void cleanRun(Path runDir) throws IOException {
        Path parent = runDir.getParent();
        if (!parent.equals(parent.toRealPath())) {
            throw new IOException("Run directory parent changed");
        }
        deleteEntry(runDir);
    }

    private static void deleteEntry(Path entry) throws IOException {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException gone) {
            return;
        }
        if (attrs.isDirectory() && !attrs.isSymbolicLink() && entry.equals(entry.toRealPath())) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(entry)) {
                for (Path child : children) {
                    deleteEntry(child);
                }
            }
        }
        Files.deleteIfExists(entry);
    }
}
