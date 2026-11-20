package top.outlands.relauncher.bootstrap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Keeps an isolated child tied to Forge even when the launcher forcibly terminates Forge. */
final class ParentPipe {
    private ParentPipe() {
    }

    static void watch(InputStream pipe) {
        // The launcher's pipe is not a console. Game code must not consume or close it.
        System.setIn(new ByteArrayInputStream(new byte[0]));
        Thread monitor = new Thread(() -> {
            int status;
            try {
                status = pipe.read() == -1 ? 1 : 2;
            } catch (IOException disconnected) {
                status = 1; // Windows can signal closure as a broken pipe instead of EOF.
            }
            // Losing the owner means a forced stop; do not wait on game shutdown hooks.
            Runtime.getRuntime().halt(status);
        }, "Outlands parent connection");
        monitor.setDaemon(true);
        monitor.start();
    }
}
