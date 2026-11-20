package top.outlands.relauncher.shared;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameArgumentsTest {
    private static final String TOKEN = "secret token for the test";
    private static final String[] TEMPLATE = {
            "--username", "${auth_player_name}", "--version", "${version_name}", "--gameDir", "${game_directory}",
            "--assetsDir", "${assets_root}", "--assetIndex", "${assets_index_name}", "--uuid", "${auth_uuid}",
            "--accessToken", "${auth_access_token}", "--userType", "${user_type}", "--tweakClass",
            "net.minecraftforge.fml.common.launcher.FMLTweaker", "--versionType", "Outlands"
    };

    @TempDir Path root;

    @Test
    void resolvesTheTargetTemplateWithTheOriginalSessionAndDirectories() throws IOException {
        Map<String, String> forge = forgeArguments();
        forge.put("--width", "1280");
        forge.put("--fullscreen", "");
        forge.put("--originalLauncherOption", "ignored");
        Path gameDir = root.resolve("存档 with spaces");
        Path plan = launchPlan(TEMPLATE);
        byte[] before = Files.readAllBytes(plan);

        List<String> arguments = GameArguments.capture(forge, gameDir).resolve(plan);

        assertEquals(Arrays.asList("--username", "Player", "--version", "1.12.2-Outlands-2026.11.16",
                "--gameDir", gameDir.toString(), "--assetsDir", forge.get("--assetsDir"), "--assetIndex", "1.12",
                "--uuid", forge.get("--uuid"), "--accessToken", TOKEN, "--userType", "msa", "--tweakClass",
                "net.minecraftforge.fml.common.launcher.FMLTweaker", "--versionType", "Outlands", "--width", "1280",
                "--fullscreen="), arguments);
        assertTrue(Arrays.equals(before, Files.readAllBytes(plan)), "resolving does not write the session into the plan");
    }

    @Test
    void rejectsMissingSessionAndAssetIndexBeforeStartingAChild() throws IOException {
        Map<String, String> missing = forgeArguments();
        missing.remove("--uuid");
        IOException noUuid = assertThrows(IOException.class, () -> GameArguments.capture(missing, root));
        assertFalse(noUuid.getMessage().contains(TOKEN));

        Map<String, String> invalidIndex = forgeArguments();
        invalidIndex.put("--assetIndex", "../login");
        assertThrows(IOException.class, () -> GameArguments.capture(invalidIndex, root));
        invalidIndex.put("--assetIndex", "not-installed");
        assertThrows(IOException.class, () -> GameArguments.capture(invalidIndex, root));
    }

    @Test
    void refusesAPlanThatSubstitutesCredentialsIntoAnUnrelatedOption() throws IOException {
        String[] template = TEMPLATE.clone();
        template[1] = "${auth_access_token}";
        GameArguments session = GameArguments.capture(forgeArguments(), root);
        IOException failure = assertThrows(IOException.class, () -> session.resolve(launchPlan(template)));
        assertFalse(failure.getMessage().contains(TOKEN));
    }

    private Map<String, String> forgeArguments() throws IOException {
        Path assets = root.resolve("游戏 assets");
        Files.createDirectories(assets.resolve("indexes"));
        Files.createDirectories(assets.resolve("objects"));
        Files.write(assets.resolve("indexes/1.12.json"), new byte[] {'{', '}'});
        Map<String, String> args = new HashMap<>();
        args.put("--assetsDir", assets.toString());
        args.put("--assetIndex", "1.12");
        args.put("--username", "Player");
        args.put("--uuid", "5d9a54e5f89a4abcb7346c4a12de3c64");
        args.put("--accessToken", TOKEN);
        args.put("--userType", "msa");
        args.put("--version", "Forge");
        return args;
    }

    private Path launchPlan(String[] template) throws IOException {
        Properties metadata = new Properties();
        metadata.setProperty("version", "1.12.2-Outlands-2026.11.16");
        metadata.setProperty("game.count", Integer.toString(template.length));
        for (int i = 0; i < template.length; i++) metadata.setProperty("game." + i, template[i]);
        Path plan = root.resolve("launch.properties");
        try (OutputStream output = Files.newOutputStream(plan)) {
            metadata.store(output, null);
        }
        return plan;
    }
}
