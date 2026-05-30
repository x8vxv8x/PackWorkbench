package link.infra.packwiz.installer.ui.gui;

import link.infra.packwiz.installer.Main;
import link.infra.packwiz.installer.config.InstallerConfig;
import link.infra.packwiz.installer.config.InstallerConfig.SyncMode;
import link.infra.packwiz.installer.target.Side;
import link.infra.packwiz.installer.util.Log;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/**
 * 启动配置窗口。用户在此设置整合包地址、安装目录、同步范围等参数。
 * 支持自动扫描本地 pack.toml 和 mods 文件夹。
 */
public class SetupWindow extends JFrame {
    private final InstallerConfig config;
    private final Path rootDir;

    private JTextField urlField;
    private JTextField installFolderField;
    private JRadioButton clientRadio;
    private JRadioButton serverRadio;
    private JRadioButton bothRadio;
    private JTextField multimcFolderField;
    private JTextField metaFileField;
    private JSpinner timeoutSpinner;

    private JRadioButton modsOnlyRadio;
    private JRadioButton configuredFilesRadio;

    // 日志面板
    private JTextArea logArea;
    private JPanel logPanel;
    private boolean logPanelVisible = false;

    public SetupWindow(InstallerConfig config, Path rootDir) {
        super("packwiz 安装器 - 配置");
        this.config = config;
        this.rootDir = rootDir;

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(600, 600);
        setLocationRelativeTo(null);

        buildUI();
        populateFromConfig();
        setupLogListener();
    }

