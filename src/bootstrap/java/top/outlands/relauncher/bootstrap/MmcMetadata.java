package top.outlands.relauncher.bootstrap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static top.outlands.relauncher.bootstrap.InstallerMetadata.*;

/** Reads Outlands distribution packs, not arbitrary MultiMC or Prism instances. */
final class MmcMetadata {
    private static final String MANIFEST = "mmc-pack.json";
    private static final String MC = "net.minecraft";
    private static final String LWJGL = "org.lwjgl3";
    private static final String FORGE = "net.minecraftforge";
    private static final Set<String> COMPONENTS = new HashSet<>(Arrays.asList(MC, LWJGL, FORGE));
    private static final String TWEAKER = "net.minecraftforge.fml.common.launcher.FMLTweaker";

    private MmcMetadata() {}

    static InstallerMetadata read(ZipFile zip, String osVersion) throws IOException {
        String root = root(zip);
        JsonObject pack = readJson(zip, root + MANIFEST);
        fields(pack, "formatVersion", "components");
        format(pack);
        Map<String, String> versions = new LinkedHashMap<>();
        for (JsonElement entry : array(pack.get("components"))) {
            JsonObject component = object(entry);
            fields(component, "uid", "version", "cachedName", "cachedVersion", "cachedVolatile",
                    "cachedRequires", "dependencyOnly", "important");
            String uid = text(component, "uid");
            String version = text(component, "version");
            require(COMPONENTS.contains(uid) && versions.putIfAbsent(uid, version) == null,
                    "Unsupported or duplicate MMC component: " + uid);
        }
        require(versions.keySet().equals(COMPONENTS) && MINECRAFT.equals(versions.get(MC))
                && versions.get(LWJGL).matches("3\\.[0-9]+\\.[0-9]+")
                && versions.get(FORGE).matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"),
                "MMC pack must declare Minecraft 1.12.2, LWJGL 3, and Outlands");

        JsonObject minecraft = patch(zip, root, MC, versions);
        JsonObject lwjgl = patch(zip, root, LWJGL, versions);
        JsonObject forge = patch(zip, root, FORGE, versions);
        fields(minecraft, "formatVersion", "uid", "version", "name", "releaseTime", "type", "requires",
                "libraries", "assetIndex", "compatibleJavaMajors", "downloads", "mainClass", "mainJar",
                "minecraftArguments");
        fields(lwjgl, "formatVersion", "uid", "version", "name", "releaseTime", "type", "requires",
                "libraries", "volatile");
        fields(forge, "formatVersion", "uid", "version", "name", "releaseTime", "requires", "libraries",
                "mainClass", "+tweakers", "+jvmArgs", "+traits");
        require("net.minecraft.client.main.Main".equals(text(minecraft, "mainClass"))
                && FOUNDATION.equals(text(forge, "mainClass")), "Unsupported MMC main class");
        if (minecraft.has("assetIndex")) {
            require("1.12".equals(text(object(minecraft.get("assetIndex")), "id")), "Unsupported MMC asset index");
        }
        if (minecraft.has("compatibleJavaMajors")) {
            require(array(minecraft.get("compatibleJavaMajors")).contains(new JsonPrimitive(25)),
                    "MMC pack does not support Java 25");
        }
        if (forge.has("+traits")) {
            // Present in the release pack; this macOS-only launch option has no effect on Windows.
            require(Collections.singletonList("FirstThreadOnMacOS").equals(strings(forge.get("+traits"))), "Unsupported MMC traits");
        }
        List<String> game = legacyArguments(text(minecraft, "minecraftArguments"));
        require(!game.isEmpty(), "MMC pack has no game argument template");
        require(Collections.singletonList(TWEAKER).equals(strings(forge.get("+tweakers"))), "Unsupported MMC tweakers");
        game.add("--tweakClass");
        game.add(TWEAKER);
        List<String> jvm = forge.has("+jvmArgs") ? strings(forge.get("+jvmArgs")) : Collections.emptyList();
        require(jvm.isEmpty() || Collections.singletonList("-Dfile.encoding=UTF-8").equals(jvm), "Unsupported MMC JVM arguments");
        String version = versions.get(FORGE);
        InstallerMetadata metadata = new InstallerMetadata("1.12.2-Outlands-" + version, game, jvm);

        // The Outlands patch repeats Minecraft libraries. Overlay by coordinate, keeping the native
        // and Java forms of text2speech separate, and retain deterministic classpath order.
        Map<String, JsonObject> libraries = new LinkedHashMap<>();
        String loader = LOADER_GROUP + version;
        collect(libraries, minecraft, null);
        collect(libraries, lwjgl, null);
        collect(libraries, forge, loader);
        boolean foundLoader = false;
        for (JsonObject library : libraries.values()) {
            if (!selected(library, osVersion)) continue;
            String name = text(library, "name");
            if (loader.equals(name)) foundLoader = true;
            if (library.has("MMC-hint")) {
                metadata.classpath.add(localLoader(zip, root, version, loader));
            } else {
                metadata.addLibrary(zip, library, name, null);
            }
        }
        require(foundLoader, "MMC pack has no Outlands loader for Windows x64");

        JsonObject mainJar = object(minecraft.get("mainJar"));
        fields(mainJar, "name", "downloads");
        require("com.mojang:minecraft:1.12.2:client".equals(text(mainJar, "name")), "Unsupported MMC client JAR");
        JsonObject downloads = object(mainJar.get("downloads"));
        fields(downloads, "artifact");
        metadata.classpath.add(Artifact.read(object(downloads.get("artifact")),
                "net/minecraft/client/1.12.2/client-1.12.2.jar", null, Collections.emptyList()));
        return metadata;
    }

