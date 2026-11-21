package top.outlands.relauncher;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaRuntimeTest {
    @TempDir Path instance;

    @Test
    void acceptsJava25AndNewer() {
        assertTrue(JavaRuntime.matchesProbe("    java.specification.version = 25\r\n    os.arch = amd64\r\n"));
        assertTrue(JavaRuntime.matchesProbe("os.arch=x86_64\njava.specification.version=26"));
        assertTrue(JavaRuntime.matchesProbe("java.specification.version=100\nos.arch=x64"));
    }

    @Test
    void rejectsOlderInvalidOrMissingFeatureVersions() {
        assertFalse(JavaRuntime.matchesProbe("java.specification.version=24\nos.arch=amd64"));
        assertFalse(JavaRuntime.matchesProbe("java.specification.version=25.0\nos.arch=amd64"));
        assertFalse(JavaRuntime.matchesProbe("java.specification.version=26-ea\nos.arch=amd64"));
        assertFalse(JavaRuntime.matchesProbe("java.specification.version=+25\nos.arch=amd64"));
        assertFalse(JavaRuntime.matchesProbe("java.specification.version=9999999999\nos.arch=amd64"));
        assertFalse(JavaRuntime.matchesProbe("java.specification.version=\nos.arch=amd64"));
        assertFalse(JavaRuntime.matchesProbe("java.version=25\nos.arch=amd64"));
    }

    @Test
    void requiresX64RuntimeArchitecture() {
        assertFalse(JavaRuntime.matchesProbe("java.specification.version=25\nos.arch=aarch64"));
        assertFalse(JavaRuntime.matchesProbe("java.specification.version=26\nos.arch=x86"));
        assertFalse(JavaRuntime.matchesProbe("java.specification.version=25"));
    }

    @Test
    void requiresWindowsX64ForNativeSupport() {
        assertTrue(JavaRuntime.isWindowsX64("Windows 11", "amd64"));
        assertFalse(JavaRuntime.isWindowsX64("Windows 11", "aarch64"));
        assertFalse(JavaRuntime.isWindowsX64("Linux", "amd64"));
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
