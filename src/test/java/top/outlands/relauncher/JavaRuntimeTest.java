package top.outlands.relauncher;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaRuntimeTest {
    @TempDir Path instance;

    @Test
    void searchesOnlyAbsolutePathsInOrderAndDeduplicates() {
        Path first = instance.resolve("current Java");
        Path home = instance.resolve("env home");
        Path pathBin = instance.resolve("other Java/bin");
        String path = File.pathSeparator + "relative" + File.pathSeparator + first.resolve("bin")
                + File.pathSeparator + '"' + pathBin.toString() + '"';
        assertEquals(Arrays.asList(first.resolve("bin/java.exe"), home.resolve("bin/java.exe"),
                pathBin.resolve("java.exe")), JavaRuntime.candidates(first.toString(), home.toString(), path));
    }

    @Test
    void requiresExactJavaSpecificationAndX64Architecture() {
        assertTrue(JavaRuntime.matchesProbe("    java.specification.version = 25\r\n    os.arch = amd64\r\n"));
        assertTrue(JavaRuntime.matchesProbe("os.arch=x86_64\njava.specification.version=25"));
        assertFalse(JavaRuntime.matchesProbe("java.version=25\nos.arch=amd64"));
        assertFalse(JavaRuntime.matchesProbe("java.specification.version=26\nos.arch=amd64"));
        assertFalse(JavaRuntime.matchesProbe("java.specification.version=25\nos.arch=aarch64"));
        assertFalse(JavaRuntime.matchesProbe("java.specification.version=25\nos.arch=x86"));
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
