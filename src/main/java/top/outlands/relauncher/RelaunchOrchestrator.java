package top.outlands.relauncher;

import java.io.DataInputStream;
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
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import top.outlands.relauncher.LaunchSettings.Field;
import top.outlands.relauncher.RelaunchMessages.Message;
import top.outlands.relauncher.shared.JvmOptions;
import top.outlands.relauncher.shared.PackSettings;
import top.outlands.relauncher.shared.ValidationException;

final class RelaunchOrchestrator {
    private static final String RESOURCES = "/META-INF/outlands-relauncher/";
    private static final String PREPARE = "top.outlands.relauncher.bootstrap.PrepareMain";

    interface Interaction {
        default boolean canConfigure() { return true; }
        LaunchSettings configure(LaunchSettings initial, Message initialError, SettingsValidator validator,
                                 JavaSelectionValidator javaValidator)
                throws InterruptedException;
        void fallback(Message reason);
    }

    interface SettingsValidator {
        void validate(LaunchSettings settings) throws IOException, InterruptedException;
    }

    interface JavaSelectionValidator {
        void validate(String path) throws IOException, InterruptedException;
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
        String failure = "fallback.config";
        Process child = null;
        try {
            Path gameDir = directory.toRealPath();
            RelaunchConfig config = RelaunchConfig.read(gameDir);
            if (config.mode == RelaunchConfig.Mode.FORGE) {
                return null;
            }
            failure = "fallback.settings";
            if (config.mode == RelaunchConfig.Mode.ASK && !ui.canConfigure()) return null;
            LaunchSettings initial = config.settings();
            List<String> inherited = ManagementFactory.getRuntimeMXBean().getInputArguments();
            ValidatedSettings settings = selectSettings(gameDir, config.mode, initial, inherited, null);
            if (settings == null) return null;
            failure = "fallback.session";
            Session session = capture.capture(gameDir);

            for (;;) {
                failure = "fallback.runDirectory";
                Path root = RelaunchPaths.directory(gameDir, ".outlands-relauncher");
                Path runs = RelaunchPaths.directory(root, "runs");
                Path cache = RelaunchPaths.directory(root, "cache");
                failure = "fallback.persistPack";
                try (ManagedPack staged = settings.archive == null ? null : ManagedPack.stage(gameDir, settings.archive.toRealPath());
                     Run run = new Run(Files.createTempDirectory(runs, "run-"))) {
                    failure = "fallback.runDirectory";
                    Path bootstrap = extract(run.dir, "bootstrap.jar");
                    Path gson = extract(run.dir, "gson.jar");
                    Files.write(run.dir.resolve(JvmOptions.FILE_NAME), settings.options, StandardCharsets.UTF_8);
                    try (OutputStream output = Files.newOutputStream(run.dir.resolve(PackSettings.FILE_NAME))) {
                        settings.packOptions.store(output, null);
                    }
                    failure = "fallback.prepare";
                    Process prepare = run.start(prepareCommand(settings.java, gameDir, cache, run.dir, bootstrap, gson,
                            staged == null ? null : staged.path()));
                    final int status;
                    // Both child JVMs treat this pipe closing as loss of their Forge owner.
                    try (OutputStream parentPipe = prepare.getOutputStream()) {
                        status = prepare.waitFor();
                    }
                    if (status != 0 && (status != PackSettings.INVALID_LOCAL_PACK_EXIT || staged == null)) {
                        diagnostic("Outlands preparation exited with code " + status + ".");
                        throw new IOException("Preparation failed");
                    }
                    if (status == 0) {
                        Path args = run.dir.resolve("java.args");
                        Path properties = run.dir.resolve("launch.properties");
                        RelaunchPaths.requireFile(args);
                        RelaunchPaths.requireFile(properties);
                        failure = "fallback.launchMetadata";
                        Handoff handoff = session.resolve(properties);
                        failure = "fallback.start";
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException();
                        }
                        child = run.start(gameCommand(settings.java, gameDir, args));
                        LaunchSettings persisted = settings.selected;
                        boolean save = settings.save;
                        if (staged != null) {
                            staged.retain();
                            String localPath = gameDir.relativize(staged.path()).toString();
                            persisted = persisted.withArchive(localPath);
                            save |= !localPath.equals(settings.selected.localMmcPack.isEmpty()
                                    ? settings.selected.localInstaller : settings.selected.localMmcPack);
                        }
                        return finishGame(child, handoff, config, save ? persisted : null);
                    }
                }
                // Local metadata can fail after preflight; do not misclassify network/download failures.
                boolean mmc = !settings.selected.localMmcPack.trim().isEmpty();
                Message issue = new Message(mmc ? "error.validation.mmcUnreadable" : "error.validation.installerUnreadable");
                if (!ui.canConfigure()) {
                    ui.fallback(issue);
                    return null;
                }
                failure = "fallback.settings";
                settings = selectSettings(gameDir, RelaunchConfig.Mode.ASK,
                        settings.selected.clear(mmc ? Field.MMC : Field.INSTALLER), inherited, issue);
                if (settings == null) return null;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            ui.fallback(new Message("fallback.interrupted"));
        } catch (IOException | RuntimeException | LinkageError problem) {
            if (child != null) {
                diagnostic("Outlands failed after the game process started; Forge will not resume.");
                return 1;
            }
            // Session validators may include raw inputs in exceptions. Never print them here.
            ui.fallback(new Message(failure));
        }
        return null;
    }

