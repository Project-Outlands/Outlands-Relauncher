package top.outlands.relauncher;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
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
import com.formdev.flatlaf.util.UIScale;

import top.outlands.relauncher.RelaunchMessages.Message;
import top.outlands.relauncher.RelaunchOrchestrator.AuthorSettingsSaver;
import top.outlands.relauncher.RelaunchOrchestrator.InstallAccess;
import top.outlands.relauncher.RelaunchOrchestrator.JavaSelectionValidator;
import top.outlands.relauncher.RelaunchOrchestrator.ReleaseChoices;

final class RelaunchUi implements RelaunchOrchestrator.Interaction {
    @Override
    public boolean canConfigure() {
        return !GraphicsEnvironment.isHeadless();
    }

    @Override
    public LaunchSettings configure(LaunchSettings initial, Message initialError,
            RelaunchOrchestrator.SettingsValidator validator, JavaSelectionValidator javaValidator,
            PackAuthorSettings policy, AuthorSettingsSaver authorSaver, ReleaseChoices releases,
            InstallAccess installation) throws InterruptedException {
        if (!canConfigure()) {
            return null;
        }
        RelaunchMessages messages = RelaunchMessages.display();
        return showModal(() -> new SettingsDialog(messages, initial, initialError, validator, javaValidator,
                policy, authorSaver, releases, installation), dialog -> dialog.result);
    }

