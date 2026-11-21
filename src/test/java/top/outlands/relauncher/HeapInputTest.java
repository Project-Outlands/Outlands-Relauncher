package top.outlands.relauncher;

import java.awt.Component;
import java.awt.Rectangle;
import java.util.Locale;
import javax.swing.JLabel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeapInputTest {
    @Test
    void sliderWritesJvmSizesAndClearsTheFieldAtDefault() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HeapInput input = new HeapInput("Xms", "", new RelaunchMessages(Locale.ENGLISH));
            JSlider slider = slider(input);
            assertEquals(0, slider.getValue());
            assertTrue(slider.getSnapToTicks());
            assertEquals(1, slider.getMinorTickSpacing());
            slider.setValue(1);
            assertEquals("512M", input.field().getText());
            slider.setValue(3);
            assertEquals("1536M", input.field().getText());
            slider.setValue(4);
            assertEquals("2G", input.field().getText());
            slider.setValue(slider.getMaximum());
            assertEquals("16G", input.field().getText());
            slider.setValue(0);
            assertEquals("", input.field().getText());
        });
    }

    @Test
    void customTextPositionsTheThumbWithoutChangingTheInput() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HeapInput input = new HeapInput("Xmx", "1792m", new RelaunchMessages(Locale.ENGLISH));
            JSlider slider = slider(input);
            assertEquals(4, slider.getValue());
            assertEquals("1792m", input.field().getText());
            input.field().setText("48g");
            assertEquals(slider.getMaximum(), slider.getValue());
            assertEquals("48g", input.field().getText());
            input.field().setText("1G");
            assertEquals(2, slider.getValue());
            input.field().setText("invalid");
            assertEquals("invalid", input.field().getText());
            input.field().setText("  ");
            assertEquals(0, slider.getValue());
            assertEquals("  ", input.field().getText());
        });
    }

    @Test
    void bothControlsDisableDuringValidationAndDefaultLabelIsLocalized() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HeapInput input = new HeapInput("Xmx", "", new RelaunchMessages(Locale.SIMPLIFIED_CHINESE));
            JSlider slider = slider(input);
            assertEquals("默认", ((JLabel) slider.getLabelTable().get(0)).getText());
            input.setInputEnabled(false);
            assertFalse(input.field().isEnabled());
            assertFalse(slider.isEnabled());
            input.setInputEnabled(true);
            assertTrue(input.field().isEnabled());
            assertTrue(slider.isEnabled());
        });
    }

    @Test
    void sliderTakesTheExtraWidthBesideTheShortTextField() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HeapInput input = new HeapInput("Xms", "1G", new RelaunchMessages(Locale.ENGLISH));
            JSlider slider = slider(input);
            int fieldWidth = input.field().getPreferredSize().width;
            input.setSize(input.getPreferredSize());
            input.doLayout();
            input.field().getParent().doLayout();
            int sliderWidth = slider.getWidth();

            input.setSize(input.getWidth() + 80, input.getHeight());
            input.doLayout();
            input.field().getParent().doLayout();
            Rectangle fieldBounds = SwingUtilities.convertRectangle(input.field().getParent(),
                    input.field().getBounds(), input);
            assertEquals(fieldWidth, fieldBounds.width);
            assertTrue(fieldBounds.x + fieldBounds.width < slider.getX());
            assertEquals(sliderWidth + 80, slider.getWidth());
        });
    }

    private static JSlider slider(HeapInput input) {
        for (Component component : input.getComponents()) {
            if (component instanceof JSlider) return (JSlider) component;
        }
        throw new AssertionError("Missing heap slider");
    }
}
