package top.outlands.relauncher.shared;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Argument file encoding shared by the Java option probe and game launch. */
public final class JavaArgFile {
    private JavaArgFile() {}

    public static Charset launcherCharset() {
        // The launcher reads platform-encoded paths, notably the Windows code page rather than the Java default.
        return Charset.forName(System.getProperty("sun.jnu.encoding", StandardCharsets.UTF_8.name()));
    }

    public static void write(OutputStream output, List<String> arguments, Charset charset) throws IOException {
        for (String argument : arguments) {
            ByteBuffer encoded;
            try {
                encoded = charset.newEncoder().encode(CharBuffer.wrap(argument));
            } catch (CharacterCodingException ex) {
                throw new IOException("Launch argument cannot be represented in the platform encoding " + charset, ex);
            }
            output.write('"');
            // The tokenizer treats a backslash byte inside a multibyte character as an escape too.
            while (encoded.hasRemaining()) {
                int ch = encoded.get() & 0xff;
                switch (ch) {
                    case '\\': case '"': output.write('\\'); output.write(ch); break;
                    case '\n': output.write('\\'); output.write('n'); break;
                    case '\r': output.write('\\'); output.write('r'); break;
                    case '\t': output.write('\\'); output.write('t'); break;
                    case '\f': output.write('\\'); output.write('f'); break;
                    case 0: throw new IOException("NUL in launch argument");
                    default: output.write(ch);
                }
            }
            output.write('"');
            output.write('\n');
        }
    }
}
