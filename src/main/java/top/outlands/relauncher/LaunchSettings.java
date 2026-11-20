package top.outlands.relauncher;

/** Editable values shared by the setup window and the instance configuration. */
final class LaunchSettings {
    final String javaExecutable;
    final String localInstaller;
    final String xms;
    final String xmx;
    final String jvmArgs;
    final boolean automatic;

    LaunchSettings(String javaExecutable, String localInstaller, String xms, String xmx, String jvmArgs,
                   boolean automatic) {
        this.javaExecutable = javaExecutable;
        this.localInstaller = localInstaller;
        this.xms = xms;
        this.xmx = xmx;
        this.jvmArgs = jvmArgs;
        this.automatic = automatic;
    }

    LaunchSettings withJava(String executable) {
        return new LaunchSettings(executable, localInstaller, xms, xmx, jvmArgs, automatic);
    }
}
