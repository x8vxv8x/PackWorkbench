package link.infra.packwiz.installer.ui.gui;

import link.infra.packwiz.installer.sync.SyncManager;

import javax.swing.*;
import java.awt.*;

/**
 * 同步结果窗口。显示成功/失败详情。
 */
public class SyncResultWindow extends JDialog {
    private boolean retryRequested = false;

    public SyncResultWindow(JFrame parent, SyncManager.SyncResult result) {
        super(parent, "同步完成", true);
        setSize(550, 500);
        setLocationRelativeTo(parent);

        var mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // 顶部统计
        int successCount = result.added.size() + result.removed.size();
        int failCount = result.failed.size();
        var summaryLabel = new JLabel(String.format(
            "成功: %d 个    失败: %d 个", successCount, failCount
        ));
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD, 14f));
        mainPanel.add(summaryLabel, BorderLayout.NORTH);

        // 中部详情
        var textArea = new JTextArea();
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        var sb = new StringBuilder();

        if (!result.added.isEmpty()) {
            sb.append("新增/更新成功:\n");
            for (var mod : result.added) {
                String type = mod.changeType.equals("added") ? "新增" : "更新";
                sb.append("  [").append(type).append("] ").append(mod.name).append("\n");
                sb.append("    ").append(mod.destPath).append("\n");
            }
            sb.append("\n");
        }

        if (!result.removed.isEmpty()) {
            sb.append("已删除/清理:\n");
            for (var mod : result.removed) {
                sb.append("  ").append(mod.name).append("\n");
                sb.append("    ").append(mod.destPath).append("\n");
            }
            sb.append("\n");
        }

        if (!result.failed.isEmpty()) {
            sb.append("下载失败:\n");
            for (var mod : result.failed) {
                String type = mod.changeType.equals("added") ? "新增" : "更新";
                sb.append("  [").append(type).append("] ").append(mod.name).append("\n");
                sb.append("    文件: ").append(mod.destPath).append("\n");
                sb.append("    链接: ").append(mod.downloadUrl).append("\n");
                sb.append("    错误: ").append(mod.error).append("\n\n");
            }
        }

        if (result.failed.isEmpty() && result.added.isEmpty() && result.removed.isEmpty()) {
            sb.append("无需更改，已是最新版本。\n");
        }

        textArea.setText(sb.toString());
        textArea.setCaretPosition(0);
        mainPanel.add(new JScrollPane(textArea), BorderLayout.CENTER);

        // 底部按钮
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var closeBtn = new JButton("关闭");
        closeBtn.addActionListener(e -> dispose());
        buttonPanel.add(closeBtn);

        if (!result.failed.isEmpty()) {
            var retryBtn = new JButton("重试失败项");
            retryBtn.addActionListener(e -> {
                retryRequested = true;
                dispose();
            });
            buttonPanel.add(retryBtn);
        }

        mainPanel.add(buttonPanel, BorderLayout.SOUTH);
        setContentPane(mainPanel);
    }

    public boolean isRetryRequested() { return retryRequested; }
}
