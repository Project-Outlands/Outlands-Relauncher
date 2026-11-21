package top.outlands.relauncher.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipFile;

import top.outlands.relauncher.shared.JavaArgFile;
import top.outlands.relauncher.shared.JvmOptions;
import top.outlands.relauncher.shared.LaunchPlatform;
import top.outlands.relauncher.shared.PackSettings;
import top.outlands.relauncher.shared.ReleaseCatalog;

import static top.outlands.relauncher.bootstrap.InstallerMetadata.require;

/** Isolated preparation process; never receives resolved game arguments or login details. */
public final class PrepareMain {
    private static final String RELEASE_PATH = releasePath(PackSettings.DEFAULT_VERSION);
    private static final InstallerMetadata.Artifact RELEASE = new InstallerMetadata.Artifact(RELEASE_PATH,
            288_995L, "SHA-256", "f55bac455fb68a54ce52b9feed654123a677ca72beef1c30421e7e528f153c9e",
            releaseUri(RELEASE_PATH), Collections.emptyList());
    private static final String CHILD_MAIN = "top.outlands.relauncher.bootstrap.ChildMain";

    private PrepareMain() {}

    public static void main(String[] args) {
        try {
            boolean install = installMode(args);
            LaunchPlatform platform = currentPlatform();
            int javaMajor = currentJavaMajor();
            ParentPipe.watch(System.in);
            Path progress = startInstallProgress(args[1], install);
            Properties settings = readPackOptions(Paths.get(args[1]));
            DownloadOptions options = DownloadOptions.read(settings, Paths.get("").toAbsolutePath());
            InstallerMetadata.Artifact release = RELEASE;
            if (args[2].isEmpty()) {
                String version = PackSettings.releaseVersion(settings.getProperty(PackSettings.RELEASE_VERSION, ""));
                if (!PackSettings.DEFAULT_VERSION.equals(version)) {
                    Path cache = Paths.get(args[0]);
                    require(ReleaseCatalog.cached(cache).contains(version), "Outlands release is not in the preset list");
                    release = customRelease(cache, options::download, version);
                }
            }
            prepare(args, options::download, release, options, progress, platform, javaMajor);
        } catch (IOException | RuntimeException ex) {
            System.err.println("Outlands preparation failed: " + ex.getMessage());
            System.exit(ex instanceof InvalidLocalPack ? PackSettings.INVALID_LOCAL_PACK_EXIT : 1);
        }
    }

    private static boolean installMode(String[] args) throws IOException {
        boolean install = args.length == 5 && "--install".equals(args[4]);
        require(args.length == 4 || install,
                "Expected cacheDir, runDir, local MMC pack / installer (or empty), bootstrapJar [--install]");
        return install;
    }

    private static Path startInstallProgress(String runDir, boolean install) throws IOException {
        if (!install) return null;
        Path progress = Paths.get(runDir).toRealPath().resolve("install-progress");
        writeProgress(progress, "verify", 0, 0);
        return progress;
    }

    static void prepare(String[] args, VerifiedCache.Downloader downloader, InstallerMetadata.Artifact release,
                        int javaMajor) throws IOException {
        boolean install = installMode(args);
        prepare(args, downloader, release, DownloadOptions.DEFAULT, startInstallProgress(args[1], install),
                currentPlatform(), javaMajor);
    }

