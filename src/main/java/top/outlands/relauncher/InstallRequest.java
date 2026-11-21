package top.outlands.relauncher;

import java.util.Objects;

import top.outlands.relauncher.shared.PackSettings;

/** A chosen source, not yet an installation. Local selections are file paths. */
final class InstallRequest {
    enum Kind { ONLINE, MMC, JAR }

    final Kind kind;
    final String selection;

    InstallRequest(Kind kind, String selection) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.selection = selection.trim();
    }

    static InstallRequest from(LaunchSettings settings) {
        if (!settings.localMmcPack.trim().isEmpty()) return new InstallRequest(Kind.MMC, settings.localMmcPack);
        if (!settings.localInstaller.trim().isEmpty()) return new InstallRequest(Kind.JAR, settings.localInstaller);
        String version = settings.releaseVersion.trim();
        return new InstallRequest(Kind.ONLINE, version.isEmpty() ? PackSettings.DEFAULT_VERSION : version);
    }

    boolean matches(InstallRequest other) {
        return kind == other.kind && selection.equals(other.selection);
    }
}
