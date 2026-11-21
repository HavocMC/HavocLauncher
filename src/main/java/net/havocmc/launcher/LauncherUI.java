package net.havocmc.launcher;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/** HavocLauncher main window. Dark, red-branded, simple: login -> play. */
public final class LauncherUI extends JFrame {
    private static final Color BG = new Color(0x17, 0x17, 0x1A);
    private static final Color PANEL = new Color(0x1E, 0x1E, 0x22);
    private static final Color FG = new Color(0xE8, 0xE8, 0xEA);
    private static final Color MUTED = new Color(0x8A, 0x8A, 0x92);
    private static final Color RED = new Color(0x95, 0x05, 0x02);
    private static final Color RED_BRIGHT = new Color(0xC6, 0x1B, 0x14);

    private final Settings settings;
    private final JLabel accountLabel = new JLabel("Not logged in");
    private final JTextField offlineField = new JTextField(12);
    private final JButton playButton = new JButton("PLAY");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JLabel status = new JLabel("Ready.");
    private final JTextArea log = new JTextArea();
    private final JComboBox<String> ramBox = new JComboBox<>(new String[]{"1 GB", "2 GB", "4 GB", "6 GB", "8 GB"});
    private Process gameProcess;

    LauncherUI(Settings settings) {
        this.settings = settings;
        setTitle("HavocLauncher");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(560, 480));
        setLocationRelativeTo(null);
        buildUi();
        Auth.Account cached = Auth.loadCache();
        if (cached != null) setAccount(cached);
        settings.ramGb = clamp(settings.ramGb, 1, 8);
        ramBox.setSelectedIndex(switch (settings.ramGb) { case 1 -> 0; case 2 -> 1; case 4 -> 2; case 6 -> 3; default -> 4; });
        offlineField.setText(settings.offlineName);
        appendLog("HavocLauncher 0.1.0 ready. Java " + System.getProperty("java.version") + " at launcher.");
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(BG);
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        setContentPane(root);

        // Header
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(BG);
        JLabel title = new JLabel("HAVOC LAUNCHER");
        title.setForeground(RED_BRIGHT);
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        JLabel sub = new JLabel("Minecraft 26.3 + Fabric + HavocClient");
        sub.setForeground(MUTED);
        sub.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        header.add(title, BorderLayout.WEST);
        header.add(sub, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);

        // Center column
        JPanel center = new JPanel();
        center.setLayout(new javax.swing.BoxLayout(center, javax.swing.BoxLayout.Y_AXIS));
        center.setBackground(BG);

