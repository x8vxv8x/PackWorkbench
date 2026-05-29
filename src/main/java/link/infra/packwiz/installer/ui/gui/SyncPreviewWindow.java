package link.infra.packwiz.installer.ui.gui;

import link.infra.packwiz.installer.sync.SyncManager;

import javax.swing.*;
import java.awt.*;

/**
 * 同步预览窗口。显示新增/更新/删除的文件列表。
 */
public class SyncPreviewWindow extends JDialog {
    private boolean confirmed = false;

    public SyncPreviewWindow(JFrame parent, SyncManager.SyncResult preview) {
        super(parent, "同步预览", true);
        setSize(550, 500);
        setLocationRelativeTo(parent);

        var mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // 顶部信息
        var infoPanel = new JPanel(new GridLayout(2, 1));
        infoPanel.add(new JLabel("整合包: " + preview.packName));
        String lastSync = preview.lastSyncTime.isEmpty() ? "从未" : preview.lastSyncTime;
        infoPanel.add(new JLabel("上次同步: " + lastSync));
        mainPanel.add(infoPanel, BorderLayout.NORTH);

        // 中部列表
        var textArea = new JTextArea();
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        var sb = new StringBuilder();

        if (!preview.added.isEmpty()) {
            sb.append("新增 (").append(preview.added.size()).append(")\n");
            for (var mod : preview.added) {
                sb.append("  + ").append(mod.name).append("\n");
                sb.append("    ").append(mod.destPath).append("\n");
            }
            sb.append("\n");
        }

        if (!preview.updated.isEmpty()) {
            sb.append("更新 (").append(preview.updated.size()).append(")\n");
            for (var mod : preview.updated) {
                sb.append("  ~ ").append(mod.name).append("\n");
                sb.append("    ").append(mod.destPath).append("\n");
            }
            sb.append("\n");
        }

        if (!preview.removed.isEmpty()) {
            sb.append("将删除 (").append(preview.removed.size()).append(") - 已从索引移除\n");
            for (var mod : preview.removed) {
                sb.append("  - ").append(mod.name).append("\n");
                sb.append("    ").append(mod.destPath).append("\n");
            }
            sb.append("\n");
        }

        if (preview.added.isEmpty() && preview.updated.isEmpty() && preview.removed.isEmpty()) {
            sb.append("无需更改，已是最新版本。\n");
        }

        textArea.setText(sb.toString());
        textArea.setCaretPosition(0);
        mainPanel.add(new JScrollPane(textArea), BorderLayout.CENTER);

        // 底部按钮
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var cancelBtn = new JButton("取消");
        var syncBtn = new JButton("开始同步");

        boolean hasChanges = !preview.added.isEmpty() || !preview.updated.isEmpty() || !preview.removed.isEmpty();
        syncBtn.setEnabled(hasChanges);

        cancelBtn.addActionListener(e -> dispose());
        syncBtn.addActionListener(e -> {
            confirmed = true;
            dispose();
        });
        getRootPane().setDefaultButton(syncBtn);

        buttonPanel.add(cancelBtn);
        buttonPanel.add(syncBtn);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        setContentPane(mainPanel);
    }

    public boolean isConfirmed() { return confirmed; }
}
