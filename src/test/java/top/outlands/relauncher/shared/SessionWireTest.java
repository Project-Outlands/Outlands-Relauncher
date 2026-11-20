package top.outlands.relauncher.shared;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionWireTest {
    @Test
    void sendsLiteralArgumentsOverThePipeWithoutShellOrArgfileExpansion() throws IOException {
        List<String> arguments = Arrays.asList("--gameDir", "D:\\游戏 with spaces\\", "--userProperties",
                "{\"note\":\"quoted value\"}", "--accessToken", "@not-a-java-argfile", "");
        ByteArrayOutputStream pipe = new ByteArrayOutputStream();
        SessionWire.write(pipe, arguments);
        assertArrayEquals(arguments.toArray(new String[0]), SessionWire.read(new ByteArrayInputStream(pipe.toByteArray())));
    }

    @Test
    void rejectsOversizedAndTruncatedMessages() throws IOException {
        ByteArrayOutputStream pipe = new ByteArrayOutputStream();
        List<String> tooMany = Collections.nCopies(129, "argument");
        assertThrows(IOException.class, () -> SessionWire.write(pipe, tooMany));

        SessionWire.write(pipe, Arrays.asList("--accessToken", "test token"));
        byte[] truncated = Arrays.copyOf(pipe.toByteArray(), pipe.size() - 1);
        assertThrows(IOException.class, () -> SessionWire.read(new ByteArrayInputStream(truncated)));
    }
}
