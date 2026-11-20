package top.outlands.relauncher.bootstrap;

import java.io.IOException;
import java.io.InputStream;

import top.outlands.foundation.boot.Foundation;
import top.outlands.relauncher.shared.SessionWire;

/** The tiny game JVM entry point; Foundation is not initialized until the parent hands off. */
public final class ChildMain {
    private ChildMain() {
    }

    public static void main(String[] ignored) {
        final String[] gameArguments;
        InputStream input = System.in;
        try {
            gameArguments = SessionWire.read(input);
        } catch (IOException e) {
            System.err.println("[Outlands Relauncher] Could not receive the game arguments: " + e.getClass().getSimpleName());
            System.exit(1);
            return;
        }
        ParentPipe.watch(input);
        Foundation.main(gameArguments);
    }
}
