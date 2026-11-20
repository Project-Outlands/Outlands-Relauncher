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
    private Path installer;
    private final FakeUi ui = new FakeUi();

    @BeforeEach
    void instance() throws IOException {
        instance = Files.createDirectory(temp.resolve("Game with space 游戏")).toRealPath();
        java = Files.createFile(Files.createDirectories(instance.resolve("java home/bin")).resolve("java.exe"));
        config = instance.resolve("config/outlands-relauncher.properties");
        installer = Files.createFile(Files.createDirectory(instance.resolve("installers")).resolve("local.jar"));
    }

    @Test
    void continueForgeLeavesFirstRunUntouchedAndPrefillsDetectedJava() throws Exception {
        Path detected = Paths.get(System.getProperty("java.home")).resolve("bin/java.exe").normalize();
        ui.selection = (initial, error, validator) -> {
            assertTrue(initial.automatic);
            assertEquals(detected, instance.resolve(initial.javaExecutable).normalize());
            assertUntouchedFirstRun();
            return null;
        };
        RelaunchOrchestrator launcher = launcher(builder -> fail("Should not start a child"),
                (exe, dir) -> exe.equals(detected), (exe, dir, options) -> fail("Should not preflight on cancel"),
                () -> fail("Should not stop logging"));
        assertNull(launcher.launch(instance, dir -> fail("Should not capture session")));
        assertEquals(1, ui.configurations);
        assertUntouchedFirstRun();
    }

    @Test
    void forgeModeBypassesSettingsAndJavaProbes() throws Exception {
        byte[] original = writeConfig("forge");
        RelaunchOrchestrator launcher = launcher(builder -> fail("Should not start a child"),
                (exe, dir) -> fail("Should not probe Java"), (exe, dir, options) -> fail("Should not preflight"),
                () -> fail("Should not stop logging"));
        assertNull(launcher.launch(instance, dir -> fail("Should not capture session")));
        assertEquals(0, ui.configurations);
        assertArrayEquals(original, Files.readAllBytes(config));
        assertFalse(Files.exists(instance.resolve(".outlands-relauncher")));
    }

    @Test
    void successfulStartStopsLoggingThenSendsSessionAndPropagatesExit() throws Exception {
        Path selectedJava = Files.createFile(Files.createDirectories(instance.resolve("selected jdk/bin"))
                .resolve("java.exe"));
        String custom = "-Dexample=two words 游戏\n-XX:+UseG1GC";
        List<String> expectedOptions = Arrays.asList("-Xms512M", "-Xmx2G", "-Dexample=two words 游戏", "-XX:+UseG1GC");
        ui.selection = (initial, error, validator) -> {
            assertTrue(initial.automatic);
            assertUntouchedFirstRun();
            LaunchSettings chosen = new LaunchSettings("selected jdk/bin/java.exe", "installers/local.jar",
                    "512M", "2G", custom, true);
            validate(validator, chosen);
            assertUntouchedFirstRun();
            return chosen;
        };
        byte[] payload = "session-only".getBytes(StandardCharsets.UTF_8);
        List<String> events = new ArrayList<>();
        FakeProcess game = new FakeProcess(37, events, true);
        Path[] currentRun = {null};
        Path otherRun = instance.resolve(".outlands-relauncher/runs/other-run");
        RelaunchOrchestrator launcher = launcher(builder -> {
            List<String> command = builder.command();
            assertEquals(selectedJava.toString(), command.get(0));
            assertEquals(instance.toFile(), builder.directory());
            assertEquals(ProcessBuilder.Redirect.INHERIT, builder.redirectOutput());
            assertEquals(ProcessBuilder.Redirect.INHERIT, builder.redirectError());
            assertEquals(ProcessBuilder.Redirect.PIPE, builder.redirectInput());
            if (command.get(1).equals("-cp")) {
                events.add("prepare");
                Files.createDirectory(otherRun);
                assertEquals("top.outlands.relauncher.bootstrap.PrepareMain", command.get(3));
                assertEquals(instance.resolve(".outlands-relauncher/cache").toString(), command.get(4));
                assertEquals(installer.toString(), command.get(6));
                assertEquals(8, command.size());
                assertEquals(expectedOptions, Files.readAllLines(Paths.get(command.get(5)).resolve("jvm-options.txt"),
                        StandardCharsets.UTF_8));
                currentRun[0] = prepare(command);
                assertEquals(currentRun[0].resolve("bootstrap.jar").toString(), command.get(7));
                assertEquals(currentRun[0].resolve("bootstrap.jar") + ";" + currentRun[0].resolve("gson.jar"), command.get(2));
                return new FakeProcess(0, events, false);
            }
            events.add("game");
            assertEquals(Arrays.asList(selectedJava.toString(), "@" + currentRun[0].resolve("java.args")), command);
            assertFalse(Files.exists(config));
            return game;
        }, (exe, dir) -> exe.equals(selectedJava), (exe, dir, options) -> {
            assertEquals(selectedJava, exe);
            assertEquals(instance, dir);
            assertEquals(expectedOptions, options);
            assertFalse(Files.exists(config));
            return true;
        }, () -> events.add("release-logs"));
        assertEquals(Integer.valueOf(37), launcher.launch(instance, dir -> metadata -> {
            assertEquals(currentRun[0].resolve("launch.properties"), metadata);
            return stdin -> {
                events.add("handoff");
                stdin.write(payload);
            };
        }));
        assertEquals(Arrays.asList("prepare", "game", "release-logs", "handoff", "wait-game", "close-stdin"), events);
        assertArrayEquals(payload, game.stdin.toByteArray());
        RelaunchConfig saved = RelaunchConfig.read(instance);
        assertEquals(RelaunchConfig.Mode.AUTO, saved.mode);
        assertEquals(selectedJava, saved.javaExecutable());
        assertEquals(installer, saved.localInstaller());
        assertEquals(custom, saved.settings().jvmArgs);
        assertEquals(1, ui.configurations);
        assertTrue(ui.fallbacks.isEmpty());
        assertFalse(Files.exists(currentRun[0]));
        assertTrue(Files.isDirectory(otherRun));
        assertTrue(Files.isDirectory(instance.resolve(".outlands-relauncher/cache")));
    }

    @Test
    void rejectedManualSettingsDoNotPrepareOrSaveAnything() throws Exception {
        List<String> rejectedOptions = new ArrayList<>();
        ui.selection = (initial, error, validator) -> {
            assertThrows(IOException.class, () -> validator.validate(new LaunchSettings(
                    "java home/bin/java.exe", "installers/missing.jar", "512M", "2G", "", true)));
            assertThrows(IOException.class, () -> validator.validate(new LaunchSettings(
                    "java home/bin/java.exe", "installers/local.jar", "3G", "2G", "", true)));
            assertThrows(IOException.class, () -> validator.validate(new LaunchSettings(
                    "java home/bin/java.exe", "installers/local.jar", "512M", "2G", "@external.args", true)));
            assertTrue(rejectedOptions.isEmpty(), "Invalid UI inputs must not reach the JVM preflight");
            assertThrows(IOException.class, () -> validator.validate(new LaunchSettings(
                    "java home/bin/java.exe", "installers/local.jar", "512M", "2G", "-XX:+UnknownOption", true)));
            assertUntouchedFirstRun();
            return null;
        };
        RelaunchOrchestrator launcher = launcher(builder -> fail("Should not start a child"),
                (exe, dir) -> exe.equals(java), (exe, dir, options) -> {
                    rejectedOptions.addAll(options);
                    return false;
                }, () -> fail("Should not stop logging"));

        assertNull(launcher.launch(instance, dir -> fail("Should not capture session")));
        assertEquals(Arrays.asList("-Xms512M", "-Xmx2G", "-XX:+UnknownOption"), rejectedOptions);
        assertEquals(1, ui.configurations);
        assertUntouchedFirstRun();
    }

    @Test
    void invalidAutoOptionsOpenRepairWithOriginalSettingsAndSaveOptOutAfterStart() throws Exception {
        byte[] original = writeConfig("auto", "installers/local.jar",
                "xms=512M\nxmx=2G\njvmArgs=-XX:+UnknownOption\nother=keep\n");
        ui.selection = (initial, error, validator) -> {
            assertNotNull(error);
            assertFalse(error.trim().isEmpty());
            assertTrue(initial.automatic);
            assertEquals("java home/bin/java.exe", initial.javaExecutable);
            assertEquals("installers/local.jar", initial.localInstaller);
            assertEquals("512M", initial.xms);
            assertEquals("2G", initial.xmx);
            assertEquals("-XX:+UnknownOption", initial.jvmArgs);
            assertFalse(Files.exists(instance.resolve(".outlands-relauncher")));
            assertDoesNotThrow(() -> {
                assertArrayEquals(original, Files.readAllBytes(config));
            });
            LaunchSettings repaired = new LaunchSettings(initial.javaExecutable, initial.localInstaller,
                    initial.xms, initial.xmx, "-Dexample=repaired", false);
            validate(validator, repaired);
            return repaired;
        };
        RelaunchOrchestrator launcher = launcher(builder -> {
            assertArrayEquals(original, Files.readAllBytes(config));
            if (builder.command().get(1).equals("-cp")) {
                prepare(builder.command());
            }
            return new FakeProcess(0, new ArrayList<>(), false);
        }, (exe, dir) -> exe.equals(java), (exe, dir, options) -> !options.contains("-XX:+UnknownOption"), () -> {});

        assertEquals(Integer.valueOf(0), launcher.launch(instance, dir -> metadata -> stdin -> {}));
        assertEquals(1, ui.configurations);
        assertTrue(ui.fallbacks.isEmpty());
        RelaunchConfig saved = RelaunchConfig.read(instance);
        assertEquals(RelaunchConfig.Mode.ASK, saved.mode);
        assertFalse(saved.settings().automatic);
        assertEquals("-Dexample=repaired", saved.settings().jvmArgs);
    }

    @Test
    void invalidAutomaticInstallerFallsBackWithoutOpeningSettingsWhenHeadless() throws Exception {
        byte[] original = writeConfig("auto", "installers/missing.jar", "");
        ui.configurable = false;
        RelaunchOrchestrator launcher = launcher(builder -> fail("Should not start a child"),
                () -> fail("Should not stop logging"));

        assertNull(launcher.launch(instance, dir -> fail("Should not capture session")));
        assertEquals(0, ui.configurations);
        assertArrayEquals(original, Files.readAllBytes(config));
        assertFalse(Files.exists(instance.resolve(".outlands-relauncher")));
    }

    @Test
    void preparationFailureDoesNotSaveEditedSettings() throws Exception {
        byte[] original = writeConfig("ask");
        ui.selection = (initial, error, validator) -> {
            LaunchSettings changed = new LaunchSettings(initial.javaExecutable, "", "512M", "2G", "", true);
            validate(validator, changed);
            return changed;
        };
        RelaunchOrchestrator launcher = launcher(builder -> {
            assertEquals("top.outlands.relauncher.bootstrap.PrepareMain", builder.command().get(3));
            return new FakeProcess(1, new ArrayList<>(), false);
        }, () -> fail("Forge logging must remain available"));

        assertNull(launcher.launch(instance, dir -> metadata -> fail("Preparation did not finish")));
        assertArrayEquals(original, Files.readAllBytes(config));
        assertRunsEmpty();
    }

    @Test
    void gameStartFailureFallsBackAndKeepsAsk() throws Exception {
        byte[] original = writeConfig("ask");
        ui.selection = (initial, error, validator) -> {
            LaunchSettings changed = new LaunchSettings(initial.javaExecutable, initial.localInstaller,
                    "512M", "2G", "-Dexample=new", true);
            validate(validator, changed);
            return changed;
        };
        RelaunchOrchestrator launcher = launcher(builder -> {
            if (builder.command().get(1).equals("-cp")) {
                prepare(builder.command());
                return new FakeProcess(0, new ArrayList<>(), false);
            }
            throw new IOException("Could not start child");
        }, () -> fail("Forge logging must remain available"));
        assertNull(launcher.launch(instance, dir -> metadata -> stdin -> fail("No child to receive session")));
        assertArrayEquals(original, Files.readAllBytes(config));
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
        assertEquals(0, ui.configurations);
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
    void prepareCommandKeepsEmptyInstallerWithoutAddingJvmOptions() {
        Path run = instance.resolve("run");
        Path cache = instance.resolve("cache");
        Path bootstrap = run.resolve("bootstrap.jar");
        Path gson = run.resolve("gson.jar");
        List<String> command = RelaunchOrchestrator.prepareCommand(java, instance, cache, run, bootstrap, gson, null).command();
        assertEquals(Arrays.asList(java.toString(), "-cp", bootstrap + ";" + gson,
                "top.outlands.relauncher.bootstrap.PrepareMain", cache.toString(), run.toString(),
                "", bootstrap.toString()), command);
    }

    private void assertRunsEmpty() throws IOException {
        try (Stream<Path> runs = Files.list(instance.resolve(".outlands-relauncher/runs"))) {
            assertEquals(0, runs.count());
        }
    }

    private void assertUntouchedFirstRun() {
        assertFalse(Files.exists(config.getParent()));
        assertFalse(Files.exists(instance.resolve(".outlands-relauncher")));
    }

    private byte[] writeConfig(String mode) throws IOException {
        return writeConfig(mode, "installers/local.jar", "");
    }

    private byte[] writeConfig(String mode, String source, String options) throws IOException {
        byte[] contents = ("mode=" + mode + "\njavaExecutable=java home/bin/java.exe\n"
                + "localInstaller=" + source + "\n" + options).getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(config.getParent());
        Files.write(config, contents);
        return contents;
    }

    private RelaunchOrchestrator launcher(RelaunchOrchestrator.ProcessStarter starter, Runnable releaseLogs) {
        return launcher(starter, (exe, dir) -> exe.equals(java), (exe, dir, options) -> true, releaseLogs);
    }

    private RelaunchOrchestrator launcher(RelaunchOrchestrator.ProcessStarter starter, RelaunchOrchestrator.Probe probe,
            RelaunchOrchestrator.OptionProbe optionProbe, Runnable releaseLogs) {
        return new RelaunchOrchestrator(ui, path -> new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8)),
                starter, probe, optionProbe, releaseLogs);
    }

    private static void validate(RelaunchOrchestrator.SettingsValidator validator, LaunchSettings settings)
            throws InterruptedException {
        try {
            validator.validate(settings);
        } catch (IOException invalidSettings) {
            fail("Expected test settings to validate", invalidSettings);
        }
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

    @FunctionalInterface
    private interface Selection {
        LaunchSettings select(LaunchSettings initial, String initialError,
                              RelaunchOrchestrator.SettingsValidator validator) throws InterruptedException;
    }

    private static final class FakeUi implements RelaunchOrchestrator.Interaction {
        boolean configurable = true;
        int configurations;
        Selection selection = (initial, error, validator) -> {
            validate(validator, initial);
            return initial;
        };
        final List<String> fallbacks = new ArrayList<>();

        @Override public boolean canConfigure() { return configurable; }
        @Override public LaunchSettings configure(LaunchSettings initial, String initialError,
                RelaunchOrchestrator.SettingsValidator validator) throws InterruptedException {
            assertTrue(configurable, "Headless launch must not open settings");
            configurations++;
            return selection.select(initial, initialError, validator);
        }
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
