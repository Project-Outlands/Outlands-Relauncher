package top.outlands.relauncher;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
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
        assertTrue(config.settings().automatic);
        assertFalse(Files.exists(instance.resolve("config")));
    }

    @Test
    void legacySettingsResolveRelativePathsAndRoundTripWithoutLosingUnknownKeys() throws Exception {
        Path file = Files.createDirectory(instance.resolve("config")).resolve("outlands-relauncher.properties");
        Files.write(file, ("mode=ask\njavaExecutable=jdk 25/bin/java.exe\n"
                + "localInstaller=installers/开发.jar\nother=keep\n").getBytes(StandardCharsets.UTF_8));
        RelaunchConfig config = RelaunchConfig.read(instance.toRealPath());
        assertEquals(instance.resolve("jdk 25/bin/java.exe"), config.javaExecutable());
        assertEquals(instance.resolve("installers/开发.jar"), config.localInstaller());
        assertEquals("jdk 25/bin/java.exe", config.settings().javaExecutable);
        assertEquals("installers/开发.jar", config.settings().localInstaller);
        assertFalse(config.settings().automatic);

        String custom = "-Dexample=two words\n-XX:+UseG1GC";
        config.save(new LaunchSettings("picked/bin/java.exe", "installers/开发.jar", "512M", "2G", custom, true));
        RelaunchConfig saved = RelaunchConfig.read(instance);
        assertEquals(RelaunchConfig.Mode.AUTO, saved.mode);
        assertEquals(instance.resolve("picked/bin/java.exe"), saved.javaExecutable());
        assertEquals(config.localInstaller(), saved.localInstaller());
        LaunchSettings settings = saved.settings();
        assertTrue(settings.automatic);
        assertEquals("picked/bin/java.exe", settings.javaExecutable);
        assertEquals("512M", settings.xms);
        assertEquals("2G", settings.xmx);
        assertEquals(custom, settings.jvmArgs);
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        assertEquals("keep", properties.getProperty("other"));
        assertEquals("auto", properties.getProperty("mode"));
    }

    @Test
    void savingOptOutReopensSettingsAndCanClearTheLocalInstaller() throws Exception {
        RelaunchConfig.read(instance).save(new LaunchSettings("java.exe", "local.jar", "", "", "", true));
        RelaunchConfig.read(instance).save(new LaunchSettings("java.exe", "", "", "2G", "-Dexample=keep", false));

        RelaunchConfig saved = RelaunchConfig.read(instance);
        assertEquals(RelaunchConfig.Mode.ASK, saved.mode);
        assertFalse(saved.settings().automatic);
        assertNull(saved.localInstaller());
        assertEquals("2G", saved.settings().xmx);
        assertEquals("-Dexample=keep", saved.settings().jvmArgs);
    }
}