    private static void collect(Map<String, JsonObject> result, JsonObject patch, String loader)
            throws IOException {
        Map<String, JsonObject> libraries = new LinkedHashMap<>();
        for (JsonElement entry : array(patch.get("libraries"))) {
            JsonObject library = object(entry);
            fields(library, "name", "downloads", "natives", "rules", "extract", "MMC-hint");
            String name = text(library, "name");
            mavenPath(name, null);
            boolean natives = library.has("natives");
            if (name.startsWith(LOADER_GROUP)) {
                require(name.equals(loader) && !natives, "Unexpected MMC Outlands loader");
            }
            if (library.has("MMC-hint")) {
                require(name.equals(loader) && "local".equals(text(library, "MMC-hint"))
                        && library.size() == 2, "Unsupported MMC local library declaration");
            } else {
                fields(object(library.get("downloads")), "artifact", "classifiers");
            }
            if (library.has("extract")) {
                require(natives, "MMC extraction requires a native library");
                fields(object(library.get("extract")), "exclude");
            }
            require(libraries.putIfAbsent(name + (natives ? "|natives" : ""), library) == null,
                    "Duplicate MMC library in component: " + name);
        }
        result.putAll(libraries);
    }

    private static Artifact localLoader(ZipFile zip, String root, String version, String coordinate) throws IOException {
        String path = root + "libraries/outlands-" + version + ".jar";
        VerifiedCache.validatePath(path);
        ZipEntry entry = zip.getEntry(path);
        require(entry != null && !entry.isDirectory() && entry.getSize() > 0 && entry.getSize() <= MAX_ARTIFACT_BYTES,
                "Missing or oversized MMC local Outlands loader");
        MessageDigest digest = VerifiedCache.digest("SHA-256");
        try (InputStream in = zip.getInputStream(entry)) {
            require(VerifiedCache.copy(in, null, entry.getSize(), digest, System.nanoTime()) == entry.getSize(),
                    "Wrong size for MMC local Outlands loader");
        }
        return new Artifact(mavenPath(coordinate, null), entry.getSize(), "SHA-256", VerifiedCache.hex(digest.digest()),
                null, Collections.emptyList(), path);
    }

    private static JsonObject patch(ZipFile zip, String root, String uid, Map<String, String> versions)
            throws IOException {
        JsonObject patch = readJson(zip, root + "patches/" + uid + ".json");
        format(patch);
        require(uid.equals(text(patch, "uid")) && versions.get(uid).equals(text(patch, "version")),
                "MMC patch does not match component: " + uid);
        if (patch.has("requires")) {
            for (JsonElement entry : array(patch.get("requires"))) {
                JsonObject dependency = object(entry);
                fields(dependency, "uid", "equals", "suggests");
                String target = text(dependency, "uid");
                require(COMPONENTS.contains(target) && !uid.equals(target)
                        && (dependency.has("equals") != dependency.has("suggests")),
                        "Unsupported MMC component dependency: " + target);
                if (dependency.has("equals")) {
                    require(versions.get(target).equals(text(dependency, "equals")),
                            "Unsatisfied MMC component dependency: " + target);
                } else {
                    text(dependency, "suggests"); // Advisory only; the manifest pins the selected version.
                }
            }
        }
        return patch;
    }

    private static String root(ZipFile zip) throws IOException {
        String root = null;
        for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
            String name = entries.nextElement().getName();
            if (!name.equals(MANIFEST) && !name.endsWith("/" + MANIFEST)) continue;
            require(root == null, "Multiple MMC pack manifests");
            root = name.substring(0, name.length() - MANIFEST.length());
            require(root.isEmpty() || root.indexOf('/') == root.length() - 1, "Unsupported MMC pack root");
            VerifiedCache.validatePath(name);
        }
        require(root != null, "Missing MMC pack manifest");
        Set<String> supportedPatches = new HashSet<>(Arrays.asList(root + "patches/" + MC + ".json", root + "patches/" + LWJGL + ".json",
                root + "patches/" + FORGE + ".json"));
        Set<String> seen = new HashSet<>();
        for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory()) continue;
            if (name.startsWith(root + "patches/") && name.endsWith(".json")) {
                require(supportedPatches.contains(name), "Unsupported MMC patch: " + name);
            } else if (!name.startsWith(root + "libraries/outlands-")) {
                continue; // Instance settings, icons, saves and mods are not launcher metadata.
            }
            require(seen.add(name), "Duplicate MMC archive entry: " + name);
        }
        return root;
    }

    private static List<String> strings(JsonElement value) throws IOException {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array(value)) values.add(string(element));
        return values;
    }

    private static void format(JsonObject value) throws IOException {
        require(integer(value, "formatVersion") == 1, "Unsupported MMC metadata format version");
    }

    private static void fields(JsonObject value, String... supported) throws IOException {
        List<String> fields = Arrays.asList(supported);
        for (String key : value.keySet()) require(fields.contains(key), "Unsupported MMC metadata field: " + key);
    }
}
