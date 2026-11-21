package top.outlands.relauncher;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.util.Locale;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class RelaunchUiTest {
    @Test
    void hidingAdvancedSettingsRestoresTheSizeBeforeEachExpansion() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "A Window needs a graphics display");
        SwingUtilities.invokeAndWait(() -> {
            RelaunchUi.SettingsDialog dialog = new RelaunchUi.SettingsDialog(new RelaunchMessages(Locale.ENGLISH),
                    new LaunchSettings("", "", "", "", "", false), null, candidate -> {}, path -> {},
                    PackAuthorSettings.DEFAULT, policy -> {});
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
                        new PackAuthorSettings(lockAdvanced, true, 25), policy -> {});
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
