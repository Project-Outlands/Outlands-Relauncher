package top.outlands.relauncher.shared;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The Outlands-only JVM options accepted from the settings editor and by the bootstrap. */
public final class JvmOptions {
    public static final String FILE_NAME = "jvm-options.txt";
    public static final int MAX_BYTES = 32 * 1024;
    private static final int MAX_OPTIONS = 64;
    private static final Pattern HEAP = Pattern.compile("([0-9]{1,19})([kKmMgGtT]?)");
    private static final Pattern PROPERTY = Pattern.compile("-D([a-zA-Z0-9_.-]+)(?:=.*)?");
    private static final Pattern TUNING = Pattern.compile("-XX:(?:[+-]([A-Za-z][A-Za-z0-9]*)|([A-Za-z][A-Za-z0-9]*)=.+)");
    private static final Pattern MODULE = Pattern.compile("--add-(?:opens|exports|reads)=[A-Za-z0-9_.$/,=+-]+");
    private static final Set<String> RESERVED_PROPERTIES = new HashSet<>(Arrays.asList(
            "java.class.path", "java.library.path", "java.system.class.loader", "java.home", "java.io.tmpdir",
            "java.specification.version", "file.encoding", "native.encoding", "sun.jnu.encoding", "user.dir",
            "sun.java.command", "os.name", "os.arch"));
    private static final Set<String> RESERVED_TUNING = new HashSet<>(Arrays.asList(
            "Flags", "VMOptionsFile", "OnError", "OnOutOfMemoryError", "PrintFlagsInitial", "PrintFlagsWithComments",
            "PrintSharedArchiveAndExit", "VerifySharedSpaces", "IgnoreUnrecognizedVMOptions",
            "InitialHeapSize", "MinHeapSize", "MaxHeapSize"));

    private JvmOptions() {}

    public static List<String> resolve(String xms, String xmx, String extra, List<String> inherited) throws IOException {
        List<String> options = new ArrayList<>();
        appendHeap(options, "Xms", xms, inherited);
        appendHeap(options, "Xmx", xmx, inherited);
        if (extra.length() > MAX_BYTES) throw new IOException("Extra JVM parameters are too long.");
        String[] lines = extra.split("\\r\\n|\\r|\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (hasControlCharacters(lines[i]) || !isExtra(line)) {
                throw new IOException("Unsupported JVM parameter on line " + (i + 1) + ". Use one option per line.");
            }
            options.add(line);
        }
        validate(options);
        return options;
    }

    /** Validates the resolved argument vector again when reading the prepare/launch input file. */
    public static void validate(List<String> options) throws IOException {
        if (options.size() > MAX_OPTIONS) throw new IOException("Too many JVM parameters (maximum 64).");
        int bytes = 0;
        Long minimum = null;
        Long maximum = null;
        for (String option : options) {
            bytes += option.getBytes(StandardCharsets.UTF_8).length + 1;
            if (bytes > MAX_BYTES) throw new IOException("Extra JVM parameters are too long.");
            if (hasControlCharacters(option)) throw new IOException("A JVM parameter contains a control character.");
            if (option.startsWith("-Xms") && minimum == null) {
                minimum = heapBytes("Xms", option.substring(4));
            } else if (option.startsWith("-Xmx") && maximum == null) {
                maximum = heapBytes("Xmx", option.substring(4));
            } else if (!isExtra(option)) {
                throw new IOException("Unsupported or duplicate JVM parameter.");
            }
        }
        if (minimum != null && maximum != null && minimum > maximum) {
            throw new IOException("Initial memory (Xms) must not exceed maximum memory (Xmx).");
        }
    }

    private static void appendHeap(List<String> options, String name, String value, List<String> inherited) throws IOException {
        String effective = value.trim();
        String prefix = "-" + name;
        if (effective.isEmpty()) {
            for (String argument : inherited) {
                if (argument.startsWith(prefix) && HEAP.matcher(argument.substring(prefix.length())).matches()) {
                    effective = argument.substring(prefix.length());
                }
            }
        }
        if (!effective.isEmpty()) {
            heapBytes(name, effective);
            options.add(prefix + effective);
        }
    }

    private static long heapBytes(String name, String value) throws IOException {
        Matcher match = HEAP.matcher(value);
        if (match.matches()) {
            try {
                long amount = Long.parseLong(match.group(1));
                int unit = match.group(2).isEmpty() ? 0 : "kmgt".indexOf(Character.toLowerCase(match.group(2).charAt(0))) + 1;
                if (amount > 0) return Math.multiplyExact(amount, 1L << (unit * 10));
            } catch (ArithmeticException | NumberFormatException overflow) {
                throw new IOException(name + " exceeds the supported memory range.", overflow);
            }
        }
        throw new IOException(name + " must be a positive size, such as 512m or 4g.");
    }

    private static boolean hasControlCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }

    private static boolean isExtra(String option) {
        Matcher property = PROPERTY.matcher(option);
        if (property.matches()) {
            String key = property.group(1);
            return !RESERVED_PROPERTIES.contains(key) && !key.startsWith("outlands.relauncher.")
                    && !key.startsWith("jdk.module.") && !key.startsWith("java.launcher.");
        }
        Matcher tuning = TUNING.matcher(option);
        if (tuning.matches()) {
            String key = tuning.group(1) == null ? tuning.group(2) : tuning.group(1);
            return !RESERVED_TUNING.contains(key);
        }
        return option.startsWith("-Xlog:") && !"-Xlog:help".equals(option)
                || option.matches("-Xss[1-9][0-9]*[kKmMgG]?") || "-Xshare:off".equals(option)
                || option.matches("-ea(?::[A-Za-z0-9_.$]+(?:\\.\\.\\.)?)?")
                || option.matches("-da(?::[A-Za-z0-9_.$]+(?:\\.\\.\\.)?)?") || MODULE.matcher(option).matches();
    }
}
