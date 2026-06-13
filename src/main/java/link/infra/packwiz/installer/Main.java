package link.infra.packwiz.installer;

import link.infra.packwiz.installer.config.InstallerConfig;
import com.formdev.flatlaf.FlatDarkLaf;
import link.infra.packwiz.installer.ui.gui.WorkbenchWindow;
import link.infra.packwiz.installer.util.AppShutdown;
import link.infra.packwiz.installer.util.Log;

import javax.swing.*;
import java.nio.file.Path;

public class Main {

    private static Path getRootDir() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    public static void main(String[] args) {
        Path rootDir = getRootDir();

        try {
            InstallerConfig.ensureConfigDir(rootDir);
        } catch (Exception e) {
            Log.fatal("创建配置目录失败", e);
            AppShutdown.exit(1);
            return;
        }

        InstallerConfig config = InstallerConfig.load(rootDir);
        if (args.length > 0) {
            config.setPackUrl(args[0]);
        }

        Path finalRootDir = rootDir;
        try {
            FlatDarkLaf.setup();
        } catch (Exception e) {
            Log.warn("初始化 FlatLaf 失败，使用系统外观: " + e.getMessage());
        }
        SwingUtilities.invokeLater(() -> new WorkbenchWindow(config, finalRootDir).setVisible(true));
    }
}
