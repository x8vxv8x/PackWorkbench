package link.infra.packwiz.installer.ui.gui;

import link.infra.packwiz.installer.ui.data.InstallProgress;

import javax.swing.*;
import java.awt.*;

public class InstallWindow extends JFrame {
    private final JProgressBar progressBar;
    private final JLabel statusLabel;

    public InstallWindow(String title) {
        super(title);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(450, 120);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(8, 8));

        statusLabel = new JLabel("准备中...");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        add(statusLabel, BorderLayout.NORTH);

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        add(progressBar, BorderLayout.CENTER);
    }

    public void updateProgress(InstallProgress progress) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(progress.text());
            if (progress.total() > 0) {
                progressBar.setIndeterminate(false);
                progressBar.setMaximum(progress.total());
                progressBar.setValue(progress.current());
                progressBar.setString(progress.current() + " / " + progress.total());
            } else {
                progressBar.setIndeterminate(true);
                progressBar.setString("");
            }
        });
    }
}
