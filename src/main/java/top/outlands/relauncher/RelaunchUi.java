package top.outlands.relauncher;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

final class RelaunchUi implements RelaunchOrchestrator.Interaction {
    private static final String TITLE = "Outlands Relauncher";

    @Override
    public boolean confirm() throws InterruptedException {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }
        return onEdt(() -> JOptionPane.showOptionDialog(null,
                "Launch this Minecraft instance with Outlands instead of Forge?\n"
                        + "Outlands will use this instance's mods and saves. Back up important worlds first.\n"
                        + "If the game process starts, future launches will switch automatically.", TITLE,
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                new String[] {"Launch Outlands", "Continue Forge"}, "Continue Forge") == 0);
    }

    @Override
    public Path pickJava() throws InterruptedException {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        return onEdt(() -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select the Java 25 x64 bin/java.exe");
            chooser.setFileFilter(new FileNameExtensionFilter("Java executable (*.exe)", "exe"));
            return chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION
                    ? chooser.getSelectedFile().toPath().toAbsolutePath().normalize() : null;
        });
    }

    @Override
    public void fallback(String reason) {
        RelaunchOrchestrator.diagnostic(reason + " Continuing with Forge.");
        if (!Thread.currentThread().isInterrupted() && !GraphicsEnvironment.isHeadless()) {
            try {
                onEdt(() -> {
                    JOptionPane.showMessageDialog(null, reason + "\nContinuing with Forge.", TITLE,
                            JOptionPane.WARNING_MESSAGE);
                    return null;
                });
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException unavailable) {
                RelaunchOrchestrator.diagnostic("Could not show the fallback dialog.");
            }
        }
    }

    private static <T> T onEdt(Callable<T> action) throws InterruptedException {
        FutureTask<T> task = new FutureTask<>(action);
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                task.run();
            } else {
                SwingUtilities.invokeAndWait(task);
            }
            return task.get();
        } catch (InterruptedException interrupted) {
            task.cancel(false);
            throw interrupted;
        } catch (InvocationTargetException | ExecutionException failure) {
            throw new IllegalStateException("Outlands relaunch dialog failed", failure);
        }
    }
}