    private ValidatedSettings selectSettings(Path gameDir, RelaunchConfig.Mode mode, LaunchSettings initial,
                                             List<String> inherited, Message initialError)
            throws IOException, InterruptedException {
        boolean ask = mode == RelaunchConfig.Mode.ASK;
        boolean javaMissing = initial.javaExecutable.trim().isEmpty();
        Message error = !ask && javaMissing ? new Message("error.validation.java") : initialError;
        if (error != null && !ui.canConfigure()) {
            ui.fallback(error);
            return null;
        }
        LaunchSettings draft = initial;
        boolean repairing = ask || javaMissing;
        // Each successful repair clears a nonempty field. Never overwrite the saved config here.
        for (;;) {
            try {
                ValidatedSettings checked = validate(gameDir, draft, inherited, false, repairing);
                if (!repairing) return checked;
                break;
            } catch (ValidationException invalid) {
                Message issue = RelaunchMessages.validation(invalid);
                error = error == null ? issue : new Message("validation.join", error, issue);
                if (!ui.canConfigure()) {
                    ui.fallback(error);
                    return null;
                }
                Field field = repairField(invalid, draft, gameDir, inherited);
                if (field == null || draft.value(field).trim().isEmpty()) break;
                draft = draft.clear(field);
                repairing = true;
            }
        }
        AtomicReference<ValidatedSettings> approved = new AtomicReference<>();
        LaunchSettings selected = ui.configure(draft, error, candidate ->
                approved.set(validate(gameDir, candidate, inherited, true, false)), path -> validateJava(gameDir, path));
        if (selected == null) return null;
        ValidatedSettings checked = approved.get();
        if (checked == null || checked.selected != selected) {
            throw new IllegalStateException("Launch settings must be approved before starting");
        }
        return checked;
    }

