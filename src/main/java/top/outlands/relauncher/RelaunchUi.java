package top.outlands.relauncher;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatSystemProperties;

final class RelaunchUi implements RelaunchOrchestrator.Interaction {
    private static final String TITLE = "Outlands Relauncher";

    @Override
    public boolean canConfigure() {
        return !GraphicsEnvironment.isHeadless();
    }

    @Override
    public LaunchSettings configure(LaunchSettings initial, String initialError,
            RelaunchOrchestrator.SettingsValidator validator) throws InterruptedException {
        if (!canConfigure()) {
            return null;
        }
        return showModal(() -> new SettingsDialog(initial, initialError, validator), dialog -> dialog.result);
    }

    @Override
    public void fallback(String reason) {
        RelaunchOrchestrator.diagnostic(reason + " Continuing with Forge.");
        if (!Thread.currentThread().isInterrupted() && canConfigure()) {
            try {
                showModal(() -> new JOptionPane(reason + "\nContinuing with Forge.", JOptionPane.WARNING_MESSAGE)
                        .createDialog(null, TITLE), dialog -> null);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException unavailable) {
                RelaunchOrchestrator.diagnostic("Could not show the fallback dialog.");
            }
        }
    }

    private static <D extends JDialog, T> T showModal(Supplier<D> create,
            Function<D, T> result) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        AtomicReference<D> openDialog = new AtomicReference<>();
        FutureTask<T> task = new FutureTask<>(() -> {
            LookAndFeel previous = UIManager.getLookAndFeel();
            String nativeSetting = System.getProperty(FlatSystemProperties.USE_NATIVE_LIBRARY);
            try {
                System.setProperty(FlatSystemProperties.USE_NATIVE_LIBRARY, "false");
                if (!FlatDarkLaf.setup()) {
                    RelaunchOrchestrator.diagnostic("Could not apply the Outlands window theme; using the existing theme.");
                }
                D dialog = create.get();
                openDialog.set(dialog);
                try {
                    dialog.setVisible(true);
                    return result.apply(dialog);
                } finally {
                    dialog.dispose();
                    openDialog.set(null);
                }
            } finally {
                try {
                    UIManager.setLookAndFeel(previous);
                } catch (UnsupportedLookAndFeelException restoreFailure) {
                    RelaunchOrchestrator.diagnostic("Could not restore the original desktop theme.");
                } finally {
                    if (nativeSetting == null) System.clearProperty(FlatSystemProperties.USE_NATIVE_LIBRARY);
                    else System.setProperty(FlatSystemProperties.USE_NATIVE_LIBRARY, nativeSetting);
                }
            }
        });
        if (SwingUtilities.isEventDispatchThread()) {
            // A modal dialog runs a secondary event loop; never wait for a queued task on the EDT.
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
        try {
            return task.get();
        } catch (InterruptedException interrupted) {
            task.cancel(false);
            SwingUtilities.invokeLater(() -> {
                D dialog = openDialog.get();
                if (dialog != null) {
                    dialog.dispose();
                }
            });
            throw interrupted;
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Outlands relaunch dialog failed", failure.getCause());
        }
    }

    @SuppressWarnings("serial")
    private static final class SettingsDialog extends JDialog {
        private final JTextField javaPath = field("javaExecutable", 28);
        private final JTextField installer = field("localInstaller", 28);
        private final JTextField xms = field("xms", 12);
        private final JTextField xmx = field("xmx", 12);
        private final JTextArea jvmArgs = new JTextArea(5, 28);
        private final JTextArea status = new JTextArea(2, 34);
        private final JCheckBox automatic = new JCheckBox("Automatically launch Outlands next time");
        private final JButton launch = new JButton("Launch Outlands");
        private final JButton forge = new JButton("Continue Forge");
        private final JToggleButton advancedToggle = new JToggleButton("Show advanced settings");
        private final JButton browseJava = new JButton("Browse...");
        private final JButton browseInstaller = new JButton("Browse...");
        private final RelaunchOrchestrator.SettingsValidator validator;
        private SwingWorker<Void, Void> validation;
        private LaunchSettings result;

