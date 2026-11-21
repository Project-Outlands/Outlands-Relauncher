# Outlands Relauncher

Outlands Relauncher is a client-side mod for Minecraft 1.12.2 Forge. It starts Outlands in a separate Java process while keeping the same game directory, mods, saves, resource packs, and login session. It does not change your launcher profile.

## Requirements

- A Minecraft 1.12.2 Forge client instance. Forge can still run on Java 8.
- A separate **Java 25 or newer** installation for Outlands. The mod does not search for or download Java; select it yourself.
- Windows, Linux, or macOS on x86-64 or ARM64. Linux and macOS dependency selection has been tested, but full game launches on those platforms have not yet been verified.

## Install and launch

1. Put `outlandsrelauncher-0.1.0.jar` in the Forge instance's `mods/` directory and start the instance as usual.
2. In Outlands Launch Settings, choose `bin/java.exe` or `bin/javaw.exe` on Windows, or `bin/java` on Linux and macOS.
3. Click **Install** next to **Outlands status**. Choose one installation method:
   - **Online installation:** Wait for the official Outlands Maven version list to load. The newest available release is selected by default; you can select an older preset. If the request fails, the installer shows cached presets and the bundled fallback. An offline install still requires the package and its dependencies to be cached.
   - **Local package:** Choose an Outlands MMC ZIP or Outlands installer JAR. The version is read from the package. A universal JAR or a generic MultiMC/Prism export is not a substitute for an Outlands package.
4. Click **Start installation**. The installer verifies the package, prepares its dependencies, and saves the installation. On success it closes automatically and shows the installed version and method in the main window. It does not start the game. Use **Change** to install a different version or use a different source; a failed change leaves the previous installation in place.
5. Click **Launch Outlands**. Enable **Automatically launch Outlands next time** to skip the settings window on future starts. **Continue Forge** skips the relaunch for the current session.

**Back up important worlds before switching.** Forge and Outlands share the instance's files. Returning to Forge does not undo changes Outlands made to saves or configuration. If preparation fails before Outlands starts, the relauncher can return to Forge. Once the Outlands game process has started, it will not start Forge again automatically.

## Settings

Settings are stored in the instance's `config/outlands-relauncher.properties` file (UTF-8). Relative paths are resolved from the instance's game directory. You can use forward slashes in Windows paths.

| Setting | Behavior |
| --- | --- |
| `mode=ask` | Show the launch settings when Forge starts. This is the default for a fresh instance. |
| `mode=auto` | Launch Outlands automatically using the saved settings. If a setting needs repair, the relauncher can reopen the settings window. |
| `mode=forge` | Skip the relauncher and continue with Forge. |

The settings window lets you adjust the initial and maximum heap (`Xms` and `Xmx`). Leave either value blank to inherit that heap setting from Forge, if present, or let the JVM choose its default. Enter extra JVM options one per line in **Advanced Settings**. Forge-specific tweakers, Java agents, and unrelated JVM arguments are not carried over. Advanced Settings also lets you configure a proxy, a Maven Central mirror, and a read-only shared library directory.

### Pack author settings

Open **Advanced Settings → Pack Author Settings** to lock advanced settings or just the extra JVM options, or to raise the minimum Java version above 25. Heap settings stay adjustable. When advanced settings are locked, players can install the configured source for the first time but cannot change an existing installation through the UI.

Locks take effect the next time the settings window opens. To unlock an option, set the corresponding `author.lockAdvanced` or `author.lockJvmArguments` key to `false` in `config/outlands-relauncher.properties`. The Java minimum is stored in `author.minimumJava`.

## Instance files

| Path | Contents |
| --- | --- |
| `outlands/installation.properties` | The installed version and installation method. An online install may leave only this file in `outlands/`. |
| `outlands/` | Managed copies of locally installed MMC ZIPs or installer JARs. After a successful installation, the relauncher no longer needs the original file. |
| `.outlands-relauncher/cache/` | Verified online packages and dependencies, plus the cached release list. |
| `.outlands-relauncher/runs/` | Temporary launch arguments and extracted native libraries. Per-run files are cleaned up when the process ends. |

## Build and test

Run the Gradle wrapper with JDK 25:

```sh
# Linux and macOS
./gradlew test build

# Windows
gradlew.bat test build
```

The distributable JAR is `build/libs/outlandsrelauncher-0.1.0.jar`. Do not install the `-dev.jar`, `-plain.jar`, or `-sources.jar` in a production instance.

For a manual smoke test, use a clean Forge 1.12.2 instance and a disposable world. Check online installation, both local package formats, automatic relaunch, session and save reuse, and the **Continue Forge** path. To verify offline behavior, repeat the launch with a complete cache and the network disconnected.

## Acknowledgments

The public feature description of **Cleanroom Relauncher Unofficial** served as a feature reference. No implementation code from either the unofficial or official Cleanroom Relauncher was used.
