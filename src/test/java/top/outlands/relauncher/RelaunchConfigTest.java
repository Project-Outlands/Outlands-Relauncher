package top.outlands.relauncher;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
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
        assertPolicy(config, false, false, 25);
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

    @Test
    void locksOnlyTheExpectedFields() {
        EnumSet<LaunchSettings.Field> advanced = EnumSet.of(LaunchSettings.Field.INSTALLER,
                LaunchSettings.Field.MMC, LaunchSettings.Field.RELEASE, LaunchSettings.Field.PROXY,
                LaunchSettings.Field.MIRROR, LaunchSettings.Field.LIBRARIES, LaunchSettings.Field.JVM_ARGS);
        PackAuthorSettings advancedPolicy = new PackAuthorSettings(true, false, 25);
        PackAuthorSettings jvmPolicy = new PackAuthorSettings(false, true, 25);
        for (LaunchSettings.Field field : LaunchSettings.Field.values()) {
            assertFalse(PackAuthorSettings.DEFAULT.locks(field), field.name());
            assertEquals(advanced.contains(field), advancedPolicy.locks(field), field.name());
            assertEquals(field == LaunchSettings.Field.JVM_ARGS, jvmPolicy.locks(field), field.name());
        }
        assertThrows(IllegalArgumentException.class, () -> new PackAuthorSettings(false, false, 24));
    }

    @Test
    void savesPolicyImmediatelyWithoutMakingModeExplicit() throws Exception {
        RelaunchConfig config = RelaunchConfig.read(instance);
        config.saveAuthorSettings(new PackAuthorSettings(true, true, 27));

        assertPolicy(config, true, true, 27);
        assertTrue(config.settings().automatic);
        RelaunchConfig saved = RelaunchConfig.read(instance);
        assertPolicy(saved, true, true, 27);
        assertEquals(RelaunchConfig.Mode.ASK, saved.mode);
        assertTrue(saved.settings().automatic);
        Properties properties = readProperties(configFile());
        assertEquals(3, properties.size());
        assertEquals("true", properties.getProperty("author.lockAdvanced"));
        assertEquals("true", properties.getProperty("author.lockJvmArguments"));
        assertEquals("27", properties.getProperty("author.minimumJava"));
    }

    @Test
    void unlocksWhenLockKeysAreManuallySetFalseOrDeleted() throws Exception {
        RelaunchConfig config = RelaunchConfig.read(instance);
        config.saveAuthorSettings(new PackAuthorSettings(true, true, 26));
        Path file = configFile();
        Files.write(file, "author.lockAdvanced=false\nauthor.minimumJava=26\n".getBytes(StandardCharsets.UTF_8));
        assertPolicy(RelaunchConfig.read(instance), false, false, 26);

        config.saveAuthorSettings(new PackAuthorSettings(true, true, 26));
        Files.write(file, "author.lockJvmArguments=false\n".getBytes(StandardCharsets.UTF_8));
        assertPolicy(RelaunchConfig.read(instance), false, false, 25);
    }

    @Test
    void policySavePreservesExplicitModeLaunchOptionsAndUnknownKeys() throws Exception {
        Path file = Files.createDirectory(instance.resolve("config")).resolve("outlands-relauncher.properties");
        Files.write(file, ("mode=forge\njavaExecutable=java.exe\nlocalInstaller=local.jar\nlocalMmcPack=pack.zip\n"
                + "xms=512M\nxmx=2G\njvmArgs=-Dexample=keep\nreleaseVersion=1.2.3\nproxy=proxy.example\n"
                + "mavenMirror=mirror.example\nlibrariesDirectory=libs\nother=keep\n")
                .getBytes(StandardCharsets.UTF_8));
        Properties before = readProperties(file);
        RelaunchConfig config = RelaunchConfig.read(instance);
        config.saveAuthorSettings(new PackAuthorSettings(true, false, 26));

        Properties after = readProperties(file);
        after.remove("author.lockAdvanced");
        after.remove("author.lockJvmArguments");
        after.remove("author.minimumJava");
        assertEquals(before, after);
        assertFalse(config.settings().automatic);
        assertEquals(RelaunchConfig.Mode.FORGE, RelaunchConfig.read(instance).mode);
    }

    @Test
    void launchAndPolicySavesOnTheSameConfigPreserveEachOther() throws Exception {
        RelaunchConfig config = RelaunchConfig.read(instance);
        config.save(new LaunchSettings("java.exe", "", "", "2G", "", false));
        config.saveAuthorSettings(new PackAuthorSettings(false, true, 27));
        RelaunchConfig saved = RelaunchConfig.read(instance);
        assertEquals("2G", saved.settings().xmx);
        assertFalse(saved.settings().automatic);
        config.save(new LaunchSettings("java.exe", "", "", "3G", "", true));
        assertPolicy(RelaunchConfig.read(instance), false, true, 27);
    }

    @Test
    void rejectsMalformedConfigWithIOException() throws Exception {
        Path file = Files.createDirectory(instance.resolve("config")).resolve("outlands-relauncher.properties");
        for (String value : new String[] {"author.lockAdvanced=yes", "author.lockJvmArguments=",
                "author.minimumJava=24", "author.minimumJava=25.5", "author.minimumJava=2147483648",
                "mode=unknown", "other=\\uZZZZ"}) {
            Files.write(file, value.getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> RelaunchConfig.read(instance), value);
        }
    }

    @Test
    void failedPolicySaveKeepsPreviousSnapshot() throws Exception {
        RelaunchConfig config = RelaunchConfig.read(instance);
        config.saveAuthorSettings(new PackAuthorSettings(false, true, 26));
        Files.delete(configFile());
        Files.createDirectory(configFile());

        assertThrows(IOException.class, () -> config.saveAuthorSettings(new PackAuthorSettings(true, false, 27)));
        assertPolicy(config, false, true, 26);
        assertTrue(Files.isDirectory(configFile()));
    }

    private Path configFile() {
        return instance.resolve("config/outlands-relauncher.properties");
    }

    private static Properties readProperties(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static void assertPolicy(RelaunchConfig config, boolean advanced, boolean jvm, int minimum) {
        PackAuthorSettings settings = config.authorSettings();
        assertEquals(advanced, settings.lockAdvanced);
        assertEquals(jvm, settings.lockJvmArguments);
        assertEquals(minimum, settings.minimumJava);
    }
}
