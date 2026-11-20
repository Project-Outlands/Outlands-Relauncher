package top.outlands.relauncher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;

import top.outlands.relauncher.shared.JvmOptions;

final class RelaunchOrchestrator {
    private static final String RESOURCES = "/META-INF/outlands-relauncher/";
    private static final String PREPARE = "top.outlands.relauncher.bootstrap.PrepareMain";

    interface Interaction {
        default boolean canConfigure() { return true; }
        LaunchSettings configure(LaunchSettings initial, String initialError, SettingsValidator validator)
                throws InterruptedException;
        void fallback(String reason);
    }

    interface SettingsValidator {
        void validate(LaunchSettings settings) throws IOException, InterruptedException;
    }

    interface SessionCapture {
        Session capture(Path gameDir) throws IOException;
    }

    interface Session {
        Handoff resolve(Path launchProperties) throws IOException;
    }

    interface Handoff {
        void write(OutputStream output) throws IOException;
    }

    interface Resources {
        InputStream open(String name);
    }

    interface ProcessStarter {
        Process start(ProcessBuilder builder) throws IOException;
    }

    interface Probe {
        boolean accepts(Path java, Path gameDir) throws InterruptedException;
    }

    interface OptionProbe {
        boolean accepts(Path java, Path gameDir, List<String> options) throws IOException, InterruptedException;
    }

    private final Interaction ui;
    private final Resources resources;
    private final ProcessStarter processes;
    private final Probe probe;
    private final OptionProbe optionProbe;
    private final Runnable releaseLogs;

    RelaunchOrchestrator(Interaction ui, Resources resources, ProcessStarter processes, Probe probe,
                         OptionProbe optionProbe, Runnable releaseLogs) {
        this.ui = ui;
        this.resources = resources;
        this.processes = processes;
        this.probe = probe;
        this.optionProbe = optionProbe;
        this.releaseLogs = releaseLogs;
    }

