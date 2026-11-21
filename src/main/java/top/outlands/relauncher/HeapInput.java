package top.outlands.relauncher;

import java.awt.BorderLayout;
import java.util.Hashtable;
import java.util.Locale;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.formdev.flatlaf.util.UIScale;

import top.outlands.relauncher.shared.JvmOptions;
import top.outlands.relauncher.shared.ValidationException;

/** An editable JVM heap size with a slider for common values. The text is authoritative. */
@SuppressWarnings("serial")
final class HeapInput extends JPanel {
    private static final int STEP_MIB = 512;
    private static final int MAX_GIB = 16;
    private static final int MAX_STEP = MAX_GIB * 1024 / STEP_MIB;
    private static final long STEP_BYTES = (long) STEP_MIB << 20;

    private final JTextField field = new JTextField(7);
    private final JSlider slider = new JSlider(0, MAX_STEP, 0);
    private boolean updating;

    HeapInput(String option, String initial, RelaunchMessages messages) {
        super(new BorderLayout(UIScale.scale(10), 0));
        String name = option.toLowerCase(Locale.ROOT);
        field.setName(name);
        field.setText(initial);
        slider.setName(name + "Slider");
        slider.getAccessibleContext().setAccessibleName(messages.text("field." + name));
        String help = messages.text("heap.slider.help");
        slider.getAccessibleContext().setAccessibleDescription(help);
        slider.setToolTipText(help);
        slider.setMinorTickSpacing(1);
        slider.setMajorTickSpacing(4 * 1024 / STEP_MIB);
        slider.setSnapToTicks(true);
        slider.setPaintTicks(true);
        slider.setPaintLabels(true);
        Hashtable<Integer, JLabel> labels = new Hashtable<>();
        labels.put(0, new JLabel(messages.text("heap.slider.default")));
        for (int gib = 4; gib <= MAX_GIB; gib += 4) {
            labels.put(gib * 1024 / STEP_MIB, new JLabel(gib + "G"));
        }
        slider.setLabelTable(labels);

        updateSlider(option);
        slider.addChangeListener(event -> {
            if (updating) return;
            int mib = slider.getValue() * STEP_MIB;
            updating = true;
            try {
                field.setText(mib == 0 ? "" : mib % 1024 == 0 ? (mib / 1024) + "G" : mib + "M");
            } finally {
                updating = false;
            }
        });
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { updateSlider(option); }
            @Override public void removeUpdate(DocumentEvent event) { updateSlider(option); }
            @Override public void changedUpdate(DocumentEvent event) { updateSlider(option); }
        });

        JPanel entry = new JPanel(new BorderLayout());
        entry.add(field, BorderLayout.NORTH);
        add(entry, BorderLayout.LINE_START);
        add(slider, BorderLayout.CENTER);
    }

    JTextField field() {
        return field;
    }

    void setInputEnabled(boolean enabled) {
        field.setEnabled(enabled);
        slider.setEnabled(enabled);
    }

    private void updateSlider(String option) {
        if (updating) return;
        String text = field.getText().trim();
        int step = 0;
        if (!text.isEmpty()) {
            try {
                long bytes = JvmOptions.heapBytes(option, text);
                long clamped = Math.min(bytes, STEP_BYTES * MAX_STEP);
                step = Math.max(1, (int) ((clamped + STEP_BYTES / 2) / STEP_BYTES));
            } catch (ValidationException invalid) {
                // Partial or invalid text stays editable and is validated when launching.
                return;
            }
        }
        updating = true;
        try {
            slider.setValue(step);
        } finally {
            updating = false;
        }
    }
}
