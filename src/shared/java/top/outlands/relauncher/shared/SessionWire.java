package top.outlands.relauncher.shared;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** A bounded, UTF-8 game argument vector carried only by the child process pipe. */
public final class SessionWire {
    private static final int MAGIC = 0x4F524C31; // ORL1
    private static final int MAX_ARGUMENTS = 128;
    private static final int MAX_BYTES = 1024 * 1024;

    private SessionWire() {
    }

    public static void write(OutputStream output, List<String> arguments) throws IOException {
        checkCount(arguments.size());
        int remaining = MAX_BYTES;
        byte[][] encoded = new byte[arguments.size()][];
        for (int i = 0; i < encoded.length; i++) {
            encoded[i] = arguments.get(i).getBytes(StandardCharsets.UTF_8);
            remaining = checkLength(encoded[i].length, remaining);
        }
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(MAGIC);
        data.writeInt(encoded.length);
        for (byte[] argument : encoded) {
            data.writeInt(argument.length);
            data.write(argument);
        }
        data.flush();
    }

    public static String[] read(InputStream input) throws IOException {
        DataInputStream data = new DataInputStream(input);
        if (data.readInt() != MAGIC) throw new IOException("Invalid game argument protocol");
        int count = data.readInt();
        checkCount(count);
        String[] arguments = new String[count];
        int remaining = MAX_BYTES;
        for (int i = 0; i < count; i++) {
            int length = data.readInt();
            remaining = checkLength(length, remaining);
            byte[] bytes = new byte[length];
            data.readFully(bytes);
            arguments[i] = new String(bytes, StandardCharsets.UTF_8);
        }
        return arguments;
    }

    private static void checkCount(int count) throws IOException {
        if (count < 1 || count > MAX_ARGUMENTS) throw new IOException("Invalid game argument count");
    }

    private static int checkLength(int length, int remaining) throws IOException {
        if (length < 0 || length > remaining) throw new IOException("Invalid game argument length");
        return remaining - length;
    }
}
