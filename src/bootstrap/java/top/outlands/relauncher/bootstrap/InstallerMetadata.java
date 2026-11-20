package top.outlands.relauncher.bootstrap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.PatternSyntaxException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** The subset of the installer format supported by the Windows x64 bootstrap. */
final class InstallerMetadata {
    static final String FOUNDATION = "top.outlands.foundation.boot.Foundation";
    static final String LOADER_GROUP = "top.outlands:outlands:";
    static final String MINECRAFT = "1.12.2";
    static final long MAX_ARTIFACT_BYTES = 512L * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final List<String> WINDOWS_JVM = Arrays.asList(
            "-Djava.library.path=${natives_directory}", "-cp", "${classpath}");

    final String id;
    final List<String> game;
    final List<String> jvm;
    final List<Artifact> classpath = new ArrayList<>();
    final List<Artifact> natives = new ArrayList<>();

    InstallerMetadata(String id, List<String> game, List<String> jvm) {
        this.id = id;
        this.game = Collections.unmodifiableList(new ArrayList<>(game));
        this.jvm = Collections.unmodifiableList(new ArrayList<>(jvm));
    }

    static InstallerMetadata read(ZipFile installer, String osVersion) throws IOException {
        JsonObject version = readJson(installer, "version.json");
        JsonObject profile = readJson(installer, "install_profile.json");
        String id = text(version, "id");
        String loader = text(profile, "path");
        require(id.matches("1\\.12\\.2-Outlands-[A-Za-z0-9._-]+") && id.equals(text(profile, "version")),
                "Inconsistent Outlands version ID");
        require(MINECRAFT.equals(text(profile, "minecraft")), "Installer must target Minecraft 1.12.2");
        if (version.has("inheritsFrom")) {
            require(MINECRAFT.equals(text(version, "inheritsFrom")), "Unsupported inherited Minecraft version");
        }
        require(FOUNDATION.equals(text(version, "mainClass")), "Installer must use the Foundation main class");
        require(integer(object(version.get("javaVersion")), "majorVersion") == 25, "Installer must target Java 25");
        require(loader.matches("top\\.outlands:outlands:[A-Za-z0-9._-]+"), "Invalid embedded loader coordinate");

        JsonObject arguments = version.has("arguments") ? object(version.get("arguments")) : new JsonObject();
        if (arguments.has("jvm")) {
            require(WINDOWS_JVM.equals(argumentList(arguments.get("jvm"), osVersion)),
                    "Unsupported Windows JVM arguments in installer");
        }
        List<String> game = arguments.has("game") ? argumentList(arguments.get("game"), osVersion)
                : legacyArguments(text(version, "minecraftArguments"));
        require(!game.isEmpty(), "Installer has no game argument template");
        InstallerMetadata metadata = new InstallerMetadata(id, game, Collections.emptyList());
        boolean foundLoader = false;
        for (JsonElement value : array(version.get("libraries"))) {
            JsonObject library = object(value);
            String name = text(library, "name");
            if (!selected(library, osVersion)) continue;
            boolean embedded = loader.equals(name);
            require(!name.startsWith(LOADER_GROUP) || (embedded && !foundLoader),
                    "Unexpected or duplicate Outlands loader");
            metadata.addLibrary(installer, library, null, embedded ? "maven/" : null);
            foundLoader |= embedded;
        }
        require(foundLoader, "Installer has no verified embedded Outlands loader");
        metadata.classpath.add(Artifact.read(object(object(version.get("downloads")).get("client")),
                "net/minecraft/client/1.12.2/client-1.12.2.jar", null, Collections.emptyList()));
        return metadata;
    }

    static boolean selected(JsonObject library, String osVersion) throws IOException {
        return allowed(library, osVersion) && !wrongWindowsLwjglArch(text(library, "name"));
    }