    // null means Forge may proceed. After the game process starts, only an exit status may be returned.
    Integer launch(Path directory, SessionCapture capture) {
        String failure = "Could not read Outlands Relauncher configuration.";
        Process child = null;
        try {
            Path gameDir = directory.toRealPath();
            RelaunchConfig config = RelaunchConfig.read(gameDir);
            if (config.mode == RelaunchConfig.Mode.FORGE) {
                return null;
            }
            failure = "Could not configure Outlands launch settings.";
            if (config.mode == RelaunchConfig.Mode.ASK && !ui.canConfigure()) return null;
            LaunchSettings initial = config.settings();
            if (initial.javaExecutable.trim().isEmpty()) {
                Path detected = detectJava(gameDir);
                if (detected != null) initial = initial.withJava(detected.toString());
            }
            List<String> inherited = ManagementFactory.getRuntimeMXBean().getInputArguments();
            ValidatedSettings settings = selectSettings(gameDir, config.mode, initial, inherited);
            if (settings == null) return null;
            failure = "Forge did not provide a usable client session or assets.";
            Session session = capture.capture(gameDir);

            failure = "Could not create the Outlands run directory or extract the embedded bootstrap JARs.";
            Path root = RelaunchPaths.directory(gameDir, ".outlands-relauncher");
            Path runs = RelaunchPaths.directory(root, "runs");
            Path cache = RelaunchPaths.directory(root, "cache");
            try (Run run = new Run(Files.createTempDirectory(runs, "run-"))) {
                Path bootstrap = extract(run.dir, "bootstrap.jar");
                Path gson = extract(run.dir, "gson.jar");
                Files.write(run.dir.resolve(JvmOptions.FILE_NAME), settings.options, StandardCharsets.UTF_8);
                failure = "Could not prepare Outlands. Check the preparation output and localInstaller setting.";
                Process prepare = run.start(prepareCommand(settings.java, gameDir, cache, run.dir, bootstrap, gson,
                        settings.installer));
                final int status;
                // Both child JVMs treat this pipe closing as loss of their Forge owner.
                try (OutputStream parentPipe = prepare.getOutputStream()) {
                    status = prepare.waitFor();
                }
                if (status != 0) {
                    diagnostic("Outlands preparation exited with code " + status + ".");
                    throw new IOException("Preparation failed");
                }
                Path args = run.dir.resolve("java.args");
                Path properties = run.dir.resolve("launch.properties");
                RelaunchPaths.requireFile(args);
                RelaunchPaths.requireFile(properties);
                failure = "Could not resolve the client session against the prepared Outlands launch metadata.";
                Handoff handoff = session.resolve(properties);
                failure = "Could not start the Outlands game process.";
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                child = run.start(gameCommand(settings.java, gameDir, args));
                return finishGame(child, handoff, config, settings);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            ui.fallback("Outlands preparation was interrupted.");
        } catch (IOException | RuntimeException | LinkageError problem) {
            if (child != null) {
                diagnostic("Outlands failed after the game process started; Forge will not resume.");
                return 1;
            }
            // Session validators may include raw inputs in exceptions. Never print them here.
            ui.fallback(failure);
        }
        return null;
    }

    private ValidatedSettings selectSettings(Path gameDir, RelaunchConfig.Mode mode, LaunchSettings initial,
                                             List<String> inherited) throws IOException, InterruptedException {
        String error = null;
        if (mode == RelaunchConfig.Mode.AUTO) {
            try {
                // An already valid automatic configuration does not need to be rewritten.
                return validate(gameDir, initial, inherited, false);
            } catch (IOException invalid) {
                error = invalid.getMessage();
                if (!ui.canConfigure()) {
                    ui.fallback(error);
                    return null;
                }
            }
        }
        AtomicReference<ValidatedSettings> approved = new AtomicReference<>();
        LaunchSettings selected = ui.configure(initial, error, candidate ->
                approved.set(validate(gameDir, candidate, inherited, true)));
        if (selected == null) return null;
        ValidatedSettings checked = approved.get();
        if (checked == null || checked.selected != selected) {
            throw new IllegalStateException("Launch settings must be approved before starting");
        }
        return checked;
    }

    private ValidatedSettings validate(Path gameDir, LaunchSettings settings, List<String> inherited, boolean save)
            throws IOException, InterruptedException {
        Path java = settingPath(gameDir, settings.javaExecutable, "Java executable");
        if (java == null || !probe.accepts(java, gameDir)) {
            throw new IOException("Select a working Java 25 x64 bin/java.exe.");
        }
        Path installer = settingPath(gameDir, settings.localInstaller, "Outlands installer");
        if (installer != null) validateInstaller(installer);
        List<String> options = JvmOptions.resolve(settings.xms, settings.xmx, settings.jvmArgs, inherited);
        try {
            if (optionProbe.accepts(java, gameDir, options)) {
                return new ValidatedSettings(java, installer, options, settings, save);
            }
        } catch (IOException error) {
            throw new IOException("Could not verify the JVM parameters. Check that their characters are supported.", error);
        }
        throw new IOException("Java 25 did not accept the JVM parameters. Check the memory values and advanced options.");
    }

    private static void validateInstaller(Path installer) throws IOException {
        if (!Files.isRegularFile(installer)
                || !installer.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IOException("Select an existing Outlands installer .jar, or leave it empty to use the release.");
        }
        try (ZipFile jar = new ZipFile(installer.toFile())) {
            if (jar.getEntry("version.json") == null || jar.getEntry("install_profile.json") == null
                    || !jar.stream().anyMatch(entry -> entry.getName().startsWith("maven/top/outlands/outlands/")
                            && entry.getName().endsWith(".jar") && !entry.isDirectory())) {
                throw new IOException("Missing Outlands installer metadata or loader");
            }
        } catch (IOException invalid) {
            throw new IOException("The selected JAR is not a readable Outlands installer. Choose another file or clear the path.", invalid);
        }
    }

    private static Path settingPath(Path gameDir, String value, String label) throws IOException {
        try {
            return RelaunchConfig.resolvePath(gameDir, value);
        } catch (InvalidPathException invalid) {
            throw new IOException(label + " path is not valid.", invalid);
        }
    }

    private Path detectJava(Path gameDir) throws InterruptedException {
        for (Path candidate : JavaRuntime.candidates(System.getProperty("java.home"), System.getenv("JAVA_HOME"),
                System.getenv("PATH"))) {
            if (probe.accepts(candidate, gameDir)) return candidate;
        }
        return null;
    }

    private static final class ValidatedSettings {
        final Path java;
        final Path installer;
        final List<String> options;
        final LaunchSettings selected;
        final boolean save;

        ValidatedSettings(Path java, Path installer, List<String> options, LaunchSettings selected, boolean save) {
            this.java = java;
            this.installer = installer;
            this.options = options;
            this.selected = selected;
            this.save = save;
        }
    }

    private Path extract(Path runDir, String name) throws IOException {
        Path output = runDir.resolve(name);
        try (InputStream stream = resources.open(RESOURCES + name)) {
            if (stream == null) {
                throw new IOException("Missing embedded " + name);
            }
            Files.copy(stream, output);
        }
        return output;
    }

    static ProcessBuilder prepareCommand(Path java, Path gameDir, Path cache, Path run, Path bootstrap, Path gson,
                                         Path localInstaller) {
        return JavaRuntime.command(gameDir, Arrays.asList(java.toString(), "-cp", bootstrap + ";" + gson, PREPARE,
                cache.toString(), run.toString(), localInstaller == null ? "" : localInstaller.toString(),
                bootstrap.toString()))
                .redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT);
    }