    private void buildUI() {
        var mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // ===== 中部：可滚动的配置面板 =====
        var formPanel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;

        // --- 整合包地址 ---
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        formPanel.add(new JLabel("整合包地址:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        urlField = new JTextField(30);
        formPanel.add(urlField, gbc);

        gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        var scanPackBtn = new JButton("扫描");
        scanPackBtn.setToolTipText("扫描本地 pack.toml 文件");
        scanPackBtn.addActionListener(e -> scanForPackToml());
        formPanel.add(scanPackBtn, gbc);

        gbc.gridx = 3;
        var pasteBtn = new JButton("粘贴");
        pasteBtn.addActionListener(e -> {
            try {
                String text = (String) Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(DataFlavor.stringFlavor);
                if (text != null) urlField.setText(text.trim());
            } catch (Exception ignored) {}
        });
        formPanel.add(pasteBtn, gbc);

        row++;

        // --- 安装目录 ---
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        formPanel.add(new JLabel("安装目录:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        installFolderField = new JTextField(30);
        formPanel.add(installFolderField, gbc);

        gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        var browseFolderBtn = new JButton("浏览...");
        browseFolderBtn.addActionListener(e -> browseFolder(installFolderField));
        formPanel.add(browseFolderBtn, gbc);

        gbc.gridx = 3;
        var scanModsBtn = new JButton("扫描");
        scanModsBtn.setToolTipText("扫描 mods 目录");
        scanModsBtn.addActionListener(e -> scanForModsFolder());
        formPanel.add(scanModsBtn, gbc);

        row++;

        // --- 安装端 ---
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        formPanel.add(new JLabel("安装端:"), gbc);

        clientRadio = new JRadioButton("客户端");
        serverRadio = new JRadioButton("服务端");
        bothRadio = new JRadioButton("全部", true);
        var sideGroup = new ButtonGroup();
        sideGroup.add(clientRadio);
        sideGroup.add(serverRadio);
        sideGroup.add(bothRadio);
        var sidePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        sidePanel.add(clientRadio);
        sidePanel.add(serverRadio);
        sidePanel.add(bothRadio);
        gbc.gridx = 1; gbc.gridwidth = 3;
        formPanel.add(sidePanel, gbc);
        gbc.gridwidth = 1;

        row++;

        // --- 同步范围 ---
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 4;
        formPanel.add(new JLabel("同步范围:"), gbc);
        gbc.gridwidth = 1;
        row++;

        modsOnlyRadio = new JRadioButton("仅同步 mods");
        modsOnlyRadio.setToolTipText("只同步 index 中位于 mods/ 下的文件，适合本地 git+packwiz 模组下载工作流。");
        configuredFilesRadio = new JRadioButton("按配置文件同步");
        configuredFilesRadio.setToolTipText("同步 packwiz index 中配置的所有文件，包括 config、scripts、resourcepacks 等。");
        var syncGroup = new ButtonGroup();
        syncGroup.add(modsOnlyRadio);
        syncGroup.add(configuredFilesRadio);
        var syncPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        syncPanel.add(modsOnlyRadio);
        syncPanel.add(configuredFilesRadio);

        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 4; gbc.fill = GridBagConstraints.HORIZONTAL;
        formPanel.add(syncPanel, gbc);
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;

        row++;

        // --- 高级设置（可折叠） ---
        var advancedToggle = new JCheckBox("高级设置");
        advancedToggle.setSelected(false);
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 4;
        formPanel.add(advancedToggle, gbc);
        gbc.gridwidth = 1;
        row++;

        var advancedPanel = new JPanel(new GridBagLayout());
        advancedPanel.setBorder(BorderFactory.createTitledBorder("高级设置"));
        advancedPanel.setVisible(false);
        var agbc = new GridBagConstraints();
        agbc.insets = new Insets(4, 4, 4, 4);
        agbc.anchor = GridBagConstraints.WEST;
        int arow = 0;

        // MultiMC 目录
        agbc.gridx = 0; agbc.gridy = arow; agbc.weightx = 0;
        advancedPanel.add(new JLabel("MultiMC 目录:"), agbc);
        agbc.gridx = 1; agbc.weightx = 1; agbc.fill = GridBagConstraints.HORIZONTAL;
        multimcFolderField = new JTextField(25);
        advancedPanel.add(multimcFolderField, agbc);
        agbc.gridx = 2; agbc.weightx = 0; agbc.fill = GridBagConstraints.NONE;
        var browseMultimcBtn = new JButton("浏览...");
        browseMultimcBtn.addActionListener(e -> browseFolder(multimcFolderField));
        advancedPanel.add(browseMultimcBtn, agbc);
        arow++;

        // 元数据文件名
        agbc.gridx = 0; agbc.gridy = arow;
        advancedPanel.add(new JLabel("清单文件名:"), agbc);
        agbc.gridx = 1; agbc.gridwidth = 2;
        metaFileField = new JTextField("packwiz.json", 25);
        advancedPanel.add(metaFileField, agbc);
        agbc.gridwidth = 1;
        arow++;

        // 超时时间
        agbc.gridx = 0; agbc.gridy = arow;
        advancedPanel.add(new JLabel("超时时间(秒):"), agbc);
        agbc.gridx = 1;
        timeoutSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 300, 1));
        advancedPanel.add(timeoutSpinner, agbc);

        advancedToggle.addActionListener(e -> advancedPanel.setVisible(advancedToggle.isSelected()));

        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 4; gbc.fill = GridBagConstraints.HORIZONTAL;
        formPanel.add(advancedPanel, gbc);

        // 将 formPanel 放入滚动面板
        var scrollPane = new JScrollPane(formPanel);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // ===== 底部：日志面板 + 按钮 =====
        var bottomPanel = new JPanel(new BorderLayout(0, 4));

        // 日志面板（可折叠）
        logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("操作日志"));
        logArea = new JTextArea(6, 50);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logPanel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        logPanel.setVisible(false);

        var logToggle = new JCheckBox("显示日志");
        logToggle.addActionListener(e -> {
            logPanelVisible = logToggle.isSelected();
            logPanel.setVisible(logPanelVisible);
            pack();
        });

        var logHeaderPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        logHeaderPanel.add(logToggle);

        bottomPanel.add(logHeaderPanel, BorderLayout.NORTH);
        bottomPanel.add(logPanel, BorderLayout.CENTER);

        // 按钮
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var cancelBtn = new JButton("取消");
        cancelBtn.addActionListener(e -> System.exit(0));
        var syncBtn = new JButton("同步检查");
        syncBtn.addActionListener(e -> startSync());
        getRootPane().setDefaultButton(syncBtn);
        buttonPanel.add(cancelBtn);
        buttonPanel.add(syncBtn);
        bottomPanel.add(buttonPanel, BorderLayout.SOUTH);

        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        setContentPane(mainPanel);
    }

    private void populateFromConfig() {
        urlField.setText(config.getPackUrl());
        installFolderField.setText(config.getInstallFolder());

        // Side
        switch (config.getSide().toLowerCase()) {
            case "server" -> serverRadio.setSelected(true);
            case "both" -> bothRadio.setSelected(true);
            default -> clientRadio.setSelected(true);
        }

        multimcFolderField.setText(config.getMultimcFolder());
        metaFileField.setText(config.getMetaFile());
        timeoutSpinner.setValue((int) config.getTimeout());

        if (config.getSyncMode() == SyncMode.CONFIGURED_FILES) {
            configuredFilesRadio.setSelected(true);
        } else {
            modsOnlyRadio.setSelected(true);
        }
    }

    private void setupLogListener() {
        Log.addListener((level, message) -> {
            SwingUtilities.invokeLater(() -> {
                logArea.append("[" + level + "] " + message + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            });
        });
    }

    private void scanForPackToml() {
        List<Path> found = InstallerConfig.scanForPackToml(rootDir);
        if (found.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "在当前目录下未找到 pack.toml 文件。\n搜索目录: " + rootDir,
                "扫描结果", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (found.size() == 1) {
            urlField.setText(found.get(0).toString());
        } else {
            String[] paths = found.stream().map(Path::toString).toArray(String[]::new);
            String selected = (String) JOptionPane.showInputDialog(this,
                "找到多个 pack.toml，请选择:", "扫描结果",
                JOptionPane.PLAIN_MESSAGE, null, paths, paths[0]);
            if (selected != null) {
                urlField.setText(selected);
            }
        }
    }

    private void scanForModsFolder() {
        List<Path> found = InstallerConfig.scanForModsFolder(rootDir);
        if (found.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "在当前目录下未找到包含 mods 文件夹的目录。\n搜索目录: " + rootDir,
                "扫描结果", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (found.size() == 1) {
            installFolderField.setText(found.get(0).getParent().toString());
        } else {
            String[] paths = found.stream().map(path -> path.getParent().toString()).toArray(String[]::new);
            String selected = (String) JOptionPane.showInputDialog(this,
                "找到多个安装目录，请选择:", "扫描结果",
                JOptionPane.PLAIN_MESSAGE, null, paths, paths[0]);
            if (selected != null) {
                installFolderField.setText(selected);
            }
        }
    }

    private void browseFolder(JTextField target) {
        var chooser = new JFileChooser(target.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("选择目录");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void startSync() {
        // 验证
        String url = urlField.getText().trim();
        if (url.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请指定整合包地址！", "验证失败", JOptionPane.WARNING_MESSAGE);
            urlField.requestFocus();
            return;
        }
        String folder = installFolderField.getText().trim();
        if (folder.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请指定安装目录！", "验证失败", JOptionPane.WARNING_MESSAGE);
            installFolderField.requestFocus();
            return;
        }

        // 保存到配置
        config.setPackUrl(url);
        if (bothRadio.isSelected()) config.setSide("both");
        else if (serverRadio.isSelected()) config.setSide("server");
        else config.setSide("client");
        config.setInstallFolder(folder);
        config.setMultimcFolder(multimcFolderField.getText().trim());
        config.setMetaFile(metaFileField.getText().trim());
        config.setTimeout((int) timeoutSpinner.getValue());
        config.setSyncMode(configuredFilesRadio.isSelected() ? SyncMode.CONFIGURED_FILES : SyncMode.MODS_ONLY);
        config.save(rootDir);

        // 禁用按钮
        setEnabled(false);

        // 显示日志面板
        if (!logPanelVisible) {
            logPanelVisible = true;
            logPanel.setVisible(true);
            pack();
        }

        // 在后台线程执行同步检查
        new Thread(() -> {
            try {
                var syncManager = new link.infra.packwiz.installer.sync.SyncManager(config, rootDir);
                var preview = syncManager.checkForChanges();

                if (preview.unchanged) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this,
                            "整合包已是最新版本，无需同步。", "同步检查",
                            JOptionPane.INFORMATION_MESSAGE);
                    });
                    syncManager.close();
                    return;
                }

                // 在 EDT 显示预览窗口
                final boolean[] syncStarted = {false};
                SwingUtilities.invokeAndWait(() -> {
                    var previewWindow = new SyncPreviewWindow(this, preview);
                    previewWindow.setVisible(true);

                    if (previewWindow.isConfirmed()) {
                        syncStarted[0] = true;
                        // 用户确认同步，显示进度并执行
                        var progressDialog = new JProgressDialog(this);
                        int totalTasks = preview.added.size() + preview.updated.size() + preview.removed.size();
                        progressDialog.setMaximum(Math.max(totalTasks, 1));
                        progressDialog.setVisible(true);

                        new Thread(() -> {
                            try {
                                var result = syncManager.executeSync(preview, (completed, status) -> {
                                    SwingUtilities.invokeLater(() -> progressDialog.updateProgress(completed, status));
                                });
                                SwingUtilities.invokeLater(() -> {
                                    progressDialog.dispose();
                                    var resultWindow = new SyncResultWindow(this, result);
                                    resultWindow.setVisible(true);
                                });
                            } finally {
                                syncManager.close();
                            }
                        }).start();
                    }
                });

                if (!syncStarted[0]) {
                    syncManager.close();
                }
            } catch (Exception e) {
                Log.warn("同步检查失败: " + e.getMessage());
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this,
                        "同步检查失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                });
            } finally {
                SwingUtilities.invokeLater(() -> setEnabled(true));
            }
        }).start();
    }

    /** 简单的进度对话框 */
    private static class JProgressDialog extends JDialog {
        private final JLabel statusLabel;
        private final JProgressBar progressBar;

        JProgressDialog(JFrame parent) {
            super(parent, "同步中...", false);
            setSize(400, 100);
            setLocationRelativeTo(parent);
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

            var panel = new JPanel(new BorderLayout(8, 8));
            panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

            statusLabel = new JLabel("准备中...");
            panel.add(statusLabel, BorderLayout.NORTH);

            progressBar = new JProgressBar(0, 100);
            progressBar.setStringPainted(true);
            panel.add(progressBar, BorderLayout.CENTER);

            setContentPane(panel);
        }

        void updateProgress(int completed, String status) {
            statusLabel.setText(status);
            progressBar.setValue(completed);
        }

        void setMaximum(int maximum) {
            progressBar.setMaximum(maximum);
        }
    }
}