        SettingsDialog(LaunchSettings initial, String initialError, RelaunchOrchestrator.SettingsValidator validator) {
            super((Frame) null, "Outlands Launch Settings", Dialog.ModalityType.APPLICATION_MODAL);
            this.validator = validator;
            setName("outlandsLaunchSettings");
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            setResizable(true);
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent event) {
                    dispose();
                }
            });
            getRootPane().registerKeyboardAction(event -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    JComponent.WHEN_IN_FOCUSED_WINDOW);

            javaPath.setText(initial.javaExecutable);
            installer.setText(initial.localInstaller);
            xms.setText(initial.xms);
            xmx.setText(initial.xmx);
            jvmArgs.setName("jvmArgs");
            jvmArgs.setText(initial.jvmArgs);
            jvmArgs.setCaretPosition(0);
            automatic.setName("automatic");
            automatic.setSelected(initial.automatic);
            automatic.setMnemonic(KeyEvent.VK_T);

            JPanel basics = new JPanel(new GridBagLayout());
            addRow(basics, 0, label("Java 25 x64:", KeyEvent.VK_J, javaPath), javaPath, browseJava);
            addRow(basics, 1, label("Initial heap (Xms):", KeyEvent.VK_I, xms), xms, null);
            addRow(basics, 2, label("Maximum heap (Xmx):", KeyEvent.VK_M, xmx), xmx, null);
            addNote(basics, 3, "Heap example: 2G. Leave blank to use Forge's setting or the JVM default.");
            browseJava.getAccessibleContext().setAccessibleName("Browse for Java executable");
            browseJava.addActionListener(event -> browse(javaPath, "Select Java 25 x64 (java.exe)",
                    "Java executable (*.exe)", "exe"));

            JPanel advanced = new JPanel(new GridBagLayout());
            addRow(advanced, 0, label("Local installer:", KeyEvent.VK_S, installer), installer, browseInstaller);
            addNote(advanced, 1, "Leave blank to use the default Outlands release.");
            addRow(advanced, 2, label("Extra JVM options:", KeyEvent.VK_E, jvmArgs), new JScrollPane(jvmArgs), null);
            addNote(advanced, 3, "Enter one complete JVM option per line, without shell quoting.");
            // Keep keyboard tab traversal consistent with the other input fields.
            jvmArgs.setFocusTraversalKeysEnabled(true);
            jvmArgs.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS,
                    Collections.singleton(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0)));
            jvmArgs.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS,
                    Collections.singleton(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK)));
            browseInstaller.getAccessibleContext().setAccessibleName("Browse for a local Outlands installer");
            browseInstaller.addActionListener(event -> browse(installer, "Select an Outlands installer JAR",
                    "Outlands installer (*.jar)", "jar"));

            JPanel toggle = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
            advancedToggle.setMnemonic(KeyEvent.VK_A);
            advancedToggle.setName("advancedToggle");
            toggle.add(advancedToggle);
            JPanel options = new JPanel(new BorderLayout(0, 8));
            options.add(toggle, BorderLayout.NORTH);
            options.add(advanced, BorderLayout.CENTER);
            advanced.setVisible(false);
            advancedToggle.addActionListener(event -> {
                boolean expanded = advancedToggle.isSelected();
                advancedToggle.setText(expanded ? "Hide advanced settings" : "Show advanced settings");
                advanced.setVisible(expanded);
                options.revalidate();
                options.repaint();
                if (expanded) {
                    Dimension preferred = getPreferredSize();
                    int screenHeight = getGraphicsConfiguration().getBounds().height;
                    int height = Math.max(getHeight(), Math.min(preferred.height, screenHeight * 4 / 5));
                    if (height != getHeight()) {
                        setSize(getWidth(), height);
                        setLocationRelativeTo(null);
                    }
                }
            });

            JPanel body = new JPanel(new BorderLayout(0, 8));
            body.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
            JPanel header = new JPanel(new BorderLayout(0, 4));
            JLabel heading = new JLabel("Outlands launch settings");
            heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize2D() + 5));
            header.add(heading, BorderLayout.NORTH);
            header.add(new JLabel("Outlands uses this instance's mods and saves. Back up important worlds first."),
                    BorderLayout.CENTER);
            header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
            body.add(header, BorderLayout.NORTH);
            body.add(basics, BorderLayout.CENTER);
            body.add(options, BorderLayout.SOUTH);
            JScrollPane bodyScroll = new JScrollPane(body);
            bodyScroll.setBorder(BorderFactory.createEmptyBorder());
            bodyScroll.getVerticalScrollBar().setUnitIncrement(16);

            status.setName("validationStatus");
            status.getAccessibleContext().setAccessibleName("Settings validation status");
            status.setEditable(false);
            status.setLineWrap(true);
            status.setWrapStyleWord(true);
            showStatus(initialError);

            launch.setName("launchOutlands");
            launch.setMnemonic(KeyEvent.VK_L);
            launch.addActionListener(event -> validateSettings());
            forge.setName("continueForge");
            forge.setMnemonic(KeyEvent.VK_C);
            forge.addActionListener(event -> dispose());
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));
            actions.add(forge);
            actions.add(launch);
            JPanel footer = new JPanel(new BorderLayout(0, 8));
            footer.add(automatic, BorderLayout.NORTH);
            footer.add(new JScrollPane(status), BorderLayout.CENTER);
            footer.add(actions, BorderLayout.SOUTH);

            JPanel content = new JPanel(new BorderLayout(0, 10));
            content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
            content.add(bodyScroll, BorderLayout.CENTER);
            content.add(footer, BorderLayout.SOUTH);
            setContentPane(content);
            getRootPane().setDefaultButton(launch);
            pack();
            setMinimumSize(new Dimension(Math.min(getWidth(), 440), Math.min(getHeight(), 300)));
            setLocationRelativeTo(null);
        }

        private void browse(JTextField field, String title, String description, String extension) {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle(title);
            chooser.setFileFilter(new FileNameExtensionFilter(description, extension));
            File current = new File(field.getText());
            if (current.isAbsolute()) {
                chooser.setSelectedFile(current);
            }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && isDisplayable()) {
                field.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        }

        private void validateSettings() {
            if (validation != null) {
                return;
            }
            LaunchSettings candidate = new LaunchSettings(javaPath.getText(), installer.getText(), xms.getText(),
                    xmx.getText(), jvmArgs.getText(), automatic.isSelected());
            setEditing(false);
            showStatus("Checking launch settings...");
            validation = new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws IOException, InterruptedException {
                    validator.validate(candidate);
                    return null;
                }

                @Override
                protected void done() {
                    if (validation != this || isCancelled()) {
                        return;
                    }
                    validation = null;
                    try {
                        get();
                        result = candidate;
                        dispose();
                        return;
                    } catch (ExecutionException failure) {
                        Throwable cause = failure.getCause();
                        if (cause instanceof IOException && cause.getMessage() != null
                                && !cause.getMessage().trim().isEmpty()) {
                            showStatus(cause.getMessage());
                        } else if (cause instanceof InterruptedException) {
                            showStatus("Settings validation was interrupted. Try again.");
                        } else {
                            showStatus("Could not check the launch settings. Check the inputs and try again.");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        showStatus("Settings validation was interrupted. Try again.");
                    }
                    setEditing(true);
                }
            };
            validation.execute();
        }

        private void setEditing(boolean enabled) {
            for (JComponent component : new JComponent[] {javaPath, installer, xms, xmx, jvmArgs, automatic,
                    browseJava, browseInstaller, launch}) {
                component.setEnabled(enabled);
            }
        }

        private void showStatus(String message) {
            status.setText(message == null ? "" : message);
            status.setCaretPosition(0);
        }

        @Override
        public void dispose() {
            if (validation != null) {
                SwingWorker<Void, Void> pending = validation;
                validation = null;
                pending.cancel(true);
            }
            super.dispose();
        }

        private static JTextField field(String name, int columns) {
            JTextField field = new JTextField(columns);
            field.setName(name);
            return field;
        }

        private static JLabel label(String text, int mnemonic, JComponent target) {
            JLabel label = new JLabel(text);
            label.setDisplayedMnemonic(mnemonic);
            label.setLabelFor(target);
            return label;
        }

        private static void addRow(JPanel panel, int row, JLabel label, JComponent field, JButton browse) {
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridx = 0;
            constraints.gridy = row;
            constraints.anchor = GridBagConstraints.FIRST_LINE_START;
            constraints.insets = new Insets(3, 0, 3, 8);
            panel.add(label, constraints);
            constraints.gridx = 1;
            constraints.weightx = 1;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            panel.add(field, constraints);
            if (browse != null) {
                constraints.gridx = 2;
                constraints.weightx = 0;
                constraints.fill = GridBagConstraints.NONE;
                constraints.insets = new Insets(3, 0, 3, 0);
                panel.add(browse, constraints);
            }
        }

        private static void addNote(JPanel panel, int row, String text) {
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridy = row;
            constraints.gridx = 0;
            constraints.gridwidth = GridBagConstraints.REMAINDER;
            constraints.anchor = GridBagConstraints.LINE_START;
            constraints.insets = new Insets(0, 0, 3, 0);
            panel.add(new JLabel(text), constraints);
        }
    }
}