    @Override
    public void fallback(Message reason) {
        RelaunchMessages english = new RelaunchMessages(Locale.ENGLISH);
        RelaunchOrchestrator.diagnostic(english.text(reason) + " " + english.text("fallback.continue"));
        if (!Thread.currentThread().isInterrupted() && canConfigure()) {
            try {
                RelaunchMessages messages = RelaunchMessages.display();
                String ok = messages.text("button.ok");
                showModal(() -> new JOptionPane(messages.text(reason) + "\n" + messages.text("fallback.continue"),
                        JOptionPane.WARNING_MESSAGE, JOptionPane.DEFAULT_OPTION, null,
                        new Object[] {ok}, ok).createDialog(null, messages.text("fallback.title")),
                        dialog -> null);
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
            Runnable restoreScale = WindowsUiScale.applyIfNeeded();
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
                    restoreScale.run();
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

    static JFileChooser createChooser(RelaunchMessages messages) {
        JFileChooser chooser = new JFileChooser();
        if (!chooser.getLocale().equals(messages.locale())) {
            chooser.setLocale(messages.locale());
            chooser.updateUI();
        }
        return chooser;
    }

    @SuppressWarnings("serial")
    static final class SettingsDialog extends JDialog {
        private static final int ROW_COLUMN_GAP = 8;

        private final RelaunchMessages messages;
        private final LaunchSettings initial;
        private final PackAuthorSettings openingPolicy;
        private PackAuthorSettings authorSettings;
        private final JTextField javaPath = field("javaExecutable", 28);
        private final JLabel outlandsStatus = new JLabel();
        private final JButton installButton = new JButton();
        private final ReleaseChoices releases;
        private final InstallAccess installation;
        private InstalledPack installed;
        private final JTextField proxy = field("proxy", 28);
        private final JTextField mavenMirror = field("mavenMirror", 28);
        private final JTextField librariesDirectory = field("librariesDirectory", 28);
        private final HeapInput xms;
        private final HeapInput xmx;
        private final JTextArea jvmArgs = new JTextArea(5, 28);
        private final JTextArea status = new JTextArea(2, 34);
        private final JCheckBox automatic;
        private final JButton launch;
        private final JButton forge;
        private final JToggleButton advancedToggle;
        private final JButton browseJava;
        private final JButton browseLibraries;
        private final JButton clearLibraries;
        private final JButton authorButton;
        private final RelaunchOrchestrator.SettingsValidator validator;
        private SwingWorker<Void, Void> validation;
        private Dimension sizeBeforeAdvanced;
        private LaunchSettings result;

        SettingsDialog(RelaunchMessages messages, LaunchSettings initial, Message initialError,
                       RelaunchOrchestrator.SettingsValidator validator, JavaSelectionValidator javaValidator,
                       PackAuthorSettings policy, AuthorSettingsSaver authorSaver, ReleaseChoices releases,
                       InstallAccess installation) {
            super((Frame) null, messages.text("settings.title"), Dialog.ModalityType.APPLICATION_MODAL);
            this.messages = messages;
            this.initial = initial;
            this.openingPolicy = policy;
            this.authorSettings = policy;
            this.releases = releases;
            this.installation = installation;
            this.installed = installation.initial;
            automatic = new JCheckBox(messages.text("settings.automatic"));
            launch = new JButton(messages.text("button.launch"));
            forge = new JButton(messages.text("button.continue"));
            advancedToggle = new JToggleButton(messages.text("advanced.show"));
            browseJava = new JButton(messages.text("button.browse"));
            browseLibraries = new JButton(messages.text("button.browse"));
            clearLibraries = new JButton(messages.text("button.clear"));
            authorButton = new JButton(messages.text("author.title"));
            authorButton.setName("packAuthorSettings");
            authorButton.addActionListener(event -> editAuthorSettings(authorSaver));
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
            javaPath.setEditable(false);
            proxy.setText(initial.proxy);
            mavenMirror.setText(initial.mavenMirror);
            librariesDirectory.setText(initial.librariesDirectory);
            librariesDirectory.setEditable(false);
            xms = new HeapInput("Xms", initial.xms, messages);
            xmx = new HeapInput("Xmx", initial.xmx, messages);
            jvmArgs.setName("jvmArgs");
            jvmArgs.setText(initial.jvmArgs);
            jvmArgs.setCaretPosition(0);
            automatic.setName("automatic");
            automatic.setSelected(initial.automatic);
            automatic.setMnemonic(KeyEvent.VK_T);

            JPanel basics = new JPanel(new GridBagLayout());
            addRow(basics, 0, label(messages.text("field.java"), KeyEvent.VK_J, javaPath), javaPath, browseJava);
            addRow(basics, 1, label(messages.text("field.xms"), KeyEvent.VK_I, xms.field()), xms, null);
            addRow(basics, 2, label(messages.text("field.xmx"), KeyEvent.VK_M, xmx.field()), xmx, null);
            addNote(basics, 3, messages.text("help.heap"));
            outlandsStatus.setName("outlandsStatus");
            installButton.setName("installOutlands");
            installButton.addActionListener(event -> openInstallation());
            addRow(basics, 4, label(messages.text("install.status.label"), KeyEvent.VK_O, installButton),
                    outlandsStatus, installButton);
            browseJava.getAccessibleContext().setAccessibleName(messages.text("access.java"));
            browseJava.addActionListener(event -> {
                JFileChooser chooser = fileChooser("chooser.java.title", "chooser.java.filter", "exe");
                String path = selectedPath(javaPath, chooser);
                if (path != null) {
                    validateAsync("validation.checkingJava", () -> javaValidator.validate(path), () -> javaPath.setText(path));
                }
            });

            JPanel advanced = new JPanel(new GridBagLayout());
            addRow(advanced, 0, label(messages.text("field.proxy"), KeyEvent.VK_U, proxy), proxy, null);
            addNote(advanced, 1, messages.text("help.proxy"));
            addRow(advanced, 2, label(messages.text("field.mirror"), KeyEvent.VK_V, mavenMirror), mavenMirror, null);
            addNote(advanced, 3, messages.text("help.mirror"));
            JPanel libraryButtons = new JPanel(new BorderLayout(ROW_COLUMN_GAP, 0));
            libraryButtons.add(browseLibraries, BorderLayout.LINE_START);
            libraryButtons.add(clearLibraries, BorderLayout.LINE_END);
            addRow(advanced, 4, label(messages.text("field.libraries"), KeyEvent.VK_D, librariesDirectory), librariesDirectory,
                    libraryButtons);
            addNote(advanced, 5, messages.text("help.libraries"));
            addRow(advanced, 6, label(messages.text("field.options"), KeyEvent.VK_E, jvmArgs), new JScrollPane(jvmArgs), null);
            addNote(advanced, 7, messages.text(policy.lockJvmArguments ? "author.jvmLocked" : "help.options"));
            JPanel authorActions = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
            authorActions.add(authorButton);
            GridBagConstraints authorRow = new GridBagConstraints();
            authorRow.gridx = 0;
            authorRow.gridy = 8;
            authorRow.gridwidth = GridBagConstraints.REMAINDER;
            authorRow.anchor = GridBagConstraints.LINE_START;
            authorRow.insets = new Insets(UIScale.scale(8), 0, 0, 0);
            advanced.add(authorActions, authorRow);
            // Keep keyboard tab traversal consistent with the other input fields.
            jvmArgs.setFocusTraversalKeysEnabled(true);
            jvmArgs.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS,
                    Collections.singleton(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0)));
            jvmArgs.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS,
                    Collections.singleton(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK)));
            browseLibraries.getAccessibleContext().setAccessibleName(messages.text("access.libraries.browse"));
            browseLibraries.addActionListener(event -> {
                JFileChooser chooser = createChooser(messages);
                chooser.setDialogTitle(messages.text("chooser.libraries.title"));
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                choosePath(librariesDirectory, chooser);
            });
            clearLibraries.getAccessibleContext().setAccessibleName(messages.text("access.libraries.clear"));
            clearLibraries.addActionListener(event -> librariesDirectory.setText(""));

            JPanel toggle = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
            advancedToggle.setMnemonic(KeyEvent.VK_A);
            advancedToggle.setName("advancedToggle");
            if (policy.lockAdvanced) advancedToggle.setText(messages.text("author.advancedLocked"));
            toggle.add(advancedToggle);
            JPanel options = new JPanel(new BorderLayout(0, 8));
            options.add(toggle, BorderLayout.NORTH);
            options.add(advanced, BorderLayout.CENTER);
            advanced.setVisible(false);
            advancedToggle.addActionListener(event -> {
                boolean expanded = advancedToggle.isSelected();
                advancedToggle.setText(messages.text(expanded ? "advanced.hide" : "advanced.show"));
                advanced.setVisible(expanded);
                options.revalidate();
                options.repaint();
                resizeForAdvancedSettings(expanded);
            });

            JPanel body = new JPanel(new BorderLayout(0, 8));
            body.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
            JPanel header = new JPanel(new BorderLayout(0, 4));
            JLabel heading = new JLabel(messages.text("settings.heading"));
            heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize2D() + 5));
            header.add(heading, BorderLayout.NORTH);
            header.add(new JLabel(messages.text("settings.warning")),
                    BorderLayout.CENTER);
            header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
            body.add(header, BorderLayout.NORTH);
            body.add(basics, BorderLayout.CENTER);
            body.add(options, BorderLayout.SOUTH);
            JScrollPane bodyScroll = new JScrollPane(body);
            bodyScroll.setBorder(BorderFactory.createEmptyBorder());
            bodyScroll.getVerticalScrollBar().setUnitIncrement(16);

            status.setName("validationStatus");
            status.getAccessibleContext().setAccessibleName(messages.text("access.validation"));
            status.setEditable(false);
            status.setLineWrap(true);
            status.setWrapStyleWord(true);
            showStatus(initialError == null ? "" : messages.text(initialError));

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
            setEditing(true);
            pack();
            Dimension limit = screenSizeLimit();
            setSize(Math.min(getWidth(), limit.width), Math.min(getHeight(), limit.height));
            setMinimumSize(new Dimension(Math.min(getWidth(), 440), Math.min(getHeight(), 300)));
            setLocationRelativeTo(null);
        }

        private void resizeForAdvancedSettings(boolean expanded) {
            Dimension current = getSize();
            Dimension target = sizeBeforeAdvanced;
            if (expanded) {
                // Capture the current compact size, including any manual resize since the last toggle.
                sizeBeforeAdvanced = current;
                Dimension preferred = getPreferredSize();
                Dimension limit = screenSizeLimit();
                target = new Dimension(Math.max(current.width, Math.min(preferred.width, limit.width)),
                        Math.max(current.height, Math.min(preferred.height, limit.height)));
            }
            if (!current.equals(target)) {
                setSize(target);
                setLocationRelativeTo(null);
            }
        }

        private Dimension screenSizeLimit() {
            GraphicsConfiguration screen = getGraphicsConfiguration();
            Rectangle bounds = screen.getBounds();
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(screen);
            return new Dimension((bounds.width - insets.left - insets.right) * 9 / 10,
                    (bounds.height - insets.top - insets.bottom) * 4 / 5);
        }

        private JFileChooser fileChooser(String title, String description, String... extensions) {
            JFileChooser chooser = createChooser(messages);
            chooser.setDialogTitle(messages.text(title));
            chooser.setFileFilter(new FileNameExtensionFilter(messages.text(description), extensions));
            return chooser;
        }

        private void choosePath(JTextField field, JFileChooser chooser) {
            String path = selectedPath(field, chooser);
            if (path != null) field.setText(path);
        }

        private String selectedPath(JTextField field, JFileChooser chooser) {
            File current = new File(field.getText());
            if (current.isAbsolute()) {
                chooser.setSelectedFile(current);
            }
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && isDisplayable()) {
                return chooser.getSelectedFile().getAbsolutePath();
            }
            return null;
        }

        private void editAuthorSettings(AuthorSettingsSaver saver) {
            JCheckBox advancedLock = new JCheckBox(messages.text("author.lockAdvanced"), authorSettings.lockAdvanced);
            JCheckBox jvmLock = new JCheckBox(messages.text("author.lockJvm"), authorSettings.lockJvmArguments);
            advancedLock.setEnabled(!authorSettings.lockAdvanced);
            jvmLock.setEnabled(!authorSettings.lockJvmArguments);
            JTextField minimum = new JTextField(Integer.toString(authorSettings.minimumJava), 6);
            JLabel minimumLabel = new JLabel(messages.text("author.minimumJava"));
            minimumLabel.setLabelFor(minimum);
            JPanel version = new JPanel(new FlowLayout(FlowLayout.LEADING, UIScale.scale(6), 0));
            version.add(minimumLabel);
            version.add(minimum);
            JTextArea help = new JTextArea(messages.text("author.help", PackAuthorSettings.MINIMUM_JAVA), 4, 36);
            help.setEditable(false);
            help.setFocusable(false);
            help.setOpaque(false);
            help.setFont(minimumLabel.getFont());
            help.setLineWrap(true);
            help.setWrapStyleWord(true);
            JLabel error = new JLabel();
            String save = messages.text("author.save");
            String cancel = messages.text("author.cancel");
            Object[] form = {advancedLock, jvmLock, version, help, error};
            while (JOptionPane.showOptionDialog(this, form, messages.text("author.title"),
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, new Object[] {save, cancel}, save) == 0) {
                final PackAuthorSettings updated;
                try {
                    updated = new PackAuthorSettings(advancedLock.isSelected(), jvmLock.isSelected(),
                            Integer.parseInt(minimum.getText().trim()));
                } catch (IllegalArgumentException invalid) {
                    error.setText(messages.text("author.invalidMinimum", PackAuthorSettings.MINIMUM_JAVA));
                    continue;
                }
                try {
                    saver.save(updated);
                    authorSettings = updated;
                    showStatus(messages.text("author.saved"));
                } catch (IOException failed) {
                    RelaunchOrchestrator.diagnostic("Could not save the pack author settings.");
                    showStatus(messages.text("author.saveFailed"));
                }
                return;
            }
        }

        private void openInstallation() {
            if (!installButton.isEnabled()) return;
            if (javaPath.getText().trim().isEmpty()) {
                showStatus(messages.text("install.selectJava"));
                browseJava.requestFocusInWindow();
                return;
            }
            InstallRequest suggestion = installed == null ? installation.suggested : installed.source;
            InstallationDialog dialog = new InstallationDialog(this, messages, readSettings(), releases,
                    installation, suggestion, openingPolicy.lockAdvanced);
            dialog.setVisible(true);
            if (dialog.result() != null && isDisplayable()) {
                installed = dialog.result();
                showStatus("");
                updateInstallationControls(true);
            }
        }

        private LaunchSettings readSettings() {
            boolean locked = openingPolicy.lockAdvanced;
            String extra = locked || openingPolicy.lockJvmArguments ? initial.jvmArgs : jvmArgs.getText();
            return new LaunchSettings(javaPath.getText(), initial.localInstaller,
                    xms.field().getText(), xmx.field().getText(), extra, automatic.isSelected(), initial.localMmcPack,
                    initial.releaseVersion, locked ? initial.proxy : proxy.getText().trim(),
                    locked ? initial.mavenMirror : mavenMirror.getText().trim(),
                    locked ? initial.librariesDirectory : librariesDirectory.getText());
        }

        private void validateSettings() {
            if (validation != null || installed == null) return;
            LaunchSettings candidate = readSettings().withSource(installed.source);
            validateAsync("validation.checking", () -> validator.validate(candidate), () -> {
                result = candidate;
                dispose();
            });
        }

        private interface ValidationTask {
            void run() throws IOException, InterruptedException;
        }

        private void validateAsync(String statusKey, ValidationTask task, Runnable success) {
            showStatus(messages.text(statusKey));
            validation = new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws IOException, InterruptedException {
                    task.run();
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
                        showStatus("");
                        success.run();
                    } catch (ExecutionException failure) {
                        showStatus(messages.text(RelaunchMessages.validation(failure.getCause())));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        showStatus(messages.text("validation.interrupted"));
                    }
                    setEditing(true);
                }
            };
            setEditing(false);
            validation.execute();
        }

        private void setEditing(boolean enabled) {
            xms.setInputEnabled(enabled);
            xmx.setInputEnabled(enabled);
            for (JComponent component : new JComponent[] {javaPath, automatic, browseJava}) {
                component.setEnabled(enabled);
            }
            boolean advancedEditable = enabled && !openingPolicy.lockAdvanced;
            for (JComponent component : new JComponent[] {advancedToggle, proxy,
                    mavenMirror, librariesDirectory, browseLibraries, clearLibraries, authorButton}) {
                component.setEnabled(advancedEditable);
            }
            jvmArgs.setEnabled(advancedEditable && !openingPolicy.lockJvmArguments);
            updateInstallationControls(enabled);
        }

        private void updateInstallationControls(boolean editing) {
            boolean present = installed != null;
            outlandsStatus.setText(present
                    ? messages.text("install.status.installed", installed.version,
                            InstallationDialog.sourceLabel(messages, installed.source.kind))
                    : messages.text("install.status.missing"));
            outlandsStatus.setToolTipText(present ? installed.source.selection : null);
            installButton.setText(messages.text(present ? "install.button.change" : "install.button.install"));
            installButton.setEnabled(editing && (!present || !openingPolicy.lockAdvanced));
            launch.setEnabled(editing && present);
        }

        private void showStatus(String message) {
            status.setText(message);
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

        private static void addRow(JPanel panel, int row, JLabel label, JComponent field, JComponent browse) {
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridx = 0;
            constraints.gridy = row;
            constraints.anchor = GridBagConstraints.FIRST_LINE_START;
            // Keep each help note close to its field; put the extra space above the next field.
            int top = UIScale.scale(row == 0 ? 3 : 10);
            int bottom = UIScale.scale(3);
            constraints.insets = new Insets(top, 0, bottom, ROW_COLUMN_GAP);
            panel.add(label, constraints);
            constraints.gridx = 1;
            constraints.weightx = 1;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            panel.add(field, constraints);
            if (browse != null) {
                constraints.gridx = 2;
                constraints.weightx = 0;
                constraints.fill = GridBagConstraints.NONE;
                constraints.insets = new Insets(top, 0, bottom, 0);
                panel.add(browse, constraints);
            }
        }

        private static void addNote(JPanel panel, int row, String text) {
            addNote(panel, row, new JLabel(text));
        }

        private static void addNote(JPanel panel, int row, JLabel note) {
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridy = row;
            constraints.gridx = 0;
            constraints.gridwidth = GridBagConstraints.REMAINDER;
            constraints.anchor = GridBagConstraints.LINE_START;
            constraints.insets = new Insets(0, 0, 3, 0);
            panel.add(note, constraints);
        }
    }
}