    private Field repairField(ValidationException invalid, LaunchSettings draft, Path gameDir,
                              List<String> inherited) throws IOException, InterruptedException {
        switch (invalid.key()) {
            case "error.validation.javaPath":
            case "error.validation.java": return Field.JAVA;
            case "error.validation.installerPath":
            case "error.validation.installerMissing":
            case "error.validation.installerUnreadable":
            case "error.validation.multiplePackages": return Field.INSTALLER;
            case "error.validation.mmcPath":
            case "error.validation.mmcMissing":
            case "error.validation.mmcUnreadable": return Field.MMC;
            case "error.validation.librariesPath":
            case "error.validation.libraries":
            case "error.validation.librariesOverlap": return Field.LIBRARIES;
            case "error.validation.pack.releaseVersion": return Field.RELEASE;
            case "error.validation.pack.proxy": return Field.PROXY;
            case "error.validation.pack.mavenMirror": return Field.MIRROR;
            case "error.validation.jvm.heapOverflow":
            case "error.validation.jvm.heapInvalidSize":
                return "Xms".equals(invalid.arguments()[0]) ? Field.XMS : Field.XMX;
            case "error.validation.jvm.heapOrder": return draft.xms.trim().isEmpty() ? Field.XMX : Field.XMS;
            case "error.validation.jvmRejected":
                return isolateRejectedOptions(draft, gameDir, inherited);
            case "error.validation.jvmProbe": return null;
            default: return invalid.key().startsWith("error.validation.jvm.") ? Field.JVM_ARGS : null;
        }
    }

    private Field isolateRejectedOptions(LaunchSettings draft, Path gameDir, List<String> inherited)
            throws InterruptedException {
        Path java = RelaunchConfig.resolvePath(gameDir, draft.javaExecutable);
        Field repair = null;
        for (Field candidate : Arrays.asList(Field.JVM_ARGS, Field.XMS, Field.XMX)) {
            if (draft.value(candidate).trim().isEmpty()) continue;
            LaunchSettings trial = draft.clear(candidate);
            try {
                if (optionProbe.accepts(java, gameDir, JvmOptions.resolve(trial.xms, trial.xmx, trial.jvmArgs, inherited))) {
                    if (repair != null) return null; // More than one edit would work; don't guess which setting is wrong.
                    repair = candidate;
                }
            } catch (ValidationException stillInvalid) {
                // Removing this field would introduce a validation error, e.g. through inherited heap values.
            } catch (IOException unavailable) {
                return null;
            }
        }
        return repair;
    }

    private ValidatedSettings validate(Path gameDir, LaunchSettings settings, List<String> inherited, boolean save,
                                       boolean allowUnsetJava)
            throws IOException, InterruptedException {
        Path java = allowUnsetJava && settings.javaExecutable.trim().isEmpty()
                ? null : validateJava(gameDir, settings.javaExecutable);
        Path installer = settingPath(gameDir, settings.localInstaller, "error.validation.installerPath");
        Path mmc = settingPath(gameDir, settings.localMmcPack, "error.validation.mmcPath");
        if (installer != null && mmc != null) throw invalid("error.validation.multiplePackages");
        if (installer != null) validateArchive(installer, false);
        if (mmc != null) validateArchive(mmc, true);
        Properties pack = new Properties();
        pack.setProperty(PackSettings.RELEASE_VERSION, PackSettings.releaseVersion(settings.releaseVersion));
        if (PackSettings.proxy(settings.proxy) != null) pack.setProperty(PackSettings.PROXY, settings.proxy.trim());
        if (PackSettings.mirror(settings.mavenMirror) != null) pack.setProperty(PackSettings.MAVEN_MIRROR, settings.mavenMirror.trim());
        Path libraries = settingPath(gameDir, settings.librariesDirectory, "error.validation.librariesPath");
        if (libraries != null) {
            if (!Files.isDirectory(libraries)) throw invalid("error.validation.libraries");
            try {
                libraries = libraries.toRealPath();
            } catch (IOException unavailable) {
                throw invalid("error.validation.libraries", unavailable);
            }
            Path cache = gameDir.resolve(".outlands-relauncher/cache");
            if (libraries.startsWith(cache) || cache.startsWith(libraries)) {
                throw invalid("error.validation.librariesOverlap");
            }
            pack.setProperty(PackSettings.LIBRARIES_DIRECTORY, libraries.toString());
        }
        List<String> options = JvmOptions.resolve(settings.xms, settings.xmx, settings.jvmArgs, inherited);
        try {
            if (java == null || optionProbe.accepts(java, gameDir, options)) {
                return new ValidatedSettings(java, mmc == null ? installer : mmc, pack, options, settings, save);
            }
        } catch (IOException error) {
            throw invalid("error.validation.jvmProbe", error);
        }
        throw invalid("error.validation.jvmRejected");
    }

