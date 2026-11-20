package top.outlands.relauncher;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RelaunchOrchestratorTest {
    @TempDir Path temp;
    private Path instance;
    private Path java;
    private Path config;
    private final FakeUi ui = new FakeUi();

    @BeforeEach
    void instance() throws IOException {
        instance = Files.createDirectory(temp.resolve("Game with space 游戏")).toRealPath();
        java = instance.resolve("java home/bin/java.exe");
        config = Files.createDirectory(instance.resolve("config")).resolve("outlands-relauncher.properties");
    }

    @Test
    void continueForgeAndForgeModeDoNotProbeOrWrite() throws Exception {
        ui.approve = false;
        RelaunchOrchestrator launcher = launcher(builder -> fail("Should not start any process"),
                () -> fail("Should not stop logging"));
        assertNull(launcher.launch(instance, dir -> fail("Should not capture session")));
        assertEquals(1, ui.confirmations);
        assertFalse(Files.exists(config));
        writeConfig("forge");
        assertNull(launcher.launch(instance, dir -> fail("Should not capture session")));
        assertEquals(1, ui.confirmations);
        assertFalse(Files.exists(instance.resolve(".outlands-relauncher")));
    }

    @Test
    void successfulStartStopsLoggingThenSendsSessionAndPropagatesExit() throws Exception {
        writeConfig("ask");
        byte[] payload = "session-only".getBytes(StandardCharsets.UTF_8);
        List<String> events = new ArrayList<>();
        FakeProcess game = new FakeProcess(37, events, true);
        Path[] currentRun = {null};
        Path otherRun = Files.createDirectories(instance.resolve(".outlands-relauncher/runs/other-run"));
        RelaunchOrchestrator launcher = launcher(builder -> {
            List<String> command = builder.command();
            assertEquals(java.toString(), command.get(0));
            assertEquals(instance.toFile(), builder.directory());
            assertEquals(ProcessBuilder.Redirect.INHERIT, builder.redirectOutput());
            assertEquals(ProcessBuilder.Redirect.INHERIT, builder.redirectError());
            assertEquals(ProcessBuilder.Redirect.PIPE, builder.redirectInput());
            if (command.get(1).equals("-cp")) {
                events.add("prepare");
                assertEquals("top.outlands.relauncher.bootstrap.PrepareMain", command.get(3));
                assertEquals(instance.resolve(".outlands-relauncher/cache").toString(), command.get(4));
                assertEquals(instance.resolve("installers/local.jar").toString(), command.get(6));
                currentRun[0] = prepare(command);
                assertEquals(currentRun[0].resolve("bootstrap.jar").toString(), command.get(7));
                assertEquals(currentRun[0].resolve("bootstrap.jar") + ";" + currentRun[0].resolve("gson.jar"), command.get(2));
                return new FakeProcess(0, events, false);
            }
            events.add("game");
            assertEquals(Arrays.asList(java.toString(), "@" + currentRun[0].resolve("java.args")), command);
            assertEquals(RelaunchConfig.Mode.ASK, RelaunchConfig.read(instance).mode);
            return game;
        }, () -> events.add("release-logs"));
        assertEquals(Integer.valueOf(37), launcher.launch(instance, dir -> metadata -> {
            assertEquals(currentRun[0].resolve("launch.properties"), metadata);
            return stdin -> {
                events.add("handoff");
                stdin.write(payload);
            };
        }));
        assertEquals(Arrays.asList("prepare", "game", "release-logs", "handoff", "close-stdin", "wait-game"), events);
        assertArrayEquals(payload, game.stdin.toByteArray());
        assertEquals(RelaunchConfig.Mode.AUTO, RelaunchConfig.read(instance).mode);
        assertEquals(1, ui.confirmations);
        assertTrue(ui.fallbacks.isEmpty());
        assertFalse(Files.exists(currentRun[0]));
        assertTrue(Files.isDirectory(otherRun));
        assertTrue(Files.isDirectory(instance.resolve(".outlands-relauncher/cache")));
    }

    @Test
    void gameStartFailureFallsBackAndKeepsAsk() throws Exception {
        writeConfig("ask");
        RelaunchOrchestrator launcher = launcher(builder -> {
            if (builder.command().get(1).equals("-cp")) {
                prepare(builder.command());
                return new FakeProcess(0, new ArrayList<>(), false);
            }
            throw new IOException("Could not start child");
        }, () -> fail("Forge logging must remain available"));
        assertNull(launcher.launch(instance, dir -> metadata -> stdin -> fail("No child to receive session")));
        assertEquals(RelaunchConfig.Mode.ASK, RelaunchConfig.read(instance).mode);
        assertEquals(1, ui.fallbacks.size());
        assertTrue(ui.fallbacks.get(0).contains("game process"));
        assertRunsEmpty();
    }

    @Test
    void failedHandoffAfterCreationIsTerminalEvenInAutomaticMode() throws Exception {
        writeConfig("auto");
        FakeProcess game = new FakeProcess(0, new ArrayList<>(), true);
        RelaunchOrchestrator launcher = launcher(builder -> {
            if (builder.command().get(1).equals("-cp")) {
                prepare(builder.command());
                return new FakeProcess(0, new ArrayList<>(), false);
            }
            return game;
        }, () -> {});
        assertEquals(Integer.valueOf(1), launcher.launch(instance, dir -> metadata -> stdin -> {
            throw new IOException("Private session details must not be logged");
        }));
        assertTrue(game.destroyed);
        assertEquals(0, ui.confirmations);
        assertTrue(ui.fallbacks.isEmpty());
        assertRunsEmpty();
    }

    @Test
    void interruptedPreparationTerminatesHelperAndRestoresInterrupt() throws Exception {
        writeConfig("auto");
        FakeProcess helper = new FakeProcess(0, new ArrayList<>(), false);
        helper.interruptWait = true;
        RelaunchOrchestrator launcher = launcher(builder -> helper, () -> fail("Forge logging must remain available"));
        try {
            assertNull(launcher.launch(instance, dir -> metadata -> fail("Preparation did not finish")));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertTrue(helper.destroyed);
        assertEquals(1, ui.fallbacks.size());
        assertRunsEmpty();
    }

    @Test
    void prepareCommandKeepsEmptyArgumentsAndFiltersHeapOptions() {
        Path run = instance.resolve("run");
        Path cache = instance.resolve("cache");
        Path bootstrap = run.resolve("bootstrap.jar");
        Path gson = run.resolve("gson.jar");
        List<String> command = RelaunchOrchestrator.prepareCommand(java, instance, cache, run, bootstrap, gson, null,
                Arrays.asList("-Xmx2G", "-Dfoo=bar", "-XX:+UseG1GC")).command();
        assertEquals(Arrays.asList(java.toString(), "-cp", bootstrap + ";" + gson,
                "top.outlands.relauncher.bootstrap.PrepareMain", cache.toString(), run.toString(),
                "", bootstrap.toString(), "", "-Xmx2G"), command);
    }

    private void assertRunsEmpty() throws IOException {
        try (Stream<Path> runs = Files.list(instance.resolve(".outlands-relauncher/runs"))) {
            assertEquals(0, runs.count());
        }
    }

    private void writeConfig(String mode) throws IOException {
        Files.write(config, ("mode=" + mode + "\njavaExecutable=java home/bin/java.exe\n"
                + "localInstaller=installers/local.jar\n").getBytes(StandardCharsets.UTF_8));
    }

    private RelaunchOrchestrator launcher(RelaunchOrchestrator.ProcessStarter starter, Runnable releaseLogs) {
        return new RelaunchOrchestrator(ui, path -> new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8)),
                starter, (exe, dir) -> exe.equals(java), releaseLogs);
    }

    private static Path prepare(List<String> command) throws IOException {
        Path run = Paths.get(command.get(5));
        for (String name : Arrays.asList("bootstrap.jar", "gson.jar")) {
            assertEquals("/META-INF/outlands-relauncher/" + name,
                    new String(Files.readAllBytes(run.resolve(name)), StandardCharsets.UTF_8));
        }
        Files.createFile(run.resolve("java.args"));
        Files.createFile(run.resolve("launch.properties"));
        return run;
    }

    private static final class FakeUi implements RelaunchOrchestrator.Interaction {
        boolean approve = true;
        int confirmations;
        final List<String> fallbacks = new ArrayList<>();

        @Override public boolean confirm() { confirmations++; return approve; }
        @Override public Path pickJava() { return null; }
        @Override public void fallback(String reason) { fallbacks.add(reason); }
    }

    private static final class FakeProcess extends Process {
        final ByteArrayOutputStream stdin;
        final int status;
        final List<String> events;
        final boolean game;
        boolean alive = true;
        boolean destroyed;
        boolean interruptWait;

        FakeProcess(int status, List<String> events, boolean game) {
            this.status = status;
            this.events = events;
            this.game = game;
            this.stdin = new ByteArrayOutputStream() {
                @Override public void close() { if (game) events.add("close-stdin"); }
            };
        }

        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return getInputStream(); }
        @Override public boolean isAlive() { return alive; }
        @Override public int exitValue() { return status; }
        @Override public void destroy() { destroyed = true; alive = false; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return !alive; }
        @Override public int waitFor() throws InterruptedException {
            if (interruptWait) throw new InterruptedException();
            if (game) events.add("wait-game");
            alive = false;
            return status;
        }
    }
}
