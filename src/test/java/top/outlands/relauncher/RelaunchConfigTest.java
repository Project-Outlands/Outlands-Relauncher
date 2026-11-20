package top.outlands.relauncher;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RelaunchConfigTest {
    @TempDir Path instance;

    @Test
    void defaultsToAskWithoutCreatingConfiguration() throws Exception {
        RelaunchConfig config = RelaunchConfig.read(instance);
        assertEquals(RelaunchConfig.Mode.ASK, config.mode);
        assertNull(config.javaExecutable());
        assertNull(config.localInstaller());
        assertFalse(Files.exists(instance.resolve("config")));
    }

    @Test
    void resolvesRelativePathsAndKeepsOtherSettingsWhenSavingAuto() throws Exception {
        Path file = Files.createDirectory(instance.resolve("config")).resolve("outlands-relauncher.properties");
        Files.write(file, ("mode=ask\njavaExecutable=jdk 25/bin/java.exe\n"
                + "localInstaller=installers/开发.jar\nother=keep\n").getBytes(StandardCharsets.UTF_8));
        RelaunchConfig config = RelaunchConfig.read(instance.toRealPath());
        assertEquals(instance.resolve("jdk 25/bin/java.exe"), config.javaExecutable());
        assertEquals(instance.resolve("installers/开发.jar"), config.localInstaller());
        Path picked = instance.resolve("picked/bin/java.exe");
        config.saveAuto(picked);
        RelaunchConfig saved = RelaunchConfig.read(instance);
        assertEquals(RelaunchConfig.Mode.AUTO, saved.mode);
        assertEquals(picked, saved.javaExecutable());
        assertEquals(config.localInstaller(), saved.localInstaller());
        assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains("other=keep"));
    }
}
