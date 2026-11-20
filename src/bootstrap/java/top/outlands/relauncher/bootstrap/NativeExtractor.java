package top.outlands.relauncher.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static top.outlands.relauncher.bootstrap.InstallerMetadata.require;

final class NativeExtractor {
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ENTRIES = 4096;

    private final Path root;
    private final Set<String> extracted = new HashSet<>();
    private long remaining = MAX_BYTES;
    private int entries;

    NativeExtractor(Path root) throws IOException {
        this.root = Files.createDirectory(root).toRealPath();
    }

    void extract(Path archive, List<String> excluded) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> contents = zip.entries();
            while (contents.hasMoreElements()) {
                ZipEntry entry = contents.nextElement();
                require(++entries <= MAX_ENTRIES, "Too many native archive entries");
                String name = entry.getName();
                // Validate even ignored entries; never interpret ZIP names as absolute/Windows paths.
                VerifiedCache.validatePath(entry.isDirectory() ? name.substring(0, name.length() - 1) : name);
                if (entry.isDirectory() || name.regionMatches(true, 0, "META-INF/", 0, 9)
                        || excluded.stream().anyMatch(name::startsWith)) continue;
                require(extracted.add(name.toLowerCase(Locale.ROOT)), "Duplicate native archive entry: " + name);
                require(entry.getSize() >= 0 && entry.getSize() <= remaining, "Oversized native archive entry: " + name);
                Path destination = VerifiedCache.resolveForWrite(root, name);
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW,
                             StandardOpenOption.WRITE)) {
                    long size = VerifiedCache.copy(input, output, entry.getSize(), null, 0);
                    require(size == entry.getSize(), "Wrong native archive entry size: " + name);
                    remaining -= size;
                }
            }
        }
    }
}
