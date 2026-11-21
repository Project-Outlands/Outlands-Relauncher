package top.outlands.relauncher.bootstrap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipFile;

import top.outlands.relauncher.shared.JavaArgFile;
import top.outlands.relauncher.shared.JvmOptions;
import top.outlands.relauncher.shared.LaunchPlatform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PrepareMainTest {
    private static final String ID = "1.12.2-Outlands-fixture";
    private static final String LOADER_PATH = "manifest/path/outlands.jar";
    private static final String HOST = "https://artifacts.example/";
    private static final byte[] LOADER = {1, 2, 3};
    private static final byte[] CLIENT = {4, 5, 6};
    private static final byte[] LWJGL = {7, 8, 9};
    private static final List<String> JVM = Arrays.asList("-Djava.library.path=${natives_directory}", "-cp", "${classpath}");

    @TempDir
    Path root;

    @Test
    void onlyExactKnownMavenMirrorPathsGetCanonicalSources() throws IOException {
        String central = "org/lwjgl/lwjgl-sdl/3.4.3/lwjgl-sdl-3.4.3.jar";
        String aliyun = "https://maven.aliyun.com/repository/central/";
        String nexus = "https://nexus.jsdu.cn/repository/";
        String foundation = "top/outlands/foundation/1.1.1/foundation-1.1.1.jar";
        String cleanmix = "com/cleanroommc/cleanmix/0.7.4/cleanmix-0.7.4.jar";
        assertEquals(Arrays.asList(URI.create("https://repo.maven.apache.org/maven2/" + central), URI.create(aliyun + central)),
                VerifiedCache.downloadUris(mirrorArtifact(central, aliyun + central)));
        assertEquals(Arrays.asList(URI.create("https://maven.outlands.top/releases/" + foundation), URI.create(nexus + foundation)),
                VerifiedCache.downloadUris(mirrorArtifact(foundation, nexus + foundation)));
        assertOriginalOnly(cleanmix, nexus + cleanmix);
        assertOriginalOnly(foundation, nexus + "other/" + foundation);
        assertOriginalOnly(central, aliyun + "different/" + central);
        assertOriginalOnly(central, aliyun.replace("aliyun.com", "aliyun.com.example") + central);
        assertOriginalOnly(central, aliyun + central + "?download=1");
    }

    @Test
    void timeoutOrBadCanonicalDigestFallsBackAndOnlyVerifiedBytesAreCached() throws IOException {
        String path = "org/lwjgl/lwjgl-sdl/3.4.3/lwjgl-sdl-3.4.3.jar";
        URI mirror = URI.create("https://maven.aliyun.com/repository/central/" + path);
        URI canonical = URI.create("https://repo.maven.apache.org/maven2/" + path);
        InstallerMetadata.Artifact artifact = mirrorArtifact(path, mirror.toString());
        for (boolean timeout : new boolean[] {true, false}) {
            List<URI> requests = new ArrayList<>();
            VerifiedCache cache = new VerifiedCache(Files.createTempDirectory(root, "mirror-"), uri -> {
                requests.add(uri);
                if (canonical.equals(uri)) {
                    if (timeout) throw new java.net.SocketTimeoutException("Read timed out");
                    return new ByteArrayInputStream(new byte[] {0, 0, 0});
                }
                return new ByteArrayInputStream(LWJGL);
            });
            Path cached = cache.obtain(artifact, null);
            assertEquals(Arrays.asList(canonical, mirror), requests);
            assertArrayEquals(LWJGL, Files.readAllBytes(cached));
            assertEquals(cached, cache.obtain(artifact, null));
            assertEquals(2, requests.size());
        }
    }

    @Test
    void badBytesFromBothSourcesAreNotPublished() throws IOException {
        String path = "top/outlands/foundation/1.1.1/foundation-1.1.1.jar";
        InstallerMetadata.Artifact artifact = mirrorArtifact(path, "https://nexus.jsdu.cn/repository/" + path);
        List<URI> requests = new ArrayList<>();
        VerifiedCache cache = new VerifiedCache(root, uri -> {
            requests.add(uri);
            return new ByteArrayInputStream(new byte[] {0, 0, 0});
        });
        IOException error = assertThrows(IOException.class, () -> cache.obtain(artifact, null));
        assertTrue(error.getMessage().contains("maven.outlands.top"));
        assertTrue(error.getMessage().contains("nexus.jsdu.cn"));
        assertEquals(VerifiedCache.downloadUris(artifact), requests);
        try (Stream<Path> paths = Files.walk(root)) {
            assertFalse(paths.anyMatch(Files::isRegularFile));
        }
    }

    private static void assertOriginalOnly(String path, String original) throws IOException {
        assertEquals(Collections.singletonList(URI.create(original)),
                VerifiedCache.downloadUris(mirrorArtifact(path, original)));
    }

    private static InstallerMetadata.Artifact mirrorArtifact(String path, String original) {
        return new InstallerMetadata.Artifact(path, LWJGL.length, "SHA-1", hash("SHA-1", LWJGL),
                URI.create(original), Collections.emptyList());
    }

    @Test
    void brokenLocalArchiveIsDistinguishedFromDependencyFailures() throws IOException {
        Path run = Files.createDirectory(root.resolve("run"));
        Path archive = Files.write(root.resolve("local.jar"), new byte[] {1, 2, 3});
        Path bootstrap = Files.createFile(root.resolve("bootstrap.jar"));
        String[] args = {root.resolve("cache").toString(), run.toString(), archive.toString(),
                bootstrap.toString(), "--install"};
        assertThrows(PrepareMain.InvalidLocalPack.class,
                () -> PrepareMain.prepare(args, uri -> { throw new AssertionError("Invalid archive must not fetch dependencies"); }, null, 25));
        assertFalse(Files.exists(run.resolve("java.args")));
        assertEquals("verify 0 0\n", readProgress(run));
    }

    @Test
    void pinnedInstallerAndArtifactsCanBeReusedOfflineAndRepairSameSizeCorruption() throws IOException {
        Fixture fixture = fixture(true, "hello.dll", version -> {});
        fixture.prepare(true);
        assertEquals(4, fixture.requests); // installer, client, LWJGL, and legacy native archive, not the embedded loader
        fixture.assertOutput(Arrays.asList("--username", "${auth_player_name}", "--demo-false", "a value"));
        assertArrayEquals(new byte[] {11}, Files.readAllBytes(fixture.run.resolve("natives/hello.dll")));
        assertFalse(Files.exists(fixture.run.resolve("natives/META-INF")));
        assertFalse(Files.exists(fixture.run.resolve("natives/ignored.txt")));
        Path lwjgl = fixture.cache.resolve("sha-1/" + hash("SHA-1", LWJGL) + "/manifest/lwjgl.jar");
        assertArrayEquals(LWJGL, Files.readAllBytes(lwjgl));

        fixture.offline = true;
        fixture.prepare(true);
        assertEquals(4, fixture.requests);
        assertArrayEquals(new byte[] {11}, Files.readAllBytes(fixture.run.resolve("natives/hello.dll")));

        Files.write(lwjgl, new byte[] {0, 0, 0});
        fixture.offline = false;
        fixture.prepare(true);
        assertEquals(5, fixture.requests);
        assertArrayEquals(LWJGL, Files.readAllBytes(lwjgl));
    }

    @Test
    void installReportsPreparationProgressAndKeepsNormalLaunchOutput() throws IOException {
        Fixture fixture = fixture(true, "hello.dll", version -> {});
        List<String> game = Arrays.asList("--username", "${auth_player_name}", "--demo-false", "a value");
        fixture.prepare(true, true);
        assertEquals(Arrays.asList("verify 0 0\n", "dependencies 1 4\n", "dependencies 2 4\n", "dependencies 3 4\n"),
                fixture.progressAtDownload);
        assertEquals("save 0 0\n", readProgress(fixture.run));
        fixture.assertOutput(game);
        assertArrayEquals(new byte[] {11}, Files.readAllBytes(fixture.run.resolve("natives/hello.dll")));

        fixture.offline = true;
        fixture.prepare(true, true);
        assertEquals(4, fixture.requests);
        assertEquals("save 0 0\n", readProgress(fixture.run));
        fixture.assertOutput(game);
        assertArrayEquals(new byte[] {11}, Files.readAllBytes(fixture.run.resolve("natives/hello.dll")));
    }

    @Test
    void installProgressWriteFailureAbortsPreparation() throws IOException {
        Fixture fixture = fixture(true, "hello.dll", version -> {});
        Path run = Files.createDirectory(root.resolve("blocked-progress"));
        Files.createDirectory(run.resolve("install-progress"));
        String[] args = {fixture.cache.toString(), run.toString(), fixture.installer.toString(),
                fixture.bootstrap.toString(), "--install"};
        assertThrows(IOException.class, () -> PrepareMain.prepare(args,
                uri -> { throw new AssertionError("Progress write failure must abort preparation"); }, fixture.release, 25));
        assertFalse(Files.exists(run.resolve("launch.properties")));
    }

    private static String readProgress(Path run) throws IOException {
        return new String(Files.readAllBytes(run.resolve("install-progress")), StandardCharsets.UTF_8);
    }

    @Test
    void legacyLocalInstallerUsesEmbeddedLoaderAndSplitsTemplateWithoutResolvingIt() throws IOException {
        Fixture fixture = fixture(false, "hello.dll", version -> {});
        fixture.prepare(false);
        assertEquals(3, fixture.requests);
        assertFalse(Files.exists(fixture.run.resolve("install-progress")));
        fixture.assertOutput(Arrays.asList("--username", "${auth_player_name}", "--label", "quoted value", ""));
    }

    @Test
    void installerFiltersConditionalArgumentsAndNativesForTargetPlatforms() throws IOException {
        Fixture fixture = fixture(true, "hello.dll", version -> {
            JsonObject args = version.getAsJsonObject("arguments");
            JsonObject platformArg = ruleArg("osx-arm64", "--apple-silicon");
            JsonObject os = platformArg.getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonObject("os");
            os.addProperty("arch", "aarch64|arm64");
            os.addProperty("version", "14[.].*");
            args.getAsJsonArray("game").add(platformArg);
            JsonArray libs = version.getAsJsonArray("libraries");
            // Replace host-specific fixture libraries, retaining the embedded loader.
            while (libs.size() > 1) libs.remove(1);
            for (String classifier : Arrays.asList("linux", "linux-arm64", "macos", "macos-arm64", "windows", "windows-arm64")) {
                libs.add(library("org.lwjgl:lwjgl:3.4.3:natives-" + classifier, null,
                        artifact("manifest/" + classifier + ".jar", "lwjgl", LWJGL)));
            }
        });
        try (ZipFile zip = new ZipFile(fixture.installer.toFile())) {
            for (String os : Arrays.asList("Windows 11", "Linux", "Mac OS X")) {
                for (String arch : Arrays.asList("amd64", "aarch64")) {
                    LaunchPlatform platform = LaunchPlatform.detect(os, arch, "14.3");
                    InstallerMetadata metadata = InstallerMetadata.read(zip, platform, 25);
                    boolean arm = "aarch64".equals(arch);
                    String classifier = (platform.mac() ? "macos" : platform.os()) + (arm ? "-arm64" : "");
                    assertEquals(3, metadata.classpath.size());
                    assertEquals("manifest/" + classifier + ".jar", metadata.classpath.get(1).path);
                    assertEquals(platform.mac(), metadata.jvm.contains("-XstartOnFirstThread"));
                    assertEquals(platform.mac() && arm, metadata.game.contains("--apple-silicon"));
                }
            }
            LaunchPlatform oldMac = LaunchPlatform.detect("Mac OS X", "arm64", "13.6");
            assertFalse(InstallerMetadata.read(zip, oldMac, 25).game.contains("--apple-silicon"));
        }
    }

    @Test
    void installerJavaMajorIsAMinimumNotAnExactVersion() throws IOException {
        Fixture fixture = fixture(true, "hello.dll", version ->
                version.getAsJsonObject("javaVersion").addProperty("majorVersion", 26));
        try (ZipFile zip = new ZipFile(fixture.installer.toFile())) {
            assertThrows(IOException.class, () -> InstallerMetadata.read(zip, LaunchPlatform.current(), 25));
            assertEquals(ID, InstallerMetadata.read(zip, LaunchPlatform.current(), 26).id);
            assertEquals(ID, InstallerMetadata.read(zip, LaunchPlatform.current(), 27).id);
        }
    }

    @Test
    void classpathRejectsTheTargetPlatformSeparatorOnly() throws IOException {
        LaunchPlatform windows = LaunchPlatform.detect("Windows 11", "x86_64", "10.0");
        LaunchPlatform linux = LaunchPlatform.detect("Linux", "arm64", "6.12");
        Path semicolon = root.resolve("with;semicolon.jar");
        assertThrows(IOException.class, () -> PrepareMain.classpathEntry(semicolon, windows));
        // Relative path works on either host and does not contain a Windows drive separator.
        assertEquals("with;semicolon.jar", PrepareMain.classpathEntry(semicolon.getFileName(), linux));
        if (File.pathSeparatorChar == ':') {
            assertThrows(IOException.class, () -> PrepareMain.classpathEntry(root.resolve("with:colon.jar"), linux));
        }
    }

    @Test
    void badDigestAndOversizedDownloadNeverPublishCacheOrLaunchFiles() throws IOException {
        Fixture fixture = fixture(true, "hello.dll", version -> {});
        for (byte[] invalid : Arrays.asList(new byte[] {0, 0, 0}, new byte[] {0, 0, 0, 0}, new byte[] {0})) {
            fixture.artifacts.put(URI.create(HOST + "lwjgl"), invalid);
            assertThrows(IOException.class, () -> fixture.prepare(false));
            fixture.assertNoLaunchFiles();
            assertFalse(Files.exists(fixture.cache.resolve("sha-1/" + hash("SHA-1", LWJGL) + "/manifest/lwjgl.jar")));
            try (Stream<Path> paths = Files.walk(fixture.cache)) {
                assertFalse(paths.anyMatch(path -> path.toString().endsWith(".part")));
            }
        }
    }

    @Test
    void badEmbeddedLoaderCannotFallBackToItsManifestUrl() throws IOException {
        Fixture fixture = fixture(true, "hello.dll", version -> libraries(version).get(0).getAsJsonObject()
                .getAsJsonObject("downloads").getAsJsonObject("artifact").addProperty("sha1", hash("SHA-1", CLIENT)));
        assertThrows(IOException.class, () -> fixture.prepare(false, true));
        assertEquals(0, fixture.requests);
        assertEquals("dependencies 0 4\n", readProgress(fixture.run));
        fixture.assertNoLaunchFiles();
    }

    @Test
    void rejectUnsafeMetadataBeforeDownloadingDependencies() throws IOException {
        List<Consumer<JsonObject>> mutants = Arrays.asList(
                v -> v.addProperty("id", "other"),
                v -> v.addProperty("mainClass", "unapproved.Main"),
                v -> v.getAsJsonObject("javaVersion").addProperty("majorVersion", 17),
                v -> v.addProperty("inheritsFrom", "1.12.1"),
                v -> v.getAsJsonObject("arguments").getAsJsonArray("jvm").add("-javaagent:evil.jar"),
                v -> client(v).addProperty("url", "http://example.test/client.jar"),
                v -> client(v).addProperty("url", "https://username:password@example.test/client.jar"),
                v -> client(v).addProperty("path", "../outside.jar"),
                v -> client(v).addProperty("path", "C:/outside.jar"),
                v -> client(v).addProperty("path", "inside\\outside.jar"),
                v -> client(v).addProperty("path", "cache/CON.jar"));
        for (Consumer<JsonObject> mutant : mutants) {
            Fixture fixture = fixture(true, "hello.dll", mutant);
            assertThrows(IOException.class, () -> fixture.prepare(false));
            assertEquals(0, fixture.requests);
            fixture.assertNoLaunchFiles();
        }
    }

    @Test
    void rejectTraversalAndCaseInsensitiveNativeCollisions() throws IOException {
        for (String bad : Arrays.asList("../escaped.dll", "nested/../../escaped.dll", "C:/escaped.dll", "HELLO.DLL")) {
            Fixture fixture = fixture(true, bad, version -> {});
            IOException error = assertThrows(IOException.class, () -> fixture.prepare(false, true));
            assertEquals("dependencies 4 4\n", readProgress(fixture.run));
            assertTrue(error.getMessage().contains("metadata path") || error.getMessage().contains("Duplicate"));
            assertFalse(Files.exists(fixture.run.resolve("escaped.dll")));
            assertFalse(Files.exists(fixture.run.getParent().resolve("escaped.dll")));
            fixture.assertNoLaunchFiles();
        }
    }

    @Test
    void nativePathLinkIsRejectedRatherThanWritingThroughIt() throws IOException {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path inside = Files.createDirectory(root.resolve("inside")).toRealPath();
        try {
            Files.createSymbolicLink(inside.resolve("linked"), outside);
        } catch (IOException | UnsupportedOperationException ex) {
            assumeTrue(false, "Symbolic links unavailable: " + ex.getMessage());
        }
        assertThrows(IOException.class, () -> VerifiedCache.resolveForWrite(inside, "linked/file.dll"));
        assertFalse(Files.exists(outside.resolve("file.dll")));
    }

    @Test
    void java25ActuallyDecodesQuotedUnicodePathsAndArguments() throws Exception {
        String javaHome = "25".equals(System.getProperty("java.specification.version"))
                ? System.getProperty("java.home") : System.getenv("JAVA_HOME");
        assumeTrue(javaHome != null, "Java 25 not configured");
        Path javaExe = java.nio.file.Paths.get(javaHome, "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        assumeTrue(Files.isRegularFile(javaExe), "Java 25 executable unavailable");
        Path versionLog = root.resolve("version.log");
        Process version = new ProcessBuilder(javaExe.toString(), "-version").redirectErrorStream(true)
                .redirectOutput(versionLog.toFile()).start();
        try {
            assertTrue(version.waitFor(10, TimeUnit.SECONDS), "Java version probe timed out");
            String reported = new String(Files.readAllBytes(versionLog), StandardCharsets.US_ASCII);
            assumeTrue(reported.contains("version \"25\"") || reported.contains("version \"25."), "Java 25 not configured");
        } finally {
            if (version.isAlive()) version.destroyForcibly();
        }
        Charset launcher = JavaArgFile.launcherCharset();
        String chinese = "\u4e2d\u6587";
        assumeTrue(launcher.newEncoder().canEncode(chinese), "Host platform code page cannot represent Chinese");
        String pathName = chinese + " spaces # " + multibyteBackslash(launcher);
        Path source = Files.createDirectory(root.resolve(pathName)).resolve("Probe.java");
        Files.write(source, Arrays.asList("class Probe {",
                "  public static void main(String[] args) {",
                "    if (!args[0].equals(System.getProperty(\"probe\")) || args[1].length() != 0) System.exit(2);",
                "    System.out.println(java.util.Base64.getEncoder().encodeToString(args[0].getBytes(java.nio.charset.StandardCharsets.UTF_8)));",
                "  }", "}"), StandardCharsets.US_ASCII);
        String expected = pathName + "\\\"\n\r\t\f\\";
        Path argfile = root.resolve("probe.args");
        try (java.io.OutputStream output = Files.newOutputStream(argfile)) {
            JavaArgFile.write(output, Arrays.asList("-Dprobe=" + expected, source.toString(), expected, ""), launcher);
        }
        Path log = root.resolve("probe.log");
        Process process = new ProcessBuilder(javaExe.toString(), "@" + argfile).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(40, TimeUnit.SECONDS), "Java argfile probe timed out");
            String result = new String(Files.readAllBytes(log), launcher).trim();
            assertEquals(0, process.exitValue(), result);
            assertEquals(java.util.Base64.getEncoder().encodeToString(expected.getBytes(StandardCharsets.UTF_8)), result);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String multibyteBackslash(Charset charset) {
        for (char c = '\u4e00'; c <= '\u9fff'; c++) {
            String value = Character.toString(c);
            byte[] bytes = value.getBytes(charset);
            if (bytes.length > 1 && bytes[bytes.length - 1] == '\\') return value;
        }
        return "";
    }

    private Fixture fixture(boolean modern, String nativeName, Consumer<JsonObject> mutate) throws IOException {
        ByteArrayOutputStream nativeZip = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(nativeZip)) {
            put(zip, "META-INF/MANIFEST.MF", CLIENT);
            put(zip, "ignored.txt", LWJGL);
            put(zip, nativeName, new byte[] {11});
            if (!"hello.dll".equals(nativeName)) put(zip, "hello.dll", new byte[] {12});
        }
        byte[] nativeBytes = nativeZip.toByteArray();
        JsonObject version = new JsonObject();
        version.addProperty("id", ID);
        version.addProperty("mainClass", InstallerMetadata.FOUNDATION);
        JsonObject javaVersion = new JsonObject();
        javaVersion.addProperty("majorVersion", 25);
        version.add("javaVersion", javaVersion);
        version.addProperty("minecraftArguments", "--username ${auth_player_name} --label \"quoted value\" ''");
        if (modern) {
            JsonObject args = new JsonObject();
            JsonArray jvm = strings(JVM.toArray(new String[0]));
            jvm.add(ruleArg("osx", "-XstartOnFirstThread"));
            args.add("jvm", jvm);
            JsonArray game = strings("--username", "${auth_player_name}");
            game.add(ruleArg("windows-x86", "--wrong-arch"));
            JsonObject conditional = ruleArg(LaunchPlatform.current().os(), "unused");
            conditional.add("value", strings("--demo-false", "a value"));
            game.add(conditional);
            args.add("game", game);
            version.add("arguments", args);
        }
        JsonObject downloads = new JsonObject();
        downloads.add("client", artifact(null, "client", CLIENT));
        version.add("downloads", downloads);
        JsonArray libraries = new JsonArray();
        libraries.add(library(InstallerMetadata.LOADER_GROUP + "fixture", null, artifact(LOADER_PATH, "loader", LOADER)));
        LaunchPlatform platform = LaunchPlatform.current();
        String os = platform.os();
        String nativeOs = platform.mac() ? "macos" : os;
        String classifier = "natives-" + nativeOs + ("arm64".equals(platform.arch()) ? "-arm64" : "");
        libraries.add(library("org.lwjgl:lwjgl:3.4.3:" + classifier, os, artifact("manifest/lwjgl.jar", "lwjgl", LWJGL)));
        String wrongArch = "arm64".equals(platform.arch()) ? "" : "-arm64";
        libraries.add(library("org.lwjgl:lwjgl:3.4.3:natives-" + nativeOs + wrongArch, os, null));
        libraries.add(library("org.lwjgl:lwjgl:3.4.3:natives-" + nativeOs + "-x86", os, null));
        libraries.add(library("test:other:1", platform.windows() ? "osx" : "windows", null));
        JsonObject nativeLib = library("test:legacy-natives:1", null, null);
        JsonObject natives = new JsonObject();
        natives.addProperty(os, "natives-" + nativeOs + "-${arch}");
        nativeLib.add("natives", natives);
        JsonObject classifiers = new JsonObject();
        classifiers.add("natives-" + nativeOs + "-64", artifact("manifest/legacy-natives.jar", "natives", nativeBytes));
        nativeLib.getAsJsonObject("downloads").add("classifiers", classifiers);
        JsonObject extract = new JsonObject();
        extract.add("exclude", strings("ignored.txt"));
        nativeLib.add("extract", extract);
        libraries.add(nativeLib);
        version.add("libraries", libraries);
        mutate.accept(version);
        JsonObject profile = new JsonObject();
        profile.addProperty("path", InstallerMetadata.LOADER_GROUP + "fixture");
        profile.addProperty("version", ID);
        profile.addProperty("minecraft", "1.12.2");
        Path installer = Files.createTempFile(root, "local installer-", ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(installer))) {
            put(zip, "version.json", version.toString().getBytes(StandardCharsets.UTF_8));
            put(zip, "install_profile.json", profile.toString().getBytes(StandardCharsets.UTF_8));
            put(zip, "maven/" + LOADER_PATH, LOADER);
        }
        return new Fixture(installer, nativeBytes);
    }

    private static JsonArray libraries(JsonObject version) {
        return version.getAsJsonArray("libraries");
    }

    private static JsonObject client(JsonObject version) {
        return version.getAsJsonObject("downloads").getAsJsonObject("client");
    }

    private static JsonObject artifact(String path, String endpoint, byte[] bytes) {
        JsonObject value = new JsonObject();
        if (path != null) value.addProperty("path", path);
        value.addProperty("url", HOST + endpoint);
        value.addProperty("sha1", hash("SHA-1", bytes));
        value.addProperty("size", bytes.length);
        return value;
    }

    private static JsonObject library(String name, String os, JsonObject artifact) {
        JsonObject value = new JsonObject();
        value.addProperty("name", name);
        if (os != null) value.add("rules", rules(os));
        JsonObject downloads = new JsonObject();
        if (artifact != null) downloads.add("artifact", artifact);
        value.add("downloads", downloads);
        return value;
    }

    private static JsonObject ruleArg(String os, String arg) {
        JsonObject value = new JsonObject();
        value.add("rules", rules(os));
        value.addProperty("value", arg);
        return value;
    }

    private static JsonArray rules(String os) {
        JsonObject platform = new JsonObject();
        platform.addProperty("name", os);
        JsonObject rule = new JsonObject();
        rule.addProperty("action", "allow");
        rule.add("os", platform);
        JsonArray rules = new JsonArray();
        rules.add(rule);
        return rules;
    }

    private static JsonArray strings(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        return array;
    }

    private static void put(ZipOutputStream zip, String entry, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        zip.write(data);
        zip.closeEntry();
    }

    private static String hash(String alg, byte[] bytes) {
        return VerifiedCache.hex(VerifiedCache.digest(alg).digest(bytes));
    }

    private final class Fixture {
        final Path installer;
        final Path cache;
        final Path bootstrap;
        final InstallerMetadata.Artifact release;
        final Map<URI, byte[]> artifacts = new HashMap<>();
        final List<String> progressAtDownload = new ArrayList<>();
        Path run;
        int requests;
        boolean offline;

        Fixture(Path installer, byte[] nativeBytes) throws IOException {
            this.installer = installer;
            cache = Files.createTempDirectory(root, "cache space-").toRealPath();
            bootstrap = Files.createTempFile(root, "bootstrap space-", ".jar").toRealPath();
            byte[] bytes = Files.readAllBytes(installer);
            release = new InstallerMetadata.Artifact("release/installer.jar", bytes.length, "SHA-256",
                    hash("SHA-256", bytes), URI.create(HOST + "installer"), Collections.emptyList());
            artifacts.put(release.uri, bytes);
            artifacts.put(URI.create(HOST + "lwjgl"), LWJGL);
            artifacts.put(URI.create(HOST + "client"), CLIENT);
            artifacts.put(URI.create(HOST + "natives"), nativeBytes);
        }

        void prepare(boolean pinned) throws IOException {
            prepare(pinned, false);
        }

        void prepare(boolean pinned, boolean install) throws IOException {
            run = Files.createTempDirectory(root, "run space-").toRealPath();
            Files.write(run.resolve(JvmOptions.FILE_NAME), Arrays.asList("-Xms512m", "-Xmx4G", "-Dcustom=two words"),
                    StandardCharsets.UTF_8);
            List<String> args = new ArrayList<>(Arrays.asList(cache.toString(), run.toString(),
                    pinned ? "" : installer.toString(), bootstrap.toString()));
            if (install) args.add("--install");
            PrepareMain.prepare(args.toArray(new String[0]), uri -> {
                if (offline) throw new AssertionError("Unexpected download while offline: " + uri);
                if (install) progressAtDownload.add(readProgress(run));
                requests++;
                byte[] bytes = artifacts.get(uri);
                if (bytes == null) throw new AssertionError("Unexpected download: " + uri);
                return new ByteArrayInputStream(bytes);
            }, release, 25);
        }

        void assertOutput(List<String> tokens) throws IOException {
            Properties properties = new Properties();
            try (InputStream data = Files.newInputStream(run.resolve("launch.properties"))) {
                properties.load(data);
            }
            assertEquals(ID, properties.getProperty("version"));
            assertEquals(Integer.toString(tokens.size()), properties.getProperty("game.count"));
            assertEquals(tokens.size() + 2, properties.size());
            for (int i = 0; i < tokens.size(); i++) assertEquals(tokens.get(i), properties.getProperty("game." + i));
            List<String> args = Files.readAllLines(run.resolve("java.args"), JavaArgFile.launcherCharset());
            if (LaunchPlatform.current().mac()) assertEquals("\"-XstartOnFirstThread\"", args.remove(0));
            assertEquals("\"-Xms512m\"", args.get(0));
            assertEquals("\"-Xmx4G\"", args.get(1));
            assertEquals("\"-Dcustom=two words\"", args.get(2));
            assertEquals("\"-Doutlands.relauncher.child=true\"", args.get(3));
            assertTrue(args.get(4).startsWith("\"-Djava.library.path="));
            assertTrue(args.get(4).endsWith("natives\""));
            assertEquals("\"-cp\"", args.get(5));
            String[] paths = args.get(6).substring(1, args.get(6).length() - 1).replace("\\\\", "\\").split(Pattern.quote(File.pathSeparator));
            assertEquals(4, paths.length);
            assertTrue(paths[0].endsWith(LOADER_PATH.replace('/', java.io.File.separatorChar)));
            assertTrue(paths[1].endsWith("lwjgl.jar"));
            assertTrue(paths[2].endsWith("client-1.12.2.jar"));
            assertEquals(bootstrap.toString(), paths[3]);
            assertEquals("\"top.outlands.relauncher.bootstrap.ChildMain\"", args.get(7));
        }

        void assertNoLaunchFiles() {
            assertFalse(Files.exists(run.resolve("java.args")));
            assertFalse(Files.exists(run.resolve("launch.properties")));
        }
    }
}