        JPanel account = new JPanel(new GridBagLayout());
        account.setBackground(PANEL);
        account.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        accountLabel.setForeground(FG);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 4, 2, 4);
        gc.gridx = 0; gc.gridy = 0; gc.anchor = GridBagConstraints.WEST;
        account.add(accountLabel, gc);
        JButton loginBtn = new JButton("Login with Microsoft");
        styleButton(loginBtn);
        gc.gridx = 1;
        account.add(loginBtn, gc);
        gc.gridx = 2;
        JLabel offlineLabel = new JLabel("  offline:");
        offlineLabel.setForeground(MUTED);
        account.add(offlineLabel, gc);
        offlineField.setBackground(new Color(0x121216));
        offlineField.setForeground(FG);
        offlineField.setCaretColor(FG);
        gc.gridx = 3;
        account.add(offlineField, gc);
        JButton offlineBtn = new JButton("Use offline");
        styleButton(offlineBtn);
        gc.gridx = 4;
        account.add(offlineBtn, gc);
        center.add(account);

        JPanel opts = new JPanel(new FlowLayout(FlowLayout.LEFT));
        opts.setBackground(PANEL);
        opts.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        JLabel ramLabel = new JLabel("Memory:");
        ramLabel.setForeground(FG);
        opts.add(ramLabel);
        opts.add(ramBox);
        JLabel resLabel = new JLabel("  Resolution:");
        resLabel.setForeground(FG);
        opts.add(resLabel);
        JTextField w = new JTextField(String.valueOf(settings.width), 5);
        JTextField h = new JTextField(String.valueOf(settings.height), 5);
        w.setBackground(new Color(0x121216)); w.setForeground(FG); w.setCaretColor(FG);
        h.setBackground(new Color(0x121216)); h.setForeground(FG); h.setCaretColor(FG);
        opts.add(w);
        opts.add(h);
        center.add(opts);

        playButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
        playButton.setBackground(RED);
        playButton.setForeground(Color.WHITE);
        playButton.setFocusPainted(false);
        playButton.setOpaque(true);
        playButton.setBorderPainted(false);
        playButton.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        playButton.addActionListener(e -> play());
        JPanel playPanel = new JPanel(new BorderLayout());
        playPanel.setBackground(BG);
        playPanel.add(playButton, BorderLayout.CENTER);
        center.add(playPanel);

        center.add(new JPanel() {{ setBackground(BG); }});
        root.add(center, BorderLayout.CENTER);

        // Bottom: progress + log
        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.setBackground(BG);
        progress.setStringPainted(true);
        progress.setForeground(RED);
        progress.setBackground(PANEL);
        bottom.add(progress, BorderLayout.NORTH);
        log.setEditable(false);
        log.setBackground(new Color(0x121216));
        log.setForeground(new Color(0xB8, 0xB8, 0xC0));
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane scroll = new JScrollPane(log);
        scroll.setPreferredSize(new Dimension(540, 170));
        scroll.getViewport().setBackground(new Color(0x121216));
        bottom.add(scroll, BorderLayout.CENTER);
        status.setForeground(MUTED);
        bottom.add(status, BorderLayout.SOUTH);
        root.add(bottom, BorderLayout.SOUTH);

        loginBtn.addActionListener(e -> loginMicrosoft());
        offlineBtn.addActionListener(e -> {
            settings.offlineName = offlineField.getText().trim();
            settings.save();
            setAccount(Auth.offline(settings.offlineName));
        });
        ramBox.addActionListener(e -> {
            settings.ramGb = switch (ramBox.getSelectedIndex()) { case 0 -> 1; case 1 -> 2; case 2 -> 4; case 3 -> 6; default -> 8; };
            settings.save();
        });
    }

    private void styleButton(JButton b) {
        b.setBackground(PANEL);
        b.setForeground(FG);
        b.setFocusPainted(false);
        b.setOpaque(true);
        b.setBorderPainted(false);
    }

    private void setAccount(Auth.Account acc) {
        if (acc.offline()) {
            accountLabel.setText("Offline mode: " + acc.name());
        } else {
            accountLabel.setText("Logged in: " + acc.name());
        }
        accountLabel.setForeground(new Color(0x67, 0xC6, 0x6A));
        appendLog("Account set: " + acc.name() + (acc.offline() ? " (offline)" : ""));
    }

    private void loginMicrosoft() {
        if (settings.clientId == null || settings.clientId.isBlank()) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    "Microsoft login needs a one-time setup:\n\n"
                    + "1. Go to portal.azure.com -> App registrations -> New registration\n"
                    + "2. Accounts: 'personal Microsoft accounts + any org'\n"
                    + "3. Enable 'Allow public client flows'\n"
                    + "4. Copy the Application (client) ID\n"
                    + "5. Put client_id=<id> into launcher.properties\n\n"
                    + "Until then you can play in offline mode below.",
                    "Microsoft login not configured", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        play();
    }

    private void play() {
        playButton.setEnabled(false);
        status.setText("Preparing...");
        new Thread(() -> {
            try {
                runPipeline();
            } catch (Throwable t) {
                SwingUtilities.invokeLater(() -> {
                    status.setText("Failed: " + t.getMessage());
                    appendLog("ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    playButton.setEnabled(true);
                });
            }
        }, "havoc-launch").start();
    }

    private void runPipeline() throws Exception {
        update("Making sure Java 25 is available", 0);
        Path javaExe = JavaRuntime.ensureJava(OsUtil.launcherDir(), this::onProgress);

        update("Signing in", 0);
        Auth.Account acc = Auth.login(settings, new Auth.DeviceCodeUi() {
            private JDialog dialog;
            private volatile boolean cancelled;

            @Override public boolean showCode(String code, String uri) {
                boolean[] ok = {false};
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        JDialog d = new JDialog(LauncherUI.this, "Microsoft login",
                                java.awt.Dialog.ModalityType.APPLICATION_MODAL);
                        d.setLayout(new BorderLayout(10, 10));
                        JPanel p = new JPanel(new GridBagLayout());
                        p.setBackground(BG);
                        p.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
                        GridBagConstraints c = new GridBagConstraints();
                        c.insets = new Insets(4, 4, 4, 4); c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.WEST;
                        JLabel l1 = new JLabel("1. Open:  " + uri); l1.setForeground(FG); p.add(l1, c);
                        c.gridy = 1;
                        JLabel l2 = new JLabel("2. Enter code:  " + code);
                        l2.setForeground(RED_BRIGHT); l2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
                        p.add(l2, c);
                        c.gridy = 2;
                        JButton open = new JButton("Open browser");
                        styleButton(open);
                        open.addActionListener(ev -> {
                            try { Desktop.getDesktop().browse(URI.create(uri)); } catch (Exception ignored) {}
                        });
                        JButton cancel = new JButton("Cancel");
                        styleButton(cancel);
                        cancel.addActionListener(ev -> { cancelled = true; d.dispose(); });
                        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
                        btns.setOpaque(false);
                        btns.add(open); btns.add(cancel);
                        p.add(btns, c);
                        d.setContentPane(p);
                        d.pack();
                        d.setLocationRelativeTo(LauncherUI.this);
                        d.setVisible(true);
                        dialog = d;
                        ok[0] = !cancelled;
                    });
                } catch (Exception ignored) {}
                return ok[0];
            }

            @Override public boolean isCancelled() { return cancelled; }

            @Override public void done() {
                JDialog d = dialog;
                if (d != null) SwingUtilities.invokeLater(() -> d.dispose());
            }
        });
        SwingUtilities.invokeLater(() -> setAccount(acc));

        update("Preparing Minecraft + HavocClient", 0);
        Installer installer = new Installer(OsUtil.launcherDir(), this::onProgress, false);
        Installer.Result prepared = installer.prepare();
        SwingUtilities.invokeLater(() -> appendLog("Mods: " + prepared.mods().size()
                + ", libraries: " + (prepared.classpath().size() - 1)));

        update("Starting Minecraft", 100);
        List<String> cmd = LaunchBuilder.build(settings, prepared, acc, javaExe);
        SwingUtilities.invokeLater(() -> appendLog("Launching: " + cmd.get(0) + " ..."));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(prepared.gameDir().toFile());
        pb.redirectErrorStream(true);
        gameProcess = pb.start();
        Thread logPump = new Thread(() -> {
            try (var in = gameProcess.getInputStream()) {
                byte[] buf = new byte[4096];
                StringBuilder line = new StringBuilder();
                int n;
                while ((n = in.read(buf)) > 0) {
                    String chunk = new String(buf, 0, n);
                    line.append(chunk);
                    int nl;
                    while ((nl = line.indexOf("\n")) >= 0) {
                        final String s = line.substring(0, nl);
                        line.delete(0, nl + 1);
                        SwingUtilities.invokeLater(() -> appendLog(s));
                    }
                }
            } catch (Exception ignored) {}
        }, "game-log");
        logPump.setDaemon(true);
        logPump.start();

        SwingUtilities.invokeLater(() -> {
            status.setText("Minecraft is running.");
            progress.setValue(100);
            appendLog("Minecraft started (pid " + gameProcess.pid() + "). Keep this window open.");
            if (!settings.keepLauncherOpen) {
                dispose();
                System.exit(0);
            }
            playButton.setEnabled(true);
        });
        int exit = gameProcess.waitFor();
        SwingUtilities.invokeLater(() -> {
            status.setText(exit == 0 ? "Minecraft closed." : "Minecraft closed with code " + exit);
            appendLog("Minecraft exited with code " + exit);
        });
    }

    private void onProgress(String stage, long done, long total) {
        SwingUtilities.invokeLater(() -> {
            progress.setIndeterminate(total <= 0);
            if (total > 0) {
                progress.setIndeterminate(false);
                progress.setValue((int) Math.min(100, done * 100 / total));
            }
            status.setText(stage);
        });
    }

    private void update(String text, int pct) {
        SwingUtilities.invokeLater(() -> { status.setText(text); progress.setValue(pct); });
    }

    private void appendLog(String line) {
        log.append(line + "\n");
        if (log.getLineCount() > 400) {
            try { log.replaceRange("", 0, log.getLineEndOffset(2)); } catch (Exception ignored) {}
        }
        log.setCaretPosition(log.getDocument().getLength());
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
