package top.outlands.relauncher.shared;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** The current Forge session and the supported options for the Outlands client. */
public final class GameArguments {
    private static final List<String> REQUIRED = Arrays.asList("--username", "--version", "--gameDir",
            "--assetsDir", "--assetIndex", "--uuid", "--accessToken", "--userType", "--tweakClass", "--versionType");
    private static final List<String> OPTIONAL = Arrays.asList("--userProperties", "--profileProperties",
            "--width", "--height", "--server", "--port");
    private static final String FML_TWEAKER = "net.minecraftforge.fml.common.launcher.FMLTweaker";

    private final Map<String, String> options;
    private final boolean fullscreen;
    private final boolean demo;

    private GameArguments(Map<String, String> options, boolean fullscreen, boolean demo) {
        this.options = options;
        this.fullscreen = fullscreen;
        this.demo = demo;
    }

    public static GameArguments capture(Map<?, ?> snapshot, Path gameDir) throws IOException {
        Map<String, String> options = new HashMap<>();
        for (String flag : Arrays.asList("--username", "--uuid", "--accessToken", "--assetIndex", "--assetsDir")) {
            String value = stringValue(snapshot, flag);
            if (value.isEmpty()) throw new IOException("Forge did not provide " + flag);
            options.put(flag, value);
        }
        String profileType = stringValue(snapshot, "--userType");
        options.put("--userType", profileType.isEmpty() ? "legacy" : profileType);
        options.put("--gameDir", gameDir.toAbsolutePath().normalize().toString());
        for (String flag : OPTIONAL) {
            String value = stringValue(snapshot, flag);
            if (!value.isEmpty()) options.put(flag, value);
        }
        String assetIndex = options.get("--assetIndex");
        if (!assetIndex.matches("[a-zA-Z0-9_-]+(?:\\.[a-zA-Z0-9_-]+)*")) {
            throw new IOException("Invalid Forge asset index ID");
        }
        Path assets = Paths.get(options.get("--assetsDir")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(assets.resolve("indexes").resolve(assetIndex + ".json"))
                || !Files.isDirectory(assets.resolve("objects"))) {
            throw new IOException("The Forge instance's asset index or objects directory is missing");
        }
        options.put("--assetsDir", assets.toString());
        return new GameArguments(options, snapshot.containsKey("--fullscreen"), snapshot.containsKey("--demo"));
    }

    public List<String> resolve(Path launchProperties) throws IOException {
        Properties metadata = new Properties();
        try (InputStream stream = Files.newInputStream(launchProperties)) {
            metadata.load(stream);
        }
        final int length;
        try {
            length = Integer.parseInt(metadata.getProperty("game.count", ""));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid Outlands game argument count", e);
        }
        String version = metadata.getProperty("version", "");
        if (length < 2 || length > 64 || length % 2 != 0 || version.isEmpty()) {
            throw new IOException("Invalid Outlands game argument template");
        }
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("--username", "${auth_player_name}");
        placeholders.put("--uuid", "${auth_uuid}");
        placeholders.put("--accessToken", "${auth_access_token}");
        placeholders.put("--userType", "${user_type}");
        placeholders.put("--version", "${version_name}");
        placeholders.put("--gameDir", "${game_directory}");
        placeholders.put("--assetsDir", "${assets_root}");
        placeholders.put("--assetIndex", "${assets_index_name}");

        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < length; i += 2) {
            String flag = metadata.getProperty("game." + i, "");
            String template = metadata.getProperty("game." + (i + 1), "");
            if (!REQUIRED.contains(flag) || !seen.add(flag)) {
                throw new IOException("Unsupported or duplicate flag in Outlands game argument template");
            }
            String value;
            if ("--tweakClass".equals(flag) && FML_TWEAKER.equals(template)) {
                value = template;
            } else if ("--versionType".equals(flag) && ("Outlands".equals(template) || "${version_type}".equals(template))) {
                value = "Outlands";
            } else if (template.equals(placeholders.get(flag))) {
                value = "--version".equals(flag) ? version : options.get(flag);
            } else {
                throw new IOException("Unsupported value for " + flag + " in Outlands game argument template");
            }
            result.add(flag);
            result.add(value);
        }
        if (!seen.containsAll(REQUIRED)) throw new IOException("Outlands game argument template omits a required option");
        for (String flag : OPTIONAL) {
            if (options.containsKey(flag)) {
                result.add(flag);
                result.add(options.get(flag));
            }
        }
        if (fullscreen) result.add("--fullscreen=");
        if (demo) result.add("--demo=");
        return result;
    }

    private static String stringValue(Map<?, ?> snapshot, String flag) {
        Object value = snapshot.get(flag);
        return value instanceof String ? (String) value : "";
    }
}
