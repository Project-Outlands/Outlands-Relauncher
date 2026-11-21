package top.outlands.relauncher.shared;

import java.util.Locale;
import java.util.regex.Pattern;

/** The runtime platform used by launcher metadata and by the child JVM. */
public final class LaunchPlatform {
    private final String os;
    private final String arch;
    private final String version;

    private LaunchPlatform(String os, String arch, String version) {
        this.os = os;
        this.arch = arch;
        this.version = version;
    }

    public static LaunchPlatform current() {
        return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""),
                System.getProperty("os.version", ""));
    }

    /** Returns null for operating systems and architectures without supported launch metadata. */
    public static LaunchPlatform detect(String osName, String osArch, String osVersion) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) os = "windows";
        else if (os.equals("linux")) os = "linux";
        else if (os.equals("mac os x") || os.equals("macos") || os.equals("darwin")) os = "osx";
        else return null;
        String arch = canonicalArch(osArch);
        return arch == null ? null : new LaunchPlatform(os, arch, osVersion);
    }

    private static String canonicalArch(String value) {
        switch (value.toLowerCase(Locale.ROOT)) {
            case "amd64": case "x86_64": case "x64": return "x86_64";
            case "aarch64": case "arm64": return "arm64";
            default: return null;
        }
    }

    public String os() { return os; }
    public String arch() { return arch; }
    public String version() { return version; }
    public boolean windows() { return "windows".equals(os); }
    public boolean mac() { return "osx".equals(os); }
    public String pathSeparator() { return windows() ? ";" : ":"; }

    /** Both the generic OS name and a matching MultiMC arch-qualified OS name apply. */
    public boolean matchesOs(String condition) {
        String name = condition.toLowerCase(Locale.ROOT);
        if (name.equals(os)) return true;
        return name.startsWith(os + "-") && arch.equals(canonicalArch(name.substring(os.length() + 1)));
    }

    /** Mojang rules can express architecture as a literal or a regular expression. */
    public boolean matchesArchitecture(String condition) {
        String exact = canonicalArch(condition);
        if (exact != null) return arch.equals(exact);
        Pattern rule = Pattern.compile(condition);
        if ("arm64".equals(arch)) {
            return rule.matcher("aarch64").matches() || rule.matcher("arm64").matches();
        }
        return rule.matcher("amd64").matches() || rule.matcher("x86_64").matches() || rule.matcher("x64").matches();
    }
}
