package top.outlands.relauncher;

/** Editable values shared by the setup window and the instance configuration. */
final class LaunchSettings {
    final String javaExecutable;
    final String localInstaller;
    final String xms;
    final String xmx;
    final String jvmArgs;
    final boolean automatic;
    final String localMmcPack;
    final String releaseVersion;
    final String proxy;
    final String mavenMirror;
    final String librariesDirectory;

    LaunchSettings(String javaExecutable, String localInstaller, String xms, String xmx, String jvmArgs,
                   boolean automatic) {
        this(javaExecutable, localInstaller, xms, xmx, jvmArgs, automatic, "", "", "", "", "");
    }

    LaunchSettings(String javaExecutable, String localInstaller, String xms, String xmx, String jvmArgs,
                   boolean automatic, String localMmcPack, String releaseVersion, String proxy, String mavenMirror,
                   String librariesDirectory) {
        this.javaExecutable = javaExecutable;
        this.localInstaller = localInstaller;
        this.xms = xms;
        this.xmx = xmx;
        this.jvmArgs = jvmArgs;
        this.automatic = automatic;
        this.localMmcPack = localMmcPack;
        this.releaseVersion = releaseVersion;
        this.proxy = proxy;
        this.mavenMirror = mavenMirror;
        this.librariesDirectory = librariesDirectory;
    }

    LaunchSettings withSource(InstallRequest source) {
        return new LaunchSettings(javaExecutable, source.kind == InstallRequest.Kind.JAR ? source.selection : "",
                xms, xmx, jvmArgs, automatic, source.kind == InstallRequest.Kind.MMC ? source.selection : "",
                source.kind == InstallRequest.Kind.ONLINE ? source.selection : "", proxy, mavenMirror, librariesDirectory);
    }

    LaunchSettings withArchive(String path) {
        boolean mmc = !localMmcPack.trim().isEmpty();
        return new LaunchSettings(javaExecutable, mmc ? "" : path, xms, xmx, jvmArgs, automatic,
                mmc ? path : "", releaseVersion, proxy, mavenMirror, librariesDirectory);
    }

    enum Field { JAVA, INSTALLER, MMC, XMS, XMX, JVM_ARGS, RELEASE, PROXY, MIRROR, LIBRARIES }

    LaunchSettings clear(Field field) {
        return new LaunchSettings(field == Field.JAVA ? "" : javaExecutable,
                field == Field.INSTALLER ? "" : localInstaller,
                field == Field.XMS ? "" : xms, field == Field.XMX ? "" : xmx,
                field == Field.JVM_ARGS ? "" : jvmArgs, automatic,
                field == Field.MMC ? "" : localMmcPack, field == Field.RELEASE ? "" : releaseVersion,
                field == Field.PROXY ? "" : proxy, field == Field.MIRROR ? "" : mavenMirror,
                field == Field.LIBRARIES ? "" : librariesDirectory);
    }

    String value(Field field) {
        switch (field) {
            case JAVA: return javaExecutable;
            case INSTALLER: return localInstaller;
            case MMC: return localMmcPack;
            case XMS: return xms;
            case XMX: return xmx;
            case JVM_ARGS: return jvmArgs;
            case RELEASE: return releaseVersion;
            case PROXY: return proxy;
            case MIRROR: return mavenMirror;
            case LIBRARIES: return librariesDirectory;
            default: throw new AssertionError(field);
        }
    }
}
