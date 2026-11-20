package top.outlands.relauncher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class RelaunchOrchestrator {
    private static final String RESOURCES = "/META-INF/outlands-relauncher/";
    private static final String PREPARE = "top.outlands.relauncher.bootstrap.PrepareMain";

    interface Interaction {
        boolean confirm() throws InterruptedException;
        Path pickJava() throws InterruptedException;
        void fallback(String reason);
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

    private final Interaction ui;
    private final Resources resources;
    private final ProcessStarter processes;
    private final Probe probe;
    private final Runnable releaseLogs;

    RelaunchOrchestrator(Interaction ui, Resources resources, ProcessStarter processes, Probe probe,
                         Runnable releaseLogs) {
        this.ui = ui;
        this.resources = resources;
        this.processes = processes;
        this.probe = probe;
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
            failure = "Could not ask for permission to launch Outlands.";
            if (config.mode == RelaunchConfig.Mode.ASK && !ui.confirm()) {
                return null;
            }
            failure = "Forge did not provide a usable client session or assets.";
            Session session = capture.capture(gameDir);
            failure = "Could not find Java 25 x64. Set javaExecutable in config/outlands-relauncher.properties.";
            Path java = findJava(config, gameDir);
            Path picked = null;
            if (java == null) {
                picked = ui.pickJava();
                if (picked == null || !probe.accepts(picked, gameDir)) {
                    ui.fallback(failure);
                    return null;
                }
                java = picked;
            }

            failure = "Could not create the Outlands run directory or extract the embedded bootstrap JARs.";
            Path root = RelaunchPaths.directory(gameDir, ".outlands-relauncher");
            Path runs = RelaunchPaths.directory(root, "runs");
            Path cache = RelaunchPaths.directory(root, "cache");
            try (Run run = new Run(Files.createTempDirectory(runs, "run-"))) {
                Path bootstrap = extract(run.dir, "bootstrap.jar");
                Path gson = extract(run.dir, "gson.jar");
                failure = "Could not prepare Outlands. Check the preparation output and localInstaller setting.";
                Process prepare = run.start(prepareCommand(java, gameDir, cache, run.dir, bootstrap, gson,
                        config.localInstaller(), ManagementFactory.getRuntimeMXBean().getInputArguments()));
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
                child = run.start(gameCommand(java, gameDir, args));
                return finishGame(child, handoff, config, picked);
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

    private Path findJava(RelaunchConfig config, Path gameDir) throws InterruptedException {
        Path configured = config.javaExecutable();
        if (configured != null) {
            return probe.accepts(configured, gameDir) ? configured : null;
        }
        for (Path candidate : JavaRuntime.candidates(System.getProperty("java.home"), System.getenv("JAVA_HOME"),
                System.getenv("PATH"))) {
            if (probe.accepts(candidate, gameDir)) {
                return candidate;
            }
        }
        return null;
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
                                         Path localInstaller, List<String> vmArgs) {
        return JavaRuntime.command(gameDir, Arrays.asList(java.toString(), "-cp", bootstrap + ";" + gson, PREPARE,
                cache.toString(), run.toString(), localInstaller == null ? "" : localInstaller.toString(),
                bootstrap.toString(), JavaRuntime.heapOption(vmArgs, "-Xms"), JavaRuntime.heapOption(vmArgs, "-Xmx")))
                .redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT);
    }

    static ProcessBuilder gameCommand(Path java, Path gameDir, Path args) {
        return JavaRuntime.command(gameDir, Arrays.asList(java.toString(), "@" + args))
                .redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT);
    }

    private int finishGame(Process child, Handoff handoff, RelaunchConfig config, Path picked) {
        try {
            config.saveAuto(picked);
        } catch (IOException | RuntimeException saveFailure) {
            diagnostic("The Outlands process started, but automatic relaunch settings could not be saved.");
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
