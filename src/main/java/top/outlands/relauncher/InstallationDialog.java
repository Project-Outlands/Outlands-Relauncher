package top.outlands.relauncher;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;

import top.outlands.relauncher.RelaunchOrchestrator.InstallAccess;
import top.outlands.relauncher.RelaunchOrchestrator.ReleaseChoices;
import top.outlands.relauncher.shared.PackSettings;
import top.outlands.relauncher.shared.ValidationException;

@SuppressWarnings("serial")
final class InstallationDialog extends JDialog {
    private final RelaunchMessages messages;
    private final LaunchSettings settings;
    private final ReleaseChoices releases;
    private final InstallAccess installation;
    private final InstallRequest suggested;
    private final boolean locked;
    private final JRadioButton onlineMethod;
    private final JRadioButton localMethod;
    private final JComboBox<String> releaseVersion = new JComboBox<>();
    private final JTextField localPackage = new JTextField(28);
    private final JButton browse;
    private final JButton refresh;
    private final JButton install;
    private final JButton cancel;
    private final JLabel releaseNote = new JLabel();
    private final JTextArea status = new JTextArea(3, 32);
    private final JProgressBar progressBar = new JProgressBar();
    private final CardLayout methods = new CardLayout();
    private final JPanel source = new JPanel(methods);
    private SwingWorker<List<String>, Void> releaseRefresh;
    private boolean attemptedRefresh;
    private InstallWorker worker;
    private InstalledPack result;

