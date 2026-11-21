package top.outlands.relauncher;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import top.outlands.relauncher.shared.LaunchPlatform;

class JavaRuntimeTest {
    @TempDir Path instance;

    @Test
    void acceptsJava25AndNewer() {
        assertTrue(JavaRuntime.matchesProbe("    java.specification.version = 25\r\n    os.arch = amd64\r\n", 25));
        assertTrue(JavaRuntime.matchesProbe("os.arch=x86_64\njava.specification.version=26", 26));
        assertFalse(JavaRuntime.matchesProbe("os.arch=x86_64\njava.specification.version=25", 26));
        assertTrue(JavaRuntime.matchesProbe("java.specification.version=100\nos.arch=x64", 25));
    }

    @Test
    void rejectsOlderInvalidOrMissingFeatureVersions() {
        assertFalse(JavaRuntime.matchesProbe("java.specification.version=24\nos.arch=amd64", 25));
        assertFalse(JavaRuntime.matchesProbe("java.specification.version=25.0\nos.arch=amd64", 25));
        assertFalse(JavaRuntime.matchesProbe("java.specification.version=26-ea\nos.arch=amd64", 25));
        assertFalse(JavaRuntime.matchesProbe("java.specification.version=+25\nos.arch=amd64", 25));
        assertFalse(JavaRuntime.matchesProbe("java.specification.version=9999999999\nos.arch=amd64", 25));
        assertFalse(JavaRuntime.matchesProbe("java.specification.version=\nos.arch=amd64", 25));
        assertFalse(JavaRuntime.matchesProbe("java.version=25\nos.arch=amd64", 25));
    }

    @Test
    void requiresSupportedRuntimeArchitecture() {
        assertTrue(JavaRuntime.matchesProbe("java.specification.version=25\nos.arch=aarch64", 25));
        assertTrue(JavaRuntime.matchesProbe("java.specification.version=25\nos.arch=arm64", 25));
        assertFalse(JavaRuntime.matchesProbe("java.specification.version=26\nos.arch=x86", 25));
        assertFalse(JavaRuntime.matchesProbe("java.specification.version=25", 25));
    }

    @Test
    void acceptsOnlyJavaLauncherBasenamesForTheHostPlatform() {
        LaunchPlatform windows = LaunchPlatform.detect("Windows 11", "amd64", "10.0");
        LaunchPlatform linux = LaunchPlatform.detect("Linux", "x86_64", "6.0");
        LaunchPlatform mac = LaunchPlatform.detect("Mac OS X", "aarch64", "15.0");
        assertTrue(JavaRuntime.isJavaExecutableName("java.exe", windows));
        assertTrue(JavaRuntime.isJavaExecutableName("JAVAW.EXE", windows));
        assertFalse(JavaRuntime.isJavaExecutableName("java", windows));
        assertFalse(JavaRuntime.isJavaExecutableName("javac.exe", windows));
        assertFalse(JavaRuntime.isJavaExecutableName("bin/javaw.exe", windows));
        for (LaunchPlatform platform : new LaunchPlatform[] {linux, mac}) {
            assertTrue(JavaRuntime.isJavaExecutableName("java", platform));
            assertFalse(JavaRuntime.isJavaExecutableName("JAVA", platform));
            assertFalse(JavaRuntime.isJavaExecutableName("java.exe", platform));
            assertFalse(JavaRuntime.isJavaExecutableName("javaw.exe", platform));
        }
        assertFalse(JavaRuntime.isJavaExecutableName(null, windows));
        assertFalse(JavaRuntime.isJavaExecutableName("java", null));
    }

    @Test
    void launchesTheExactSelectedJavawPath() {
        Path javaw = instance.resolve("java home").resolve("bin").resolve("JAVAW.EXE");
        ProcessBuilder game = RelaunchOrchestrator.gameCommand(javaw, instance, instance.resolve("launch.args"));
        assertEquals(javaw.toString(), game.command().get(0));
    }

    @Test
    void childEnvironmentCannotInjectJavaOptionsOrClasspath() {
        ProcessBuilder process = JavaRuntime.command(instance, Collections.singletonList(Paths.get("java").toString()));
        for (String key : process.environment().keySet()) {
            assertFalse("JAVA_TOOL_OPTIONS".equalsIgnoreCase(key)
                    || "_JAVA_OPTIONS".equalsIgnoreCase(key) || "JDK_JAVA_OPTIONS".equalsIgnoreCase(key)
                    || "CLASSPATH".equalsIgnoreCase(key));
        }
    }
}
