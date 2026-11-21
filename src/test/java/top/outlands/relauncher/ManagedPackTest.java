package top.outlands.relauncher;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ManagedPackTest {
    @TempDir Path temp;
    private Path gameDir;
    private Path directory;

    @BeforeEach
    void setUp() throws IOException {
        temp = temp.toRealPath();
        gameDir = Files.createDirectory(temp.resolve("instance"));
        directory = gameDir.resolve("outlands");
    }

    @Test
    void discardsUnretainedZipWithoutTouchingSource() throws IOException {
        Path source = archive(temp.resolve("pack.ZIP"), "mmc");
        byte[] contents = Files.readAllBytes(source);
        Path staged;
        try (ManagedPack pack = ManagedPack.stage(gameDir, source)) {
            staged = pack.path();
            assertEquals(directory, staged.getParent());
            assertTrue(staged.getFileName().toString().endsWith(".ZIP"));
            assertArrayEquals(contents, Files.readAllBytes(staged));
            assertEquals(1, entries(directory));
        }
        assertFalse(Files.exists(staged));
        assertEquals(0, entries(directory));
        assertArrayEquals(contents, Files.readAllBytes(source));
    }

    @Test
    void retainsJarWithoutSourceAndDoesNotOverwriteItOnAnotherStage() throws IOException {
        Path source = archive(temp.resolve("installer.jar"), "first");
        byte[] contents = Files.readAllBytes(source);
        Path retained;
        try (ManagedPack pack = ManagedPack.stage(gameDir, source)) {
            retained = pack.path();
            assertTrue(retained.getFileName().toString().endsWith(".jar"));
            pack.retain();
        }
        Files.delete(source);
        assertArrayEquals(contents, Files.readAllBytes(retained));

        archive(source, "second");
        try (ManagedPack pack = ManagedPack.stage(gameDir, source)) {
            assertNotEquals(retained, pack.path());
            assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(pack.path()));
        }
        assertArrayEquals(contents, Files.readAllBytes(retained));
        assertEquals(1, entries(directory));
    }

    @Test
    void reusesAnArchiveAlreadyInTheManagedTreeWithoutTakingOwnership() throws IOException {
        Path nested = Files.createDirectories(directory.resolve("local"));
        Path original = archive(nested.resolve("pack.zip"), "mmc");
        try (ManagedPack pack = ManagedPack.stage(gameDir, original)) {
            assertEquals(original, pack.path());
        }
        assertTrue(Files.isRegularFile(original));
        assertEquals(1, entries(directory));
        assertEquals(1, entries(nested));
    }

    @Test
    void rejectsInvalidSourcesWithoutLeavingAnArtifact() throws IOException {
        Files.createDirectory(directory);
        Path wrongSuffix = archive(temp.resolve("archive.txt"), "mmc");
        Path notAFile = Files.createDirectory(temp.resolve("directory.jar"));
        assertThrows(IOException.class, () -> ManagedPack.stage(gameDir, wrongSuffix));
        assertThrows(IOException.class, () -> ManagedPack.stage(gameDir, notAFile));
        assertThrows(IOException.class, () -> ManagedPack.stage(gameDir, temp.resolve("missing.zip")));
        assertEquals(0, entries(directory));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void failedCopyRemovesItsTemporaryFile() throws Exception {
        Path source = archive(temp.resolve("pack.zip"), "mmc");
        // Windows sharing denial leaves the source a regular file but makes opening it fail.
        OpenOption noShareRead = (OpenOption) Class.forName("com.sun.nio.file.ExtendedOpenOption")
                .getField("NOSHARE_READ").get(null);
        try (SeekableByteChannel locked = Files.newByteChannel(source, StandardOpenOption.WRITE, noShareRead)) {
            assertThrows(IOException.class, () -> ManagedPack.stage(gameDir, source));
            assertEquals(0, entries(directory));
        }
    }

    @Test
    void rejectsAManagedRootThatIsNotADirectory() throws IOException {
        Path source = archive(temp.resolve("pack.zip"), "mmc");
        Files.write(directory, new byte[] {1});
        assertThrows(IOException.class, () -> ManagedPack.stage(gameDir, source));
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(directory));
    }

    @Test
    void rejectsManagedDirectoryAndArchiveSymlinks() throws IOException {
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path source = archive(outside.resolve("pack.zip"), "mmc");
        symlink(directory, outside);
        assertThrows(IOException.class, () -> ManagedPack.stage(gameDir, source));
        assertEquals(1, entries(outside));

        Files.delete(directory);
        Files.createDirectory(directory);
        Path fileLink = symlink(directory.resolve("pack.zip"), source);
        Path nestedLink = symlink(directory.resolve("nested"), outside);
        assertThrows(IOException.class, () -> ManagedPack.stage(gameDir, fileLink));
        assertThrows(IOException.class, () -> ManagedPack.stage(gameDir, nestedLink.resolve("pack.zip")));
        assertEquals(2, entries(directory));
        assertEquals(1, entries(outside));
    }

    @Test
    void rollbackDoesNotFollowAReplacementManagedDirectory() throws IOException {
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path link = symlink(temp.resolve("link"), outside);
        Files.delete(link);
        Path source = archive(temp.resolve("pack.zip"), "mmc");
        Path moved = temp.resolve("old-outlands");
        try (ManagedPack pack = ManagedPack.stage(gameDir, source)) {
            Files.move(directory, moved);
            Files.createSymbolicLink(directory, outside);
            try {
                Path unrelated = Files.copy(source, outside.resolve(pack.path().getFileName()));
                assertThrows(IOException.class, pack::close);
                assertTrue(Files.isRegularFile(unrelated));
            } finally {
                Files.delete(directory);
                Files.move(moved, directory);
            }
        }
        assertEquals(0, entries(directory));
    }

    private static Path archive(Path path, String contents) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("example.txt"));
            zip.write(contents.getBytes(StandardCharsets.UTF_8));
        }
        return path;
    }

    private static long entries(Path directory) throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.count();
        }
    }

    private static Path symlink(Path link, Path target) throws IOException {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException failure) {
            assumeTrue(false, "Symbolic links unavailable: " + failure);
            throw failure;
        }
    }
}
