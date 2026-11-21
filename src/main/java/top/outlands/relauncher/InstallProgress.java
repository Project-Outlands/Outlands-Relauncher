package top.outlands.relauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** A bounded snapshot of the preparation process progress. */
final class InstallProgress {
    final String phase;
    final int done;
    final int total;

    InstallProgress(String phase, int done, int total) {
        this.phase = phase;
        this.done = done;
        this.total = total;
    }

    static InstallProgress read(Path run) throws IOException {
        Path file = run.resolve("install-progress");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        RelaunchPaths.requireFile(file);
        if (Files.size(file) > 64) throw new IOException("Oversized installation progress");
        String value = new String(Files.readAllBytes(file), StandardCharsets.US_ASCII).trim();
        if (!value.matches("(?:verify|dependencies|save) [0-9]{1,6} [0-9]{1,6}")) {
            throw new IOException("Invalid installation progress");
        }
        String[] parts = value.split(" ");
        int done = Integer.parseInt(parts[1]);
        int total = Integer.parseInt(parts[2]);
        if (done > total) throw new IOException("Invalid installation progress counts");
        return new InstallProgress(parts[0], done, total);
    }

    boolean matches(InstallProgress other) {
        return other != null && phase.equals(other.phase) && done == other.done && total == other.total;
    }
}