    private static void validateArchive(Path archive, boolean mmc) throws IOException {
        String extension = mmc ? ".zip" : ".jar";
        String label = mmc ? "MMC pack" : "installer";
        if (!Files.isRegularFile(archive) || !archive.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension)) {
            throw invalid(mmc ? "error.validation.mmcMissing" : "error.validation.installerMissing");
        }
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            boolean valid = mmc ? hasOutlandsMmcPatches(zip)
                    : zip.getEntry("version.json") != null && zip.getEntry("install_profile.json") != null
                    && zip.stream().anyMatch(entry -> entry.getName().startsWith("maven/top/outlands/outlands/")
                            && entry.getName().endsWith(".jar") && !entry.isDirectory());
            if (!valid) throw new IOException("Missing Outlands " + label + " metadata");
        } catch (IOException cause) {
            throw invalid(mmc ? "error.validation.mmcUnreadable" : "error.validation.installerUnreadable", cause);
        }
    }

    private static boolean hasOutlandsMmcPatches(ZipFile zip) throws IOException {
        String manifest = zip.stream().filter(entry -> !entry.isDirectory()
                && entry.getName().matches("(?:[^/]+/)?mmc-pack\\.json"))
                .map(entry -> entry.getName()).findFirst().orElse(null);
        if (manifest == null) return false;
        String root = manifest.substring(0, manifest.length() - "mmc-pack.json".length());
        if (zip.getEntry(root + "patches/net.minecraft.json") == null
                || zip.getEntry(root + "patches/org.lwjgl3.json") == null) return false;
        ZipEntry patch = zip.getEntry(root + "patches/net.minecraftforge.json");
        if (patch == null || patch.getSize() <= 0 || patch.getSize() > 2 * 1024 * 1024) return false;
        try (InputStream input = zip.getInputStream(patch)) {
            byte[] contents = new byte[(int) patch.getSize()];
            new DataInputStream(input).readFully(contents);
            if (input.read() != -1) return false;
            JsonElement json = new JsonParser().parse(new String(contents, StandardCharsets.UTF_8));
            JsonElement main = json.isJsonObject() ? json.getAsJsonObject().get("mainClass") : null;
            return main != null && main.isJsonPrimitive()
                    && "top.outlands.foundation.boot.Foundation".equals(main.getAsString());
        } catch (JsonParseException invalid) {
            throw new IOException("Invalid Outlands component metadata", invalid);
        }
    }

    private static Path settingPath(Path gameDir, String value, String messageKey) throws IOException {
        try {
            return RelaunchConfig.resolvePath(gameDir, value);
        } catch (InvalidPathException cause) {
            throw invalid(messageKey, cause);
        }
    }

    private static ValidationException invalid(String key) {
        return invalid(key, null);
    }

    private static ValidationException invalid(String key, Throwable cause) {
        return new ValidationException(key, new RelaunchMessages(Locale.ENGLISH).text(key), cause);
    }

    private Path validateJava(Path gameDir, String value) throws IOException, InterruptedException {
        Path java = settingPath(gameDir, value, "error.validation.javaPath");
        if (java == null || !probe.accepts(java, gameDir)) throw invalid("error.validation.java");
        return java;
    }

    private static final class ValidatedSettings {
        final Path java;
        final Path archive;
        final Properties packOptions;
        final List<String> options;
        final LaunchSettings selected;
        final boolean save;

        ValidatedSettings(Path java, Path archive, Properties packOptions, List<String> options, LaunchSettings selected,
                          boolean save) {
            this.java = java;
            this.archive = archive;
            this.packOptions = packOptions;
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

    private int finishGame(Process child, Handoff handoff, RelaunchConfig config, LaunchSettings toSave) {
        try {
            if (toSave != null) config.save(toSave);
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
