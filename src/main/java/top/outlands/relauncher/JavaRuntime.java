package top.outlands.relauncher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

final class JavaRuntime {
    private static final Pattern HEAP = Pattern.compile("[0-9]{1,19}[kKmMgGtT]?");

    private JavaRuntime() {}

    static boolean isX64(String arch) {
        return "amd64".equalsIgnoreCase(arch) || "x86_64".equalsIgnoreCase(arch)
                || "x64".equalsIgnoreCase(arch);
    }

    static boolean isWindowsX64(String os, String arch) {
        return os.toLowerCase(Locale.ROOT).startsWith("windows") && isX64(arch);
    }

    static List<Path> candidates(String currentHome, String javaHome, String path) {
        Set<Path> paths = new LinkedHashSet<>();
        addCandidate(paths, currentHome, "bin/java.exe");
        addCandidate(paths, javaHome, "bin/java.exe");
        if (path != null) {
            for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
                String bin = entry.trim();
                if (bin.length() >= 2 && bin.startsWith("\"") && bin.endsWith("\"")) {
                    bin = bin.substring(1, bin.length() - 1);
                }
                addCandidate(paths, bin, "java.exe");
            }
        }
        return new ArrayList<>(paths);
    }

    private static void addCandidate(Set<Path> paths, String dir, String relative) {
        if (dir == null || dir.isEmpty()) {
            return;
        }
        try {
            Path root = Paths.get(dir);
            if (root.isAbsolute()) {
                paths.add(root.resolve(relative).normalize());
            }
        } catch (InvalidPathException ignored) {
            // A malformed PATH entry does not hide later Java installations.
        }
    }

    static ProcessBuilder command(Path gameDir, List<String> argv) {
        ProcessBuilder builder = new ProcessBuilder(argv).directory(gameDir.toFile());
        builder.environment().keySet().removeIf(key -> "JAVA_TOOL_OPTIONS".equalsIgnoreCase(key)
                || "_JAVA_OPTIONS".equalsIgnoreCase(key) || "JDK_JAVA_OPTIONS".equalsIgnoreCase(key)
                || "CLASSPATH".equalsIgnoreCase(key));
        return builder;
    }

    static boolean probe(Path java, Path gameDir) throws InterruptedException {
        if (!java.isAbsolute() || !Files.isRegularFile(java)
                || !"java.exe".equalsIgnoreCase(java.getFileName().toString())) {
            return false;
        }
        List<String> argv = new ArrayList<>();
        argv.add(java.toString());
        argv.add("-XshowSettings:properties");
        argv.add("-version");
        Process process;
        try {
            process = command(gameDir, argv).redirectErrorStream(true).start();
        } catch (IOException failedToStart) {
            return false;
        }
        try {
            process.getOutputStream().close();
            // Drain concurrently so a noisy probe cannot block waiting for pipe space.
            FutureTask<String> read = new FutureTask<>(() -> {
                try (InputStream output = process.getInputStream()) {
                    return readProbe(output);
                }
            });
            Thread reader = new Thread(read, "Outlands Java probe output");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return false;
            }
            return matchesProbe(read.get(1, TimeUnit.SECONDS));
        } catch (IOException | ExecutionException | TimeoutException failedProbe) {
            return false;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String readProbe(InputStream input) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            collected.write(buffer, 0, Math.min(count, 65536 - collected.size()));
        }
        return new String(collected.toByteArray(), StandardCharsets.US_ASCII);
    }

    static boolean matchesProbe(String output) {
        String version = "";
        String arch = "";
        for (String line : output.split("\\R")) {
            int equals = line.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if ("java.specification.version".equals(key)) {
                version = value;
            } else if ("os.arch".equals(key)) {
                arch = value;
            }
        }
        return "25".equals(version) && isX64(arch);
    }

    static String heapOption(List<String> input, String option) {
        String result = "";
        for (String arg : input) {
            if (arg.startsWith(option)) {
                String value = arg.substring(option.length());
                result = HEAP.matcher(value).matches() ? arg : "";
            }
        }
        return result;
    }
}
