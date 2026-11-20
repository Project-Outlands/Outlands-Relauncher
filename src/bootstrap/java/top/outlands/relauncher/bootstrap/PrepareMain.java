package top.outlands.relauncher.bootstrap;

import java.io.IOException;
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

import static top.outlands.relauncher.bootstrap.InstallerMetadata.require;

/** Isolated preparation process; never receives resolved game arguments or login details. */
public final class PrepareMain {
    private static final String RELEASE_INSTALLER = "top/outlands/outlands/2026.11.16/outlands-2026.11.16-installer.jar";
    private static final InstallerMetadata.Artifact RELEASE = new InstallerMetadata.Artifact(RELEASE_INSTALLER,
            6_619_705L, "SHA-256", "6b9abc14d7d7a212dbf95543e3769ef7649b1a42dad65fd1773f81d99c46c730",
            URI.create("https://maven.outlands.top/releases/" + RELEASE_INSTALLER), Collections.emptyList());
    private static final String CHILD_MAIN = "top.outlands.relauncher.bootstrap.ChildMain";

    private PrepareMain() {}

    public static void main(String[] args) {
        try {
            require(args.length == 4,
                    "Expected cacheDir, runDir, localInstaller (or empty), bootstrapJar");
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            require(System.getProperty("os.name", "").startsWith("Windows")
                    && ("amd64".equals(arch) || "x86_64".equals(arch))
                    && "25".equals(System.getProperty("java.specification.version")),
                    "Preparation requires Windows x64 and Java 25");
            ParentPipe.watch(System.in);
            prepare(args, VerifiedCache::download, RELEASE);
        } catch (IOException | RuntimeException ex) {
            System.err.println("Outlands preparation failed: " + ex.getMessage());
            System.exit(1);
        }
    }

    static void prepare(String[] args, VerifiedCache.Downloader downloader, InstallerMetadata.Artifact release)
            throws IOException {
        Path runDir = Paths.get(args[1]).toRealPath();
        require(Files.isDirectory(runDir), "Run directory must already exist");
        Path argFile = runDir.resolve("java.args");
        Path propertiesFile = runDir.resolve("launch.properties");
        require(!Files.exists(argFile, LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(propertiesFile, LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(runDir.resolve("natives"), LinkOption.NOFOLLOW_LINKS), "Run directory was already used");
        Path bootstrapJar = absoluteFile(args[3], "Bootstrap JAR");
        List<String> jvm = readJvmOptions(runDir);
        VerifiedCache cache = new VerifiedCache(Paths.get(args[0]), downloader);
        Path installer = args[2].isEmpty() ? cache.obtain(release, null) : absoluteFile(args[2], "Local installer");
        List<String> classpath = new ArrayList<>();
        Path natives = runDir.resolve("natives");
        InstallerMetadata metadata;
        try (ZipFile zip = new ZipFile(installer.toFile())) {
            metadata = InstallerMetadata.read(zip, System.getProperty("os.version", ""));
            System.out.println("Preparing Outlands " + metadata.id);
            for (InstallerMetadata.Artifact artifact : metadata.classpath) {
                VerifiedCache.Source embedded = artifact.uri == null
                        ? () -> zip.getInputStream(zip.getEntry("maven/" + artifact.path)) : null;
                classpath.add(classpathEntry(cache.obtain(artifact, embedded)));
            }
            NativeExtractor extractor = new NativeExtractor(natives);
            for (InstallerMetadata.Artifact artifact : metadata.natives) {
                extractor.extract(cache.obtain(artifact, null), artifact.excludes);
            }
        }
        classpath.add(classpathEntry(bootstrapJar));
        jvm.add("-Doutlands.relauncher.child=true");
        jvm.add("-Djava.library.path=" + natives);
        jvm.add("-cp");
        jvm.add(String.join(";", classpath));
        jvm.add(CHILD_MAIN);
        writeAtomically(argFile, output -> JavaArgFile.write(output, jvm, JavaArgFile.launcherCharset()));

        Properties properties = new Properties();
        properties.setProperty("version", metadata.id);
        properties.setProperty("game.count", Integer.toString(metadata.game.size()));
        for (int i = 0; i < metadata.game.size(); i++) {
            properties.setProperty("game." + i, metadata.game.get(i));
        }
        writeAtomically(propertiesFile, output -> properties.store(output, null));
        System.out.println("Outlands preparation complete");
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

    private static String classpathEntry(Path path) throws IOException {
        String value = path.toString();
        require(value.indexOf(';') < 0, "Windows classpath cannot use paths containing semicolons");
        return value;
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
