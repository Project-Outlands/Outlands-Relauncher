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

    LaunchSettings withJava(String executable) {
        return new LaunchSettings(executable, localInstaller, xms, xmx, jvmArgs, automatic, localMmcPack, releaseVersion,
                proxy, mavenMirror, librariesDirectory);
    }
}
