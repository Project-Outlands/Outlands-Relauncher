package top.outlands.relauncher.bootstrap;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParentPipeTest {
    @TempDir Path work;

    @Test
    void independentJvmStopsWhenItsOwningPipeCloses() throws Exception {
        String binary = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Paths.get(System.getProperty("java.home"), "bin", binary);
        String classpath = classes(Probe.class) + File.pathSeparator + classes(ParentPipe.class);
        Path log = work.resolve("probe.log");
        Process child = new ProcessBuilder(java.toString(), "-cp", classpath, Probe.class.getName())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (child.isAlive() && (Files.size(log) == 0) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(Arrays.asList("ready"), Files.readAllLines(log, StandardCharsets.UTF_8));
            assertTrue(child.isAlive());
            child.getOutputStream().close();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Detached child kept running after the pipe closed");
            assertEquals(1, child.exitValue());
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    private static Path classes(Class<?> type) throws Exception {
        return Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    public static final class Probe {
        public static void main(String[] args) throws InterruptedException {
            ParentPipe.watch(System.in);
            System.out.println("ready");
            Thread.sleep(TimeUnit.MINUTES.toMillis(1));
        }
    }
}