    // A coordinate supplies missing paths in MMC metadata; installer metadata carries explicit paths.
    void addLibrary(ZipFile zip, JsonObject library, String coordinate, String embeddedPrefix) throws IOException {
        JsonObject downloads = object(library.get("downloads"));
        if (downloads.has("artifact")) {
            Artifact artifact = Artifact.read(object(downloads.get("artifact")),
                    coordinate == null ? null : mavenPath(coordinate, null), embeddedPrefix, Collections.emptyList());
            if (embeddedPrefix != null) {
                ZipEntry entry = zip.getEntry(artifact.embeddedPath);
                require(entry != null && !entry.isDirectory() && entry.getSize() == artifact.size,
                        "Embedded Outlands loader missing or the wrong size");
            }
            classpath.add(artifact);
        } else {
            require(embeddedPrefix == null && library.has("natives"), "Library has neither artifact nor natives");
        }
        if (library.has("natives")) {
            JsonObject nativeNames = object(library.get("natives"));
            if (!nativeNames.has("windows")) return;
            String classifier = text(nativeNames, "windows").replace("${arch}", "64");
            List<String> excludes = new ArrayList<>();
            if (library.has("extract")) {
                for (JsonElement exclude : array(object(library.get("extract")).get("exclude"))) {
                    excludes.add(string(exclude));
                }
            }
            natives.add(Artifact.read(object(object(downloads.get("classifiers")).get(classifier)),
                    coordinate == null ? null : mavenPath(coordinate, classifier), null, excludes));
        }
    }

    static String mavenPath(String coordinate, String classifier) throws IOException {
        String[] parts = coordinate.split(":", -1);
        require(parts.length == 3 || parts.length == 4, "Unsupported JAR coordinate: " + coordinate);
        for (String part : parts) {
            require(part.matches("[A-Za-z0-9_+.-]+"), "Invalid JAR coordinate: " + coordinate);
        }
        if (classifier == null && parts.length == 4) classifier = parts[3];
        require(classifier == null || classifier.matches("[A-Za-z0-9_+.-]+"), "Invalid JAR classifier");
        String path = parts[0].replace('.', '/') + '/' + parts[1] + '/' + parts[2] + '/'
                + parts[1] + '-' + parts[2] + (classifier == null ? "" : "-" + classifier) + ".jar";
        VerifiedCache.validatePath(path);
        return path;
    }

    private static boolean wrongWindowsLwjglArch(String name) {
        // Some local installers give every Windows LWJGL classifier the same OS rule.
        return name.startsWith("org.lwjgl:") && (name.endsWith(":natives-windows-x86")
                || name.endsWith(":natives-windows-arm64"));
    }

    private static boolean allowed(JsonObject object, String osVersion) throws IOException {
        if (!object.has("rules")) return true;
        boolean allowed = false;
        for (JsonElement value : array(object.get("rules"))) {
            JsonObject rule = object(value);
            String action = text(rule, "action");
            require("allow".equals(action) || "disallow".equals(action), "Unknown launcher rule action");
            for (String key : rule.keySet()) {
                require("action".equals(key) || "os".equals(key) || "features".equals(key),
                        "Unsupported launcher rule condition");
            }
            if (rule.has("features")) {
                // The relauncher does not advertise any optional launcher features.
                boolean needsFeature = false;
                for (JsonElement feature : object(rule.get("features")).asMap().values()) {
                    require(feature.isJsonPrimitive() && feature.getAsJsonPrimitive().isBoolean(),
                            "Invalid launcher feature rule");
                    needsFeature |= feature.getAsBoolean();
                }
                if (needsFeature) continue;
            }
            if (rule.has("os")) {
                JsonObject os = object(rule.get("os"));
                for (String key : os.keySet()) {
                    require("name".equals(key) || "arch".equals(key) || "version".equals(key),
                            "Unsupported launcher OS rule");
                }
                if (os.has("name") && !"windows".equals(text(os, "name"))) continue;
                if (os.has("arch") && !"amd64".equals(text(os, "arch")) && !"x86_64".equals(text(os, "arch"))) {
                    continue;
                }
                try {
                    if (os.has("version") && !osVersion.matches(text(os, "version"))) continue;
                } catch (PatternSyntaxException ex) {
                    throw new IOException("Invalid launcher OS version rule", ex);
                }
            }
            allowed = "allow".equals(action);
        }
        return allowed;
    }

    static List<String> argumentList(JsonElement list, String osVersion) throws IOException {
        List<String> result = new ArrayList<>();
        for (JsonElement value : array(list)) {
            if (value.isJsonObject()) {
                JsonObject entry = object(value);
                require(entry.has("rules"), "Missing rules on conditional argument");
                if (!allowed(entry, osVersion)) continue;
                value = entry.get("value");
                if (value != null && value.isJsonArray()) {
                    for (JsonElement item : array(value)) result.add(string(item));
                    continue;
                }
            }
            result.add(string(value));
        }
        return result;
    }

