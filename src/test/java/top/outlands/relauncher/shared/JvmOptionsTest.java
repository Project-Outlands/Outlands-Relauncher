package top.outlands.relauncher.shared;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmOptionsTest {
    @Test
    void mergesExplicitAndInheritedMemoryWithoutInheritingUnrelatedOptions() throws IOException {
        assertEquals(Arrays.asList("-Xms1g", "-Xmx2g", "-Dexample.name=two words", "-XX:+UseG1GC",
                        "--add-opens=java.base/java.lang=ALL-UNNAMED"),
                JvmOptions.resolve("", "2g", "-Dexample.name=two words\r\n\r\n-XX:+UseG1GC\n"
                                + "--add-opens=java.base/java.lang=ALL-UNNAMED",
                        Arrays.asList("-Xms512m", "-Xms1g", "-Xmx4g", "-javaagent:forge.jar")));
        assertTrue(JvmOptions.resolve("", "", "", Collections.emptyList()).isEmpty());
    }

    @Test
    void rejectsInvalidAndInconsistentHeapSizes() {
        for (String size : Arrays.asList("0", "nine", "-Xms1g", "9999999999999999999g", "9999999999999999g")) {
            assertThrows(IOException.class, () -> JvmOptions.resolve(size, "", "", Collections.emptyList()), size);
        }
        assertThrows(IOException.class, () -> JvmOptions.resolve("2049m", "2g", "", Collections.emptyList()));
        assertThrows(IOException.class, () -> JvmOptions.resolve("", "512m", "", Collections.singletonList("-Xms1g")));
    }

    @Test
    void cannotReplaceTheRelaunchClasspathOrBootstrap() {
        for (String option : Arrays.asList("-javaagent:another.jar", "-agentlib:jdwp", "@other.args", "-cp path",
                "--class-path=other.jar", "-jar another.jar", "-Djava.library.path=x", "-Doutlands.relauncher.child=false",
                "-Djava.system.class.loader=Example", "-Xmx2g", "-XX:VMOptionsFile=options", "-Xlog:help", "--version")) {
            IOException failure = assertThrows(IOException.class,
                    () -> JvmOptions.resolve("", "", "-Dgood=yes\n" + option, Collections.emptyList()), option);
            assertTrue(failure.getMessage().contains("line 2"));
        }
        assertThrows(IOException.class, () -> JvmOptions.validate(Arrays.asList("-Xms512m", "-Xms1g")));
    }
}
