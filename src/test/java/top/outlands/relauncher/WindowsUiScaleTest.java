package top.outlands.relauncher;

import org.junit.jupiter.api.Test;

import java.awt.Font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WindowsUiScaleTest {
    @Test
    void suppliesAnExactHintWhenWindowsFontsAreUndersized() {
        assertEquals("120dpi", WindowsUiScale.scaleHint(120, font(12), font(12)));
        assertEquals("144dpi", WindowsUiScale.scaleHint(144, font(12), font(12)));
        assertEquals("168dpi", WindowsUiScale.scaleHint(168, null, null));
        assertEquals("192dpi", WindowsUiScale.scaleHint(192, font(24), font(12)));
        assertEquals("144dpi", WindowsUiScale.scaleHint(144, font(12), font(18)));
    }

    @Test
    void leavesStandardOrUnavailableDpiAlone() {
        assertNull(WindowsUiScale.scaleHint(96, font(12), font(12)));
        assertNull(WindowsUiScale.scaleHint(0, font(12), font(12)));
    }

    @Test
    void leavesAlreadyScaledFontsAndLargerAccessibilityTextAlone() {
        assertNull(WindowsUiScale.scaleHint(144, font(18), font(18)));
        assertNull(WindowsUiScale.scaleHint(144, null, font(18)));
        assertNull(WindowsUiScale.scaleHint(144, font(12), font(21)));
    }

    private static Font font(int size) {
        return new Font(Font.DIALOG, Font.PLAIN, size);
    }
}