    static List<String> legacyArguments(String arguments) throws IOException {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < arguments.length(); i++) {
            char ch = arguments.charAt(i);
            if (quote != 0) {
                if (ch == quote) quote = 0;
                else if (ch == '\\' && i + 1 < arguments.length() && arguments.charAt(i + 1) == quote) {
                    token.append(arguments.charAt(++i));
                } else token.append(ch);
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
                started = true;
            } else if (Character.isWhitespace(ch)) {
                if (started) tokens.add(token.toString());
                token.setLength(0);
                started = false;
            } else {
                token.append(ch);
                started = true;
            }
        }
        require(quote == 0, "Unterminated quote in legacy game arguments");
        if (started) tokens.add(token.toString());
        return tokens;
    }

    static JsonObject readJson(ZipFile installer, String name) throws IOException {
        ZipEntry entry = installer.getEntry(name);
        require(entry != null && !entry.isDirectory() && entry.getSize() >= 0 && entry.getSize() <= MAX_JSON_BYTES,
                "Missing or oversized installer metadata: " + name);
        byte[] bytes;
        try (InputStream input = installer.getInputStream(entry)) {
            bytes = VerifiedCache.readBytes(input, MAX_JSON_BYTES);
        }
        try (JsonReader reader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(bytes),
                StandardCharsets.UTF_8.newDecoder()))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement json = JsonParser.parseReader(reader);
            require(reader.peek() == JsonToken.END_DOCUMENT, "Trailing content in " + name);
            return object(json);
        } catch (RuntimeException ex) {
            throw new IOException("Invalid installer metadata: " + name, ex);
        }
    }

    static JsonObject object(JsonElement value) throws IOException {
        require(value != null && value.isJsonObject(), "Expected installer metadata object");
        return value.getAsJsonObject();
    }

    static JsonArray array(JsonElement value) throws IOException {
        require(value != null && value.isJsonArray(), "Expected installer metadata array");
        return value.getAsJsonArray();
    }

    static String string(JsonElement value) throws IOException {
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(),
                "Expected installer metadata string");
        String result = value.getAsString();
        require(result.indexOf('\0') < 0, "NUL in installer metadata string");
        return result;
    }

    static String text(JsonObject object, String key) throws IOException {
        if (!object.has(key)) throw new IOException("Missing installer metadata field: " + key);
        return string(object.get(key));
    }

    static long integer(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(),
                "Missing installer numeric field: " + key);
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IOException("Invalid installer integer: " + key, ex);
        }
    }

    static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    static final class Artifact {
        final String path;
        final long size;
        final String algorithm;
        final String digest;
        final URI uri;
        final List<String> excludes;
        final String embeddedPath;

        Artifact(String path, long size, String algorithm, String digest, URI uri, List<String> excludes) {
            this(path, size, algorithm, digest, uri, excludes, null);
        }

        Artifact(String path, long size, String algorithm, String digest, URI uri, List<String> excludes,
                String embeddedPath) {
            this.path = path;
            this.size = size;
            this.algorithm = algorithm;
            this.digest = digest;
            this.uri = uri;
            this.excludes = excludes;
            this.embeddedPath = embeddedPath;
        }

        static Artifact read(JsonObject object, String defaultPath, String embeddedPrefix, List<String> excludes)
                throws IOException {
            String path = object.has("path") ? text(object, "path") : defaultPath;
            VerifiedCache.validatePath(path);
            require(path.endsWith(".jar"), "Artifact is not a JAR: " + path);
            long size = integer(object, "size");
            require(size > 0 && size <= MAX_ARTIFACT_BYTES, "Invalid artifact size: " + path);
            boolean sha256 = object.has("sha256");
            String hash = text(object, sha256 ? "sha256" : "sha1");
            require(hash.matches(sha256 ? "[a-f0-9A-F]{64}" : "[a-f0-9A-F]{40}"),
                    "Invalid artifact digest: " + path);
            URI uri = embeddedPrefix == null ? VerifiedCache.httpsUri(text(object, "url")) : null;
            return new Artifact(path, size, sha256 ? "SHA-256" : "SHA-1",
                    hash.toLowerCase(java.util.Locale.ROOT), uri, excludes,
                    embeddedPrefix == null ? null : embeddedPrefix + path);
        }
    }
}