    InstallationDialog(Window owner, RelaunchMessages messages, LaunchSettings settings, ReleaseChoices releases,
            InstallAccess installation, InstallRequest suggested, boolean locked) {
        super(owner, messages.text("install.title"), Dialog.ModalityType.DOCUMENT_MODAL);
        this.messages = messages;
        this.settings = settings;
        this.releases = releases;
        this.installation = installation;
        this.suggested = suggested;
        this.locked = locked;
        setName("outlandsInstallation");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent event) { refreshOnFirstOnlineUse(); }
            @Override public void windowClosing(WindowEvent event) { cancel(); }
        });
        getRootPane().registerKeyboardAction(event -> cancel(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        onlineMethod = new JRadioButton(messages.text("install.method.online"));
        onlineMethod.setName("onlineMethod");
        localMethod = new JRadioButton(messages.text("install.method.local"));
        localMethod.setName("localMethod");
        browse = new JButton(messages.text("button.browse"));
        browse.setName("browsePackage");
        browse.getAccessibleContext().setAccessibleName(messages.text("access.package"));
        browse.addActionListener(event -> browsePackage());
        refresh = new JButton(messages.text("button.refreshReleases"));
        refresh.setName("refreshReleases");
        refresh.addActionListener(event -> refreshReleaseChoices());
        install = new JButton(messages.text("install.button.start"));
        install.setName("startInstallation");
        install.addActionListener(event -> startInstallation());
        cancel = new JButton(messages.text("author.cancel"));
        cancel.setName("cancelInstallation");
        cancel.addActionListener(event -> cancel());
        releaseVersion.setName("releaseVersion");
        releaseVersion.setEditable(false);
        localPackage.setName("localOutlandsPackage");
        localPackage.setEditable(false);
        boolean suggestedLocal = suggested != null && suggested.kind != InstallRequest.Kind.ONLINE;
        if (suggestedLocal) localPackage.setText(suggested.selection);

        JPanel form = new JPanel(new BorderLayout(0, 8));
        if (locked) {
            if (suggested != null) {
                JTextField selection = new JTextField(suggested.selection, 28);
                selection.setEditable(false);
                selection.setCaretPosition(0);
                form.add(sourceRow(sourceLabel(messages, suggested.kind), selection, null));
            }
        } else {
            replaceReleaseChoices(releases.presets, suggestedLocal || suggested == null ? null : suggested.selection);
            releaseNote.setText(messages.text("help.release"));
            JPanel online = new JPanel(new BorderLayout(0, 6));
            online.add(sourceRow(messages.text("field.release"), releaseVersion, refresh), BorderLayout.NORTH);
            online.add(releaseNote, BorderLayout.CENTER);
            source.add(online, "online");
            source.add(sourceRow(messages.text("field.package"), localPackage, browse), "local");
            ButtonGroup group = new ButtonGroup();
            group.add(onlineMethod);
            group.add(localMethod);
            (suggestedLocal ? localMethod : onlineMethod).setSelected(true);
            onlineMethod.addActionListener(event -> changeMethod());
            localMethod.addActionListener(event -> changeMethod());
            JPanel choices = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 0));
            choices.add(onlineMethod);
            choices.add(localMethod);
            form.add(choices, BorderLayout.NORTH);
            form.add(source, BorderLayout.CENTER);
            methods.show(source, suggestedLocal ? "local" : "online");
        }

        status.setName("installationStatus");
        status.getAccessibleContext().setAccessibleName(messages.text("access.validation"));
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        progressBar.setName("installationProgress");
        JPanel feedback = new JPanel(new BorderLayout(0, 6));
        feedback.add(new JScrollPane(status), BorderLayout.CENTER);
        feedback.add(progressBar, BorderLayout.SOUTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));
        actions.add(cancel);
        actions.add(install);
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        content.add(form, BorderLayout.NORTH);
        content.add(feedback, BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(install);
        updateControls();
        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(owner);
    }

    InstalledPack result() {
        return result;
    }

    static String sourceLabel(RelaunchMessages messages, InstallRequest.Kind kind) {
        switch (kind) {
            case ONLINE: return messages.text("install.source.online");
            case MMC: return messages.text("install.source.mmc");
            case JAR: return messages.text("install.source.jar");
            default: throw new AssertionError(kind);
        }
    }

    private static JPanel sourceRow(String text, JComponent input, JButton action) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        JLabel label = new JLabel(text);
        label.setLabelFor(input);
        row.add(label, BorderLayout.LINE_START);
        row.add(input, BorderLayout.CENTER);
        if (action != null) row.add(action, BorderLayout.LINE_END);
        return row;
    }

    private void changeMethod() {
        methods.show(source, onlineMethod.isSelected() ? "online" : "local");
        updateControls();
        refreshOnFirstOnlineUse();
    }

    private void updateControls() {
        boolean editable = !locked && worker == null;
        boolean online = editable && onlineMethod.isSelected();
        onlineMethod.setEnabled(editable);
        localMethod.setEnabled(editable);
        releaseVersion.setEnabled(online);
        refresh.setEnabled(online && releaseRefresh == null);
        localPackage.setEnabled(editable && localMethod.isSelected());
        browse.setEnabled(localPackage.isEnabled());
        install.setEnabled(worker == null);
    }

    private void browsePackage() {
        JFileChooser chooser = RelaunchUi.createChooser(messages);
        chooser.setDialogTitle(messages.text("chooser.package.title"));
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(messages.text("chooser.package.filter"), "zip", "jar"));
        File current = new File(localPackage.getText());
        if (current.isAbsolute()) chooser.setSelectedFile(current);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && isDisplayable()) {
            localPackage.setText(chooser.getSelectedFile().getAbsolutePath());
            localPackage.setCaretPosition(0);
        }
    }

    private void refreshOnFirstOnlineUse() {
        if (!attemptedRefresh && isShowing()) refreshReleaseChoices();
    }

    private void replaceReleaseChoices(List<String> versions, String selected) {
        releaseVersion.setModel(new DefaultComboBoxModel<>(versions.toArray(new String[0])));
        if (selected != null && versions.contains(selected)) {
            releaseVersion.setSelectedItem(selected);
        } else if (versions.contains(PackSettings.DEFAULT_VERSION)) {
            releaseVersion.setSelectedItem(PackSettings.DEFAULT_VERSION);
        }
    }

    private void refreshReleaseChoices() {
        if (!refresh.isEnabled()) return;
        attemptedRefresh = true;
        releaseNote.setText(messages.text("help.releaseLoading"));
        releaseRefresh = new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws IOException, InterruptedException {
                return releases.loader.refresh(settings.proxy);
            }

            @Override protected void done() {
                if (releaseRefresh != this) return;
                releaseRefresh = null;
                try {
                    replaceReleaseChoices(get(), (String) releaseVersion.getSelectedItem());
                    releaseNote.setText(messages.text("help.release"));
                } catch (ExecutionException failed) {
                    releaseNote.setText(messages.text("help.releaseOffline"));
                    RelaunchOrchestrator.diagnostic("Could not refresh the Outlands release presets.");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    releaseNote.setText(messages.text("help.releaseOffline"));
                }
                updateControls();
            }
        };
        updateControls();
        releaseRefresh.execute();
    }

    private InstallRequest selectedRequest() {
        if (locked) return suggested;
        if (onlineMethod.isSelected()) {
            String version = (String) releaseVersion.getSelectedItem();
            return version == null ? null : new InstallRequest(InstallRequest.Kind.ONLINE, version);
        }
        String path = localPackage.getText();
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) return new InstallRequest(InstallRequest.Kind.MMC, path);
        if (lower.endsWith(".jar")) return new InstallRequest(InstallRequest.Kind.JAR, path);
        return null;
    }

    private void startInstallation() {
        if (worker != null) return;
        InstallRequest request = selectedRequest();
        if (request == null || request.selection.trim().isEmpty()) {
            showStatus(messages.text("install.selectSource"));
            return;
        }
        worker = new InstallWorker(request);
        updateControls();
        showStatus(messages.text("install.phase.verify"));
        progressBar.setIndeterminate(true);
        worker.execute();
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setCaretPosition(0);
    }

    private void showProgress(InstallProgress progress) {
        switch (progress.phase) {
            case "verify":
            case "dependencies":
            case "save":
                showStatus(messages.text("install.phase." + progress.phase));
                break;
            default:
                break;
        }
        progressBar.setIndeterminate(progress.total <= 0);
        if (progress.total > 0) {
            progressBar.setMaximum(progress.total);
            progressBar.setValue(progress.done);
        }
    }

    private void cancel() {
        if (worker == null) {
            dispose();
        } else {
            worker.requestCancellation();
            cancel.setEnabled(false);
            showStatus(messages.text("install.canceling"));
        }
    }

    @Override public void dispose() {
        if (releaseRefresh != null) {
            SwingWorker<List<String>, Void> pending = releaseRefresh;
            releaseRefresh = null;
            pending.cancel(true);
        }
        if (worker != null) {
            worker.requestCancellation();
            worker = null;
        }
        super.dispose();
    }

    private final class InstallWorker extends SwingWorker<InstalledPack, InstallProgress> {
        private final InstallRequest request;
        private Thread runningThread;
        private boolean cancellationRequested;

        InstallWorker(InstallRequest request) {
            this.request = request;
        }

        // SwingWorker.cancel would call done before the installer has stopped touching disk.
        synchronized void requestCancellation() {
            if (cancellationRequested) return;
            cancellationRequested = true;
            if (runningThread != null) runningThread.interrupt();
        }

        @Override protected InstalledPack doInBackground() throws IOException, InterruptedException {
            synchronized (this) {
                if (cancellationRequested) throw new InterruptedException();
                runningThread = Thread.currentThread();
            }
            try {
                return Objects.requireNonNull(installation.install(settings.withSource(request), request, this::publish));
            } finally {
                synchronized (this) {
                    runningThread = null;
                }
            }
        }

        @Override protected void process(List<InstallProgress> updates) {
            if (worker == this && !cancellationRequested && !updates.isEmpty()) {
                showProgress(updates.get(updates.size() - 1));
            }
        }

        @Override protected void done() {
            if (worker != this) return;
            worker = null;
            try {
                result = get();
                // A cancellation arriving after commit must not leave the parent with stale status.
                dispose();
                return;
            } catch (ExecutionException failed) {
                if (cancellationRequested) {
                    dispose();
                    return;
                }
                Throwable cause = failed.getCause();
                if (cause instanceof ValidationException || cause instanceof InterruptedException) {
                    showStatus(messages.text(RelaunchMessages.validation(cause)));
                } else {
                    String detail = cause instanceof IOException ? cause.getLocalizedMessage() : null;
                    showStatus(detail == null || detail.isEmpty() ? messages.text("fallback.prepare")
                            : messages.text("install.failed", detail));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                showStatus(messages.text("validation.interrupted"));
            }
            progressBar.setIndeterminate(false);
            progressBar.setValue(0);
            cancel.setEnabled(true);
            updateControls();
        }
    }
}
