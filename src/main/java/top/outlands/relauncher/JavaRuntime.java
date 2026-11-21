package top.outlands.relauncher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import top.outlands.relauncher.shared.JavaArgFile;
import top.outlands.relauncher.shared.LaunchPlatform;

final class JavaRuntime {
    private JavaRuntime() {}

    /** Tests a basename only; filesystem and version checks are performed by probe. */
    static boolean isJavaExecutableName(String name, LaunchPlatform platform) {
        if (platform == null) {
            return false;
        }
        return platform.windows() ? "java.exe".equalsIgnoreCase(name) || "javaw.exe".equalsIgnoreCase(name)
                : "java".equals(name);
    }

    static ProcessBuilder command(Path gameDir, List<String> argv) {
        ProcessBuilder builder = new ProcessBuilder(argv).directory(gameDir.toFile());
        builder.environment().keySet().removeIf(key -> "JAVA_TOOL_OPTIONS".equalsIgnoreCase(key)
                || "_JAVA_OPTIONS".equalsIgnoreCase(key) || "JDK_JAVA_OPTIONS".equalsIgnoreCase(key)
                || "CLASSPATH".equalsIgnoreCase(key));
        return builder;
    }

    static boolean probe(Path java, Path gameDir, int minimumJava) throws InterruptedException {
        if (!java.isAbsolute() || !Files.isRegularFile(java)
                || !isJavaExecutableName(java.getFileName().toString(), LaunchPlatform.current())) {
            return false;
        }
        String output = probeOutput(gameDir, Arrays.asList(java.toString(), "-XshowSettings:properties", "-version"));
        return output != null && matchesProbe(output, minimumJava);
    }

    static boolean probeOptions(Path java, Path gameDir, List<String> options) throws IOException, InterruptedException {
        Path argFile = Files.createTempFile("outlands-java-check-", ".args");
        try {
            List<String> argv = new ArrayList<>(options);
            argv.add("-version");
            try (OutputStream output = Files.newOutputStream(argFile)) {
                JavaArgFile.write(output, argv, JavaArgFile.launcherCharset());
            }
            return probeOutput(gameDir, Arrays.asList(java.toString(), "@" + argFile)) != null;
        } finally {
            Files.deleteIfExists(argFile);
        }
    }

    private static String probeOutput(Path gameDir, List<String> argv) throws InterruptedException {
        Process process;
        try {
            process = command(gameDir, argv).redirectErrorStream(true).start();
        } catch (IOException failedToStart) {
            return null;
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
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return null;
            }
            return read.get(1, TimeUnit.SECONDS);
        } catch (IOException | ExecutionException | TimeoutException failedProbe) {
            return null;
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

    static boolean matchesProbe(String output, int minimumJava) {
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
        LaunchPlatform runtimePlatform = LaunchPlatform.detect(System.getProperty("os.name", ""), arch, "");
        if (runtimePlatform == null || !version.matches("[0-9]+")) {
            return false;
        }
        try {
            return Integer.parseInt(version) >= minimumJava;
        } catch (NumberFormatException invalidFeatureVersion) {
            return false;
        }
    }
}
