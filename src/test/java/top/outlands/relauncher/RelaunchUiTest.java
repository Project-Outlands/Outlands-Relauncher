package top.outlands.relauncher;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import top.outlands.relauncher.RelaunchOrchestrator.ReleaseChoices;
import top.outlands.relauncher.shared.PackSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class RelaunchUiTest {
    private static final List<String> PRESETS = Arrays.asList("2026.11.20", PackSettings.DEFAULT_VERSION);
    private static final ReleaseChoices RELEASES = new ReleaseChoices(PRESETS, proxy -> PRESETS);

    @Test
    void hidingAdvancedSettingsRestoresTheSizeBeforeEachExpansion() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A Window needs a graphics display");
        SwingUtilities.invokeAndWait(() -> {
            RelaunchUi.SettingsDialog dialog = new RelaunchUi.SettingsDialog(new RelaunchMessages(Locale.ENGLISH),
                    new LaunchSettings("", "", "", "", "", false), null, candidate -> {}, path -> {},
                    PackAuthorSettings.DEFAULT, policy -> {}, RELEASES);
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
                RelaunchUi.SettingsDialog dialog = new RelaunchUi.SettingsDialog(new RelaunchMessages(Locale.ENGLISH),
                        new LaunchSettings("", "", "", "", "-Dauthor=yes", false), null, candidate -> {}, path -> {},
                        new PackAuthorSettings(lockAdvanced, true, 25), policy -> {}, RELEASES);
                try {
                    assertEquals(!lockAdvanced, named(dialog, "advancedToggle").isEnabled());
                    assertFalse(named(dialog, "jvmArgs").isEnabled());
                    assertEquals(!lockAdvanced, named(dialog, "packAuthorSettings").isEnabled());
                    assertTrue(named(dialog, "xms").isEnabled());
                    assertTrue(named(dialog, "xmx").isEnabled());
                    assertTrue(named(dialog, "launchOutlands").isEnabled());
                } finally {
                    dialog.dispose();
                }
            }
        });
    }

    @Test
    void releasePickerIsReadOnlyAndDisabledForLocalPackages() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A Window needs a graphics display");
        SwingUtilities.invokeAndWait(() -> {
            RelaunchUi.SettingsDialog dialog = new RelaunchUi.SettingsDialog(new RelaunchMessages(Locale.ENGLISH),
                    new LaunchSettings("", "", "", "", "", false, "", "2099.12.31", "", "", ""),
                    null, candidate -> {}, path -> {}, PackAuthorSettings.DEFAULT, policy -> {}, RELEASES);
            try {
                JComboBox<?> release = (JComboBox<?>) named(dialog, "releaseVersion");
                JTextField pack = (JTextField) named(dialog, "localOutlandsPackage");
                assertFalse(release.isEditable());
                assertEquals(PRESETS.size(), release.getItemCount());
                assertEquals(PackSettings.DEFAULT_VERSION, release.getSelectedItem());
                release.setSelectedItem("2099.12.31");
                assertEquals(PackSettings.DEFAULT_VERSION, release.getSelectedItem());
                release.setSelectedItem(PRESETS.get(0));
                for (String extension : Arrays.asList("zip", "jar")) {
                    pack.setText("local." + extension);
                    assertFalse(release.isEnabled());
                    assertFalse(named(dialog, "refreshReleases").isEnabled());
                }
                pack.setText("");
                assertTrue(release.isEnabled());
                assertEquals(PRESETS.get(0), release.getSelectedItem());
            } finally {
                dialog.dispose();
            }
        });
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
