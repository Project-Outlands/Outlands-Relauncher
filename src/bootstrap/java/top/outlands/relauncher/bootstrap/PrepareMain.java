package top.outlands.relauncher.bootstrap;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
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
            require(args.length == 6,
                    "Expected cacheDir, runDir, localInstaller (or empty), bootstrapJar, Xms (or empty), Xmx (or empty)");
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
        checkHeap(args[4], "-Xms");
        checkHeap(args[5], "-Xmx");
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
        List<String> jvm = new ArrayList<>();
        if (!args[4].isEmpty()) jvm.add(args[4]);
        if (!args[5].isEmpty()) jvm.add(args[5]);
        jvm.add("-Doutlands.relauncher.child=true");
        jvm.add("-Djava.library.path=" + natives);
        jvm.add("-cp");
        jvm.add(String.join(";", classpath));
        jvm.add(CHILD_MAIN);
        writeAtomically(argFile, output -> writeArgFile(output, jvm, launcherCharset()));

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

    private static void checkHeap(String argument, String option) throws IOException {
        require(argument.isEmpty() || argument.matches(option + "[0-9]+[kKmMgGtT]?"), "Invalid " + option + " argument");
    }

    private static String classpathEntry(Path path) throws IOException {
        String value = path.toString();
        require(value.indexOf(';') < 0, "Windows classpath cannot use paths containing semicolons");
        return value;
    }

    static Charset launcherCharset() {
        // The Windows launcher consumes platform-code-page bytes, not Java 25's UTF-8 default charset.
        return Charset.forName(System.getProperty("sun.jnu.encoding", StandardCharsets.UTF_8.name()));
    }

    static void writeArgFile(OutputStream output, List<String> arguments, Charset charset) throws IOException {
        for (String argument : arguments) {
            ByteBuffer encoded;
            try {
                encoded = charset.newEncoder().encode(CharBuffer.wrap(argument));
            } catch (CharacterCodingException ex) {
                throw new IOException("Launch argument cannot be represented in the platform encoding " + charset, ex);
            }
            output.write('"');
            // The launcher's tokenizer also treats a backslash byte inside a multibyte character as an escape.
            while (encoded.hasRemaining()) {
                int ch = encoded.get() & 0xff;
                switch (ch) {
                    case '\\': case '"': output.write('\\'); output.write(ch); break;
                    case '\n': output.write('\\'); output.write('n'); break;
                    case '\r': output.write('\\'); output.write('r'); break;
                    case '\t': output.write('\\'); output.write('t'); break;
                    case '\f': output.write('\\'); output.write('f'); break;
                    case 0: throw new IOException("NUL in launch argument");
                    default: output.write(ch);
                }
            }
            output.write('"');
            output.write('\n');
        }
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