    static ProcessBuilder gameCommand(Path java, Path gameDir, Path args) {
        return JavaRuntime.command(gameDir, Arrays.asList(java.toString(), "@" + args))
                .redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT);
    }

    private int finishGame(Process child, Handoff handoff, RelaunchConfig config, ValidatedSettings settings) {
        try {
            if (settings.save) config.save(settings.selected);
        } catch (IOException | RuntimeException saveFailure) {
            diagnostic("The Outlands process started, but relaunch settings could not be saved.");
        }
        String failure = "Could not release Forge logging before starting Outlands.";
        try (OutputStream stdin = child.getOutputStream()) {
            releaseLogs.run();
            failure = "Could not hand the session to the Outlands process.";
            handoff.write(stdin);
            return waitUninterruptibly(child);
        } catch (IOException | RuntimeException | LinkageError handoffFailure) {
            diagnostic(failure);
            terminate(child);
            return 1;
        }
    }

    static void diagnostic(String message) {
        // Forge overrides println to go through Log4j, but write still reaches the original stderr.
        byte[] line = ("[Outlands Relauncher] " + message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        System.err.write(line, 0, line.length);
        System.err.flush();
    }

    private static int waitUninterruptibly(Process process) {
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    return process.waitFor();
                } catch (InterruptedException signal) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void terminate(Process process) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            // Avoid blocking JVM shutdown indefinitely on a failed OS process termination.
            boolean interrupted = Thread.interrupted();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException signal) {
                interrupted = true;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private final class Run implements AutoCloseable {
        final Path dir;
        private final Thread hook;
        private Process active;
        private boolean closed;

        Run(Path dir) {
            this.dir = dir;
            this.hook = new Thread(this::stopAndClean, "Outlands relaunch shutdown");
            try {
                Runtime.getRuntime().addShutdownHook(hook);
            } catch (RuntimeException unavailable) {
                stopAndClean();
                throw unavailable;
            }
        }

        synchronized Process start(ProcessBuilder command) throws IOException {
            if (closed) {
                throw new IOException("Outlands run is shutting down");
            }
            active = processes.start(command);
            return active;
        }

        @Override
        public void close() {
            stopAndClean();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shutdownInProgress) {
                // The hook may already be running; stopAndClean is synchronized and idempotent.
            } catch (SecurityException denied) {
                diagnostic("Could not unregister the Outlands shutdown hook.");
            }
        }

        private synchronized void stopAndClean() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                terminate(active);
                if (active == null || !active.isAlive()) {
                    RelaunchPaths.cleanRun(dir);
                } else {
                    diagnostic("The Outlands child did not stop; its run directory was left intact.");
                }
            } catch (IOException | RuntimeException cleanupFailure) {
                diagnostic("Could not clean the Outlands run directory.");
            }
        }
    }
}
