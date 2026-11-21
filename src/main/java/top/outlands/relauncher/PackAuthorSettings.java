package top.outlands.relauncher;

/** Policy set by the pack author, independent of a player's launch choices. */
final class PackAuthorSettings {
    static final int MINIMUM_JAVA = 25;
    static final PackAuthorSettings DEFAULT = new PackAuthorSettings(false, false, MINIMUM_JAVA);

    final boolean lockAdvanced;
    final boolean lockJvmArguments;
    final int minimumJava;

    PackAuthorSettings(boolean lockAdvanced, boolean lockJvmArguments, int minimumJava) {
        if (minimumJava < MINIMUM_JAVA) {
            throw new IllegalArgumentException("Minimum Java must be at least " + MINIMUM_JAVA);
        }
        this.lockAdvanced = lockAdvanced;
        this.lockJvmArguments = lockJvmArguments;
        this.minimumJava = minimumJava;
    }

    boolean locks(LaunchSettings.Field field) {
        switch (field) {
            case INSTALLER:
            case MMC:
            case RELEASE:
            case PROXY:
            case MIRROR:
            case LIBRARIES:
                return lockAdvanced;
            case JVM_ARGS:
                return lockAdvanced || lockJvmArguments;
            default:
                return false;
        }
    }
}
