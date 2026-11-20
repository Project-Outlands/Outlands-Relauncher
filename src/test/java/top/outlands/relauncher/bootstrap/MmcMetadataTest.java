package top.outlands.relauncher.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class MmcMetadataTest {
    private static final String OUTLANDS = "2026.11.16";
    private static final String LOADER = "top/outlands/outlands/2026.11.16/outlands-2026.11.16.jar";
    private static final String FORGE_PATCH = "patches/net.minecraftforge.json";
    private static final String MC_PATCH = "patches/net.minecraft.json";
    private static final String ARTIFACT = "{\"size\":3,\"sha1\":\"0000000000000000000000000000000000000000\",\"url\":\"https://example.org/test.jar\"}";

    @TempDir Path directory;

    @Test
    void resolvesReleaseLayoutAndReusesWindowsLibraryRules() throws IOException {
        InstallerMetadata metadata = read("", fixture(false));
        assertEquals("1.12.2-Outlands-" + OUTLANDS, metadata.id);
        assertEquals(Arrays.asList("--username", "${auth_player_name}", "--tweakClass",
                "net.minecraftforge.fml.common.launcher.FMLTweaker"), metadata.game);
        assertEquals(Collections.singletonList("-Dfile.encoding=UTF-8"), metadata.jvm);
        assertEquals(Arrays.asList("org/example/voice/1/voice-1.jar", "org/lwjgl/lwjgl/3.4.3/lwjgl-3.4.3-natives-windows.jar",
                        LOADER, "net/minecraft/client/1.12.2/client-1.12.2.jar"),
                metadata.classpath.stream().map(artifact -> artifact.path).collect(Collectors.toList()));
        assertEquals("https://example.org/test.jar", metadata.classpath.get(2).uri.toString());
        assertNull(metadata.classpath.get(2).embeddedPath);
        assertEquals(1, metadata.natives.size());
        assertEquals("org/example/voice/1/voice-1-natives-windows-64.jar", metadata.natives.get(0).path);
        assertEquals(Collections.singletonList("META-INF/"), metadata.natives.get(0).excludes);
    }

    @Test
    void hashesEmbeddedLoaderFromSingleSafeWrapper() throws IOException {
        Map<String, String> files = fixture(true);
        files.put("libraries/outlands-" + OUTLANDS + ".jar", "abc");
        files.put(".minecraft/mods/not-imported.jar", "not used");
        files.put("instance.cfg", "JavaPath=ignored");
        InstallerMetadata metadata = read("Outlands-Instance/", files);
        InstallerMetadata.Artifact loader = metadata.classpath.get(2);
        assertEquals(LOADER, loader.path);
        assertEquals("Outlands-Instance/libraries/outlands-" + OUTLANDS + ".jar", loader.embeddedPath);
        assertNull(loader.uri);
        assertEquals(3, loader.size);
        assertEquals("SHA-256", loader.algorithm);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", loader.digest);
    }

    @Test
    void rejectsMissingLoaderAndUnsafeOrAmbiguousArchiveRoots() {
        assertThrows(IOException.class, () -> read("", fixture(true)));
        assertThrows(IOException.class, () -> read("../", fixture(false)));
        assertThrows(IOException.class, () -> read("nested/wrapper/", fixture(false)));
        Map<String, String> ambiguous = fixture(false);
        ambiguous.put("another/mmc-pack.json", "{}");
        assertThrows(IOException.class, () -> read("", ambiguous));
    }

    @Test
    void rejectsMismatchedComponentsAndUnappliedPatches() {
        Map<String, String> mismatched = fixture(false);
        mismatched.compute(FORGE_PATCH, (name, json) -> json.replace("\"version\":\"2026.11.16\"", "\"version\":\"wrong\""));
        assertThrows(IOException.class, () -> read("", mismatched));
        Map<String, String> extra = fixture(false);
        extra.put("patches/net.fabricmc.fabric-loader.json", "{}");
        assertThrows(IOException.class, () -> read("", extra));
    }

    @Test
    void rejectsUnsupportedLaunchShapeAndUntrustedJvmArguments() {
        Map<String, String> unsupported = fixture(false);
        unsupported.compute(MC_PATCH, (name, json) -> json.replace("\"assetIndex\"", "\"+jarMods\""));
        assertThrows(IOException.class, () -> read("", unsupported));
        Map<String, String> jvm = fixture(false);
        jvm.compute(FORGE_PATCH, (name, json) -> json.replace("-Dfile.encoding=UTF-8", "-javaagent:unknown.jar"));
        assertThrows(IOException.class, () -> read("", jvm));
        Map<String, String> loader = fixture(false);
        loader.compute(FORGE_PATCH, (name, json) -> json.replace("top.outlands:outlands:2026.11.16", "top.outlands:outlands:wrong"));
        assertThrows(IOException.class, () -> read("", loader));
    }

    private InstallerMetadata read(String prefix, Map<String, String> files) throws IOException {
        Path path = Files.createTempFile(directory, "instance", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(prefix + file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        try (ZipFile zip = new ZipFile(path.toFile())) {
            return MmcMetadata.read(zip, "10.0");
        }
    }

    private static Map<String, String> fixture(boolean local) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("mmc-pack.json", "{\"formatVersion\":1,\"components\":[{\"uid\":\"org.lwjgl3\",\"version\":\"3.4.3\"},"
                + "  {\"uid\":\"net.minecraft\",\"version\":\"1.12.2\"},{\"uid\":\"net.minecraftforge\",\"version\":\"2026.11.16\"}]}");
        files.put(MC_PATCH, String.format("{\"formatVersion\":1,\"uid\":\"net.minecraft\",\"version\":\"1.12.2\","
                + "  \"mainClass\":\"net.minecraft.client.main.Main\",\"compatibleJavaMajors\":[25,26],\"assetIndex\":{\"id\":\"1.12\"},"
                + "  \"minecraftArguments\":\"--username ${auth_player_name}\","
                + "  \"mainJar\":{\"name\":\"com.mojang:minecraft:1.12.2:client\",\"downloads\":{\"artifact\":%s}},"
                + "  \"libraries\":[{\"name\":\"org.example:voice:1\",\"downloads\":{\"artifact\":%s}},"
                + "    {\"name\":\"org.example:voice:1\",\"natives\":{\"windows\":\"natives-windows-${arch}\"},"
                + "     \"extract\":{\"exclude\":[\"META-INF/\"]},\"downloads\":{\"classifiers\":{\"natives-windows-64\":%s}}}]}", ARTIFACT, ARTIFACT, ARTIFACT));
        files.put("patches/org.lwjgl3.json", String.format("{\"formatVersion\":1,\"uid\":\"org.lwjgl3\",\"version\":\"3.4.3\",\"libraries\":["
                + "  {\"name\":\"org.lwjgl:lwjgl:3.4.3:natives-windows\",\"rules\":[{\"action\":\"allow\",\"os\":{\"name\":\"windows\"}}],"
                + "   \"downloads\":{\"artifact\":%s}},"
                + "  {\"name\":\"org.lwjgl:lwjgl:3.4.3:natives-windows-x86\",\"rules\":[{\"action\":\"allow\",\"os\":{\"name\":\"windows\"}}],"
                + "   \"downloads\":{\"artifact\":%s}},"
                + "  {\"name\":\"org.lwjgl:lwjgl:3.4.3:natives-linux\",\"rules\":[{\"action\":\"allow\",\"os\":{\"name\":\"linux\"}}],"
                + "   \"downloads\":{\"artifact\":%s}}]}", ARTIFACT, ARTIFACT, ARTIFACT));
        String source = local ? "\"MMC-hint\":\"local\"" : "\"downloads\":{\"artifact\":" + ARTIFACT + "}";
        files.put(FORGE_PATCH, String.format("{\"formatVersion\":1,\"uid\":\"net.minecraftforge\",\"version\":\"2026.11.16\","
                + "  \"mainClass\":\"top.outlands.foundation.boot.Foundation\",\"+traits\":[\"FirstThreadOnMacOS\"],"
                + "  \"requires\":[{\"uid\":\"net.minecraft\",\"equals\":\"1.12.2\"}],"
                + "  \"+tweakers\":[\"net.minecraftforge.fml.common.launcher.FMLTweaker\"],"
                + "  \"+jvmArgs\":[\"-Dfile.encoding=UTF-8\"],\"libraries\":["
                + "    {\"name\":\"org.example:voice:1\",\"downloads\":{\"artifact\":%s}},"
                + "    {\"name\":\"top.outlands:outlands:2026.11.16\",%s}]}", ARTIFACT, source));
        return files;
    }
}
