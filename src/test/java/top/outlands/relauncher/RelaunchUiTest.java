package top.outlands.relauncher;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JRadioButton;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import top.outlands.relauncher.RelaunchOrchestrator.InstallAccess;
import top.outlands.relauncher.RelaunchOrchestrator.ReleaseChoices;
import top.outlands.relauncher.shared.PackSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class RelaunchUiTest {
    private static final RelaunchMessages MESSAGES = new RelaunchMessages(Locale.ENGLISH);
    private static final List<String> PRESETS = Arrays.asList("2026.11.20", PackSettings.DEFAULT_VERSION);
    private static final ReleaseChoices RELEASES = new ReleaseChoices(PRESETS, proxy -> PRESETS);

    private static final InstallRequest OFFICIAL = new InstallRequest(InstallRequest.Kind.ONLINE,
            PackSettings.DEFAULT_VERSION);
    private static final InstalledPack INSTALLED = new InstalledPack(PackSettings.DEFAULT_VERSION, OFFICIAL);

    private static InstallAccess unusedInstaller(InstalledPack initial, InstallRequest suggested) {
        return new InstallAccess(initial, suggested, (settings, request, progress) -> {
            throw new AssertionError("Unexpected installation");
        });
    }

    @Test
    void hidingAdvancedSettingsRestoresTheSizeBeforeEachExpansion() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A Window needs a graphics display");
        SwingUtilities.invokeAndWait(() -> {
            RelaunchUi.SettingsDialog dialog = new RelaunchUi.SettingsDialog(MESSAGES,
                    new LaunchSettings("", "", "", "", "", false), null, candidate -> {}, path -> {},
                    PackAuthorSettings.DEFAULT, policy -> {}, RELEASES, unusedInstaller(null, OFFICIAL));
            try {
                JToggleButton advanced = (JToggleButton) named(dialog, "advancedToggle");
                assertNotNull(advanced);
                Dimension original = dialog.getSize();
                advanced.doClick(0);
                assertTrue(dialog.getHeight() > original.height, "Advanced settings should grow the dialog");
                advanced.doClick(0);
                assertEquals(original, dialog.getSize(), "Hiding advanced settings should shrink the dialog again");

                dialog.setSize(original.width + 24, original.height + 12);
                Dimension manualSize = dialog.getSize();
                advanced.doClick(0);
                advanced.doClick(0);
                assertEquals(manualSize, dialog.getSize(), "Restore the user's size, not just the default size");
            } finally {
                dialog.dispose();
            }
        });
    }

    @Test
    void authorLocksDisableOnlyTheirControls() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A Window needs a graphics display");
        SwingUtilities.invokeAndWait(() -> {
            for (boolean lockAdvanced : new boolean[] {false, true}) {
                RelaunchUi.SettingsDialog dialog = new RelaunchUi.SettingsDialog(MESSAGES,
                        new LaunchSettings("", "", "", "", "-Dauthor=yes", false), null, candidate -> {}, path -> {},
                        new PackAuthorSettings(lockAdvanced, true, 25), policy -> {}, RELEASES,
                        unusedInstaller(INSTALLED, OFFICIAL));
                try {
                    assertEquals(!lockAdvanced, named(dialog, "advancedToggle").isEnabled());
                    assertFalse(named(dialog, "jvmArgs").isEnabled());
                    assertEquals(!lockAdvanced, named(dialog, "packAuthorSettings").isEnabled());
                    assertTrue(named(dialog, "xms").isEnabled());
                    assertTrue(named(dialog, "xmx").isEnabled());
                    assertTrue(named(dialog, "launchOutlands").isEnabled());
                    assertEquals(!lockAdvanced, named(dialog, "installOutlands").isEnabled());
                    assertEquals(MESSAGES.text("install.status.installed", INSTALLED.version,
                            MESSAGES.text("install.source.online")), ((JLabel) named(dialog, "outlandsStatus")).getText());
                    assertNull(named(dialog, "releaseVersion"));
                    assertNull(named(dialog, "localOutlandsPackage"));
                } finally {
                    dialog.dispose();
                }
            }
        });
    }

    @Test
    void missingInstallDisablesLaunchButInstallPointsToJavaEvenWhenLocked() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A Window needs a graphics display");
        SwingUtilities.invokeAndWait(() -> {
            RelaunchUi.SettingsDialog dialog = new RelaunchUi.SettingsDialog(MESSAGES,
                    new LaunchSettings("", "", "", "", "", false), null, candidate -> {}, path -> {},
                    new PackAuthorSettings(true, false, 25), policy -> {}, RELEASES, unusedInstaller(null, OFFICIAL));
            try {
                assertFalse(named(dialog, "launchOutlands").isEnabled());
                assertEquals(MESSAGES.text("install.status.missing"), ((JLabel) named(dialog, "outlandsStatus")).getText());
                JButton install = (JButton) named(dialog, "installOutlands");
                assertTrue(install.isEnabled());
                install.doClick(0);
                assertEquals(MESSAGES.text("install.selectJava"), ((JTextArea) named(dialog, "validationStatus")).getText());
            } finally {
                dialog.dispose();
            }
        });
    }

    @Test
    void onlineOptionsAppearOnlyAfterRefreshAndSelectTheNewestRelease() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A Window needs a graphics display");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        ReleaseChoices slowReleases = new ReleaseChoices(Arrays.asList(PackSettings.DEFAULT_VERSION), proxy -> {
            started.countDown();
            if (!unblock.await(5, TimeUnit.SECONDS)) throw new IOException("Missing test signal");
            return PRESETS;
        });
        InstallRequest local = new InstallRequest(InstallRequest.Kind.MMC, "local.zip");
        AtomicReference<InstallationDialog> opened = new AtomicReference<>();
        CountDownLatch refreshed = new CountDownLatch(1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                InstallationDialog dialog = new InstallationDialog(null, MESSAGES,
                        new LaunchSettings("java", "", "", "", "", false), slowReleases,
                        unusedInstaller(null, local), local, false);
                opened.set(dialog);
                JComboBox<?> release = (JComboBox<?>) named(dialog, "releaseVersion");
                JTextField path = (JTextField) named(dialog, "localOutlandsPackage");
                assertTrue(((JRadioButton) named(dialog, "localMethod")).isSelected());
                assertEquals("local.zip", path.getText());
                assertFalse(path.isEditable());
                assertFalse(release.isVisible());
                assertFalse(named(dialog, "refreshReleases").isEnabled());
                ((JRadioButton) named(dialog, "onlineMethod")).doClick(0);
                assertFalse(named(dialog, "browsePackage").isEnabled());
                assertFalse(named(dialog, "startInstallation").isEnabled());
                requestReleaseRefresh(dialog, refreshed);
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                InstallationDialog dialog = opened.get();
                JComboBox<?> release = (JComboBox<?>) named(dialog, "releaseVersion");
                assertEquals(0, release.getItemCount());
                assertFalse(release.isVisible());
                assertFalse(named(dialog, "startInstallation").isEnabled());
                ((JRadioButton) named(dialog, "localMethod")).doClick(0);
                assertTrue(named(dialog, "browsePackage").isEnabled());
                assertTrue(named(dialog, "startInstallation").isEnabled());
                ((JRadioButton) named(dialog, "onlineMethod")).doClick(0);
                assertFalse(named(dialog, "startInstallation").isEnabled());
            });
            unblock.countDown();
            assertTrue(refreshed.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                InstallationDialog dialog = opened.get();
                JComboBox<?> release = (JComboBox<?>) named(dialog, "releaseVersion");
                assertTrue(release.isVisible());
                assertTrue(release.isEnabled());
                assertFalse(release.isEditable());
                assertEquals(PRESETS.size(), release.getItemCount());
                assertEquals(PRESETS.get(0), release.getSelectedItem());
                release.setSelectedItem("2099.12.31");
                assertEquals(PRESETS.get(0), release.getSelectedItem());
                assertTrue(named(dialog, "startInstallation").isEnabled());
            });
        } finally {
            unblock.countDown();
            if (opened.get() != null) SwingUtilities.invokeAndWait(opened.get()::dispose);
        }
    }

    @Test
    void failedRefreshOnlyRevealsCachedPresetsAfterTheRequestFinishes() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A Window needs a graphics display");
        ReleaseChoices offline = new ReleaseChoices(PRESETS, proxy -> { throw new IOException("offline"); });
        AtomicReference<InstallationDialog> opened = new AtomicReference<>();
        CountDownLatch refreshed = new CountDownLatch(1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                InstallationDialog dialog = new InstallationDialog(null, MESSAGES,
                        new LaunchSettings("java", "", "", "", "", false), offline,
                        unusedInstaller(INSTALLED, OFFICIAL), OFFICIAL, false);
                opened.set(dialog);
                assertFalse(named(dialog, "releaseVersion").isVisible());
                requestReleaseRefresh(dialog, refreshed);
            });
            assertTrue(refreshed.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                JComboBox<?> release = (JComboBox<?>) named(opened.get(), "releaseVersion");
                assertTrue(release.isVisible());
                assertEquals(PRESETS.get(0), release.getSelectedItem());
                assertTrue(named(opened.get(), "startInstallation").isEnabled());
            });
        } finally {
            if (opened.get() != null) SwingUtilities.invokeAndWait(opened.get()::dispose);
        }
    }

    private static void requestReleaseRefresh(InstallationDialog dialog, CountDownLatch refreshed) {
        JButton refresh = (JButton) named(dialog, "refreshReleases");
        assertTrue(refresh.isEnabled());
        refresh.addPropertyChangeListener("enabled", event -> {
            if (Boolean.TRUE.equals(event.getNewValue())) refreshed.countDown();
        });
        refresh.doClick(0);
    }

    @Test
    void lockedInstallUsesExactAuthorSourceAndClosesOnSuccess() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A Window needs a graphics display");
        InstallRequest authorSource = new InstallRequest(InstallRequest.Kind.JAR, "author.jar");
        InstalledPack receipt = new InstalledPack(PackSettings.DEFAULT_VERSION, authorSource);
        AtomicReference<InstallRequest> selected = new AtomicReference<>();
        InstallAccess access = new InstallAccess(null, authorSource, (settings, request, progress) -> {
            assertFalse(SwingUtilities.isEventDispatchThread());
            selected.set(request);
            return receipt;
        });
        withInstaller(access, authorSource, true, dialog -> {
            assertNull(named(dialog, "onlineMethod"));
            assertNull(named(dialog, "localMethod"));
            assertNull(named(dialog, "browsePackage"));
            assertNull(named(dialog, "refreshReleases"));
            ((JButton) named(dialog, "startInstallation")).doClick(0);
        }, dialog -> {
            assertFalse(dialog.isDisplayable());
            assertSame(receipt, dialog.result());
            assertSame(authorSource, selected.get());
        });
    }

    @Test
    void failedChangeStaysOpenAndDoesNotReturnAReplacement() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A Window needs a graphics display");
        InstallAccess access = new InstallAccess(INSTALLED, OFFICIAL, (settings, request, progress) -> {
            throw new IOException("test failure");
        });
        InstallRequest replacement = new InstallRequest(InstallRequest.Kind.MMC, "replacement.zip");
        withInstaller(access, replacement, false, dialog -> ((JButton) named(dialog, "startInstallation")).doClick(0),
                dialog -> {
                    assertTrue(dialog.isDisplayable());
                    assertNull(dialog.result());
                    assertEquals(MESSAGES.text("install.failed", "test failure"),
                            ((JTextArea) named(dialog, "installationStatus")).getText());
                });
    }

    @Test
    void cancelWaitsForInstallerCleanupBeforeClosing() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A Window needs a graphics display");
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch finishCleanup = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        InstallAccess access = new InstallAccess(INSTALLED, OFFICIAL, (settings, request, progress) -> {
            running.countDown();
            try {
                finishCleanup.await();
                throw new AssertionError("Expected cancellation before cleanup");
            } catch (InterruptedException canceled) {
                interrupted.countDown();
                finishCleanup.await();
                throw canceled;
            }
        });
        AtomicReference<InstallationDialog> opened = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                InstallationDialog dialog = new InstallationDialog(null, MESSAGES,
                        new LaunchSettings("java", "", "", "", "", false), RELEASES, access,
                        new InstallRequest(InstallRequest.Kind.MMC, "replacement.zip"), false);
                opened.set(dialog);
                dialog.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosed(WindowEvent event) { closed.countDown(); }
                });
                ((JButton) named(dialog, "startInstallation")).doClick(0);
            });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> ((JButton) named(opened.get(), "cancelInstallation")).doClick(0));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(opened.get().isDisplayable());
                assertFalse(named(opened.get(), "startInstallation").isEnabled());
            });
            finishCleanup.countDown();
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> assertNull(opened.get().result()));
        } finally {
            finishCleanup.countDown();
            if (opened.get() != null) SwingUtilities.invokeAndWait(opened.get()::dispose);
        }
    }

    private static void withInstaller(InstallAccess access, InstallRequest suggested, boolean locked,
            Consumer<InstallationDialog> start,
            Consumer<InstallationDialog> check) throws Exception {
        AtomicReference<InstallationDialog> opened = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                InstallationDialog dialog = new InstallationDialog(null, MESSAGES,
                        new LaunchSettings("java", "", "", "", "", false), RELEASES, access, suggested, locked);
                opened.set(dialog);
                named(dialog, "startInstallation").addPropertyChangeListener("enabled", event -> {
                    if (Boolean.TRUE.equals(event.getNewValue())) finished.countDown();
                });
                dialog.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosed(WindowEvent event) { finished.countDown(); }
                });
                start.accept(dialog);
            });
            assertTrue(finished.await(5, TimeUnit.SECONDS), "Installation completion was not handled by the EDT");
            SwingUtilities.invokeAndWait(() -> check.accept(opened.get()));
        } finally {
            if (opened.get() != null) SwingUtilities.invokeAndWait(opened.get()::dispose);
        }
    }

    private static Component named(Container parent, String name) {
        for (Component component : parent.getComponents()) {
            if (name.equals(component.getName())) return component;
            if (component instanceof Container) {
                Component child = named((Container) component, name);
                if (child != null) return child;
            }
        }
        return null;
    }
}