    private static void prepare(String[] args, VerifiedCache.Downloader downloader, InstallerMetadata.Artifact release,
                                DownloadOptions options, Path progress, LaunchPlatform platform, int javaMajor)
            throws IOException {
        Path runDir = Paths.get(args[1]).toRealPath();
        require(Files.isDirectory(runDir), "Run directory must already exist");
        Path argFile = runDir.resolve("java.args");
        Path propertiesFile = runDir.resolve("launch.properties");
        require(!Files.exists(argFile, LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(propertiesFile, LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(runDir.resolve("natives"), LinkOption.NOFOLLOW_LINKS), "Run directory was already used");
        Path bootstrapJar = absoluteFile(args[3], "Bootstrap JAR");
        List<String> userJvm = readJvmOptions(runDir);
        VerifiedCache cache = new VerifiedCache(Paths.get(args[0]), downloader, options);
        boolean local = !args[2].isEmpty();
        boolean mmc = (local ? args[2] : release.path).toLowerCase(Locale.ROOT).endsWith(".zip");
        List<String> classpath = new ArrayList<>();
        Path natives = runDir.resolve("natives");
        InstallerMetadata metadata;
        boolean readingLocalMetadata = local;
        try (ZipFile zip = new ZipFile((local ? absoluteFile(args[2], "Local Outlands package")
                : cache.obtain(release, null)).toFile())) {
            metadata = mmc ? MmcMetadata.read(zip, platform, javaMajor) : InstallerMetadata.read(zip, platform, javaMajor);
            readingLocalMetadata = false;
            if (mmc && !local) {
                require(release.path.equals(releasePath(metadata.id.substring("1.12.2-Outlands-".length()))),
                        "Outlands release does not match the requested MMC package version");
            }
            System.out.println("Preparing Outlands " + metadata.id);
            int done = 0;
            int total = metadata.classpath.size() + metadata.natives.size();
            writeProgress(progress, "dependencies", done, total);
            for (InstallerMetadata.Artifact artifact : metadata.classpath) {
                VerifiedCache.Source embedded = artifact.embeddedPath == null
                        ? null : () -> zip.getInputStream(zip.getEntry(artifact.embeddedPath));
                classpath.add(classpathEntry(cache.obtain(artifact, embedded), platform));
                writeProgress(progress, "dependencies", ++done, total);
            }
            NativeExtractor extractor = new NativeExtractor(natives);
            for (InstallerMetadata.Artifact artifact : metadata.natives) {
                Path archive = cache.obtain(artifact, null);
                writeProgress(progress, "dependencies", ++done, total);
                extractor.extract(archive, artifact.excludes);
            }
        } catch (IOException problem) {
            if (readingLocalMetadata) throw new InvalidLocalPack(problem);
            throw problem;
        }
        classpath.add(classpathEntry(bootstrapJar, platform));
        List<String> jvm = new ArrayList<>(metadata.jvm);
        jvm.addAll(userJvm);
        jvm.add("-Doutlands.relauncher.child=true");
        jvm.add("-Djava.library.path=" + natives);
        jvm.add("-cp");
        jvm.add(String.join(platform.pathSeparator(), classpath));
        jvm.add(CHILD_MAIN);
        writeAtomically(argFile, output -> JavaArgFile.write(output, jvm, JavaArgFile.launcherCharset()));

        Properties properties = new Properties();
        properties.setProperty("version", metadata.id);
        properties.setProperty("game.count", Integer.toString(metadata.game.size()));
        for (int i = 0; i < metadata.game.size(); i++) {
            properties.setProperty("game." + i, metadata.game.get(i));
        }
        writeAtomically(propertiesFile, output -> properties.store(output, null));
        writeProgress(progress, "save", 0, 0);
        System.out.println("Outlands preparation complete");
    }

    static final class InvalidLocalPack extends IOException {
        private static final long serialVersionUID = 1L;

        InvalidLocalPack(IOException cause) {
            super("Could not read local Outlands package metadata: " + cause.getMessage(), cause);
        }
    }

    private static Properties readPackOptions(Path runDir) throws IOException {
        Properties properties = new Properties();
        Path settings = runDir.resolve(PackSettings.FILE_NAME);
        if (!Files.exists(settings, LinkOption.NOFOLLOW_LINKS)) return properties;
        require(Files.isRegularFile(settings, LinkOption.NOFOLLOW_LINKS) && Files.size(settings) <= 32 * 1024,
                "Invalid pack options file");
        try (InputStream input = Files.newInputStream(settings)) {
            properties.load(input);
        }
        return properties;
    }

    private static String releasePath(String version) {
        return "top/outlands/outlands/" + version + "/outlands-" + version + "-mmc.zip";
    }

    private static URI releaseUri(String path) {
        return VerifiedCache.releaseSources(path).get(0);
    }

    // Remember a previously verified release descriptor so a selected version can launch offline too.
    static InstallerMetadata.Artifact customRelease(Path cache, VerifiedCache.Downloader downloader, String version)
            throws IOException {
        Path root = Files.createDirectories(cache).toRealPath();
        Path descriptor = VerifiedCache.resolveForWrite(root, "releases/" + version + ".sha256");
        String path = releasePath(version);
        if (Files.isRegularFile(descriptor, LinkOption.NOFOLLOW_LINKS) && Files.size(descriptor) <= 128) {
            try {
                return customReleaseArtifact(path, checksum(Files.readAllBytes(descriptor)));
            } catch (IOException damaged) {
                System.err.println("Cached Outlands release checksum is unreadable; requesting it again.");
            }
        }
        IOException failure = new IOException("Could not obtain the SHA-256 for Outlands MMC " + version);
        for (URI source : VerifiedCache.releaseSources(path + ".sha256")) {
            final String hash;
            try (InputStream stream = downloader.open(source)) {
                hash = checksum(VerifiedCache.readBytes(stream, 128));
            } catch (IOException unavailable) {
                failure.addSuppressed(new IOException(source.getHost() + ": " + unavailable.getMessage(), unavailable));
                continue;
            }
            writeAtomically(descriptor, output -> output.write(hash.getBytes(StandardCharsets.US_ASCII)));
            return customReleaseArtifact(path, hash);
        }
        throw failure;
    }

    private static InstallerMetadata.Artifact customReleaseArtifact(String path, String hash) {
        return new InstallerMetadata.Artifact(path, -1, "SHA-256", hash, releaseUri(path), Collections.emptyList());
    }

    private static String checksum(byte[] bytes) throws IOException {
        String value = new String(bytes, StandardCharsets.US_ASCII).trim().toLowerCase(Locale.ROOT);
        require(value.matches("[a-f0-9]{64}"), "Outlands Maven returned an invalid MMC package SHA-256");
        return value;
    }

    private static Path absoluteFile(String input, String label) throws IOException {
        Path path = Paths.get(input);
        require(path.isAbsolute() && Files.isRegularFile(path), label + " must be an existing absolute filename");
        return path.toRealPath();
    }

    private static List<String> readJvmOptions(Path runDir) throws IOException {
        Path path = runDir.resolve(JvmOptions.FILE_NAME);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new ArrayList<>();
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= JvmOptions.MAX_BYTES,
                "Invalid JVM options file");
        List<String> options = Files.readAllLines(path, StandardCharsets.UTF_8);
        JvmOptions.validate(options);
        return options;
    }

    private static LaunchPlatform currentPlatform() throws IOException {
        LaunchPlatform platform = LaunchPlatform.current();
        require(platform != null, "Preparation requires Windows, Linux, or macOS on x86_64 or arm64");
        return platform;
    }

    private static int currentJavaMajor() throws IOException {
        String version = System.getProperty("java.specification.version", "0");
        int major = Integer.parseInt(version.startsWith("1.") ? version.substring(2) : version);
        require(major >= InstallerMetadata.MIN_JAVA, "Preparation requires Java 25 or newer");
        return major;
    }

    static String classpathEntry(Path path, LaunchPlatform platform) throws IOException {
        String value = path.toString();
        require(!value.contains(platform.pathSeparator()),
                "Classpath cannot use paths containing the separator '" + platform.pathSeparator() + "'");
        return value;
    }

    private static void writeProgress(Path target, String phase, int done, int total) throws IOException {
        if (target == null) return;
        byte[] record = (phase + " " + done + " " + total + "\n").getBytes(StandardCharsets.UTF_8);
        writeAtomically(target, output -> output.write(record));
    }

    private interface OutputWriter {
        void write(OutputStream output) throws IOException;
    }

    private static void writeAtomically(Path target, OutputWriter writer) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".outlands-", ".part");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                writer.write(output);
            }
            VerifiedCache.commit(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
