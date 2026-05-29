package link.infra.packwiz.installer;

import link.infra.packwiz.installer.target.Side;
import link.infra.packwiz.installer.target.path.HttpUrlPath;
import link.infra.packwiz.installer.target.path.PackwizFilePath;
import link.infra.packwiz.installer.target.path.PackwizPath;
import link.infra.packwiz.installer.ui.IUserInterface;
import link.infra.packwiz.installer.ui.cli.CLIHandler;
import link.infra.packwiz.installer.ui.gui.GUIHandler;
import link.infra.packwiz.installer.util.Log;
import org.apache.commons.cli.*;

import java.awt.*;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.swing.*;

public class Main {
    private boolean guiEnabled = !GraphicsEnvironment.isHeadless();
    private IUserInterface ui;

    public Main(String[] args) {
        var options = new Options();
        addOptions(options);

        CommandLine cmd;
        try {
            cmd = new DefaultParser().parse(options, args);
        } catch (ParseException e) {
            Log.fatal("解析命令行参数失败", e);
            if (guiEnabled) {
                try {
                    EventQueue.invokeAndWait(() -> {
                        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
                        JOptionPane.showMessageDialog(null, "解析命令行参数失败：" + e, "packwiz 安装器", JOptionPane.ERROR_MESSAGE);
                    });
                } catch (Exception ignored) {}
            }
            System.exit(1);
            return;
        }

        if (guiEnabled && cmd.hasOption("no-gui")) guiEnabled = false;
        ui = guiEnabled ? new GUIHandler() : new CLIHandler();

        var unparsedArgs = cmd.getArgs();
        if (unparsedArgs.length > 1) {
            ui.showErrorAndExit("指定了过多的参数！");
            return;
        }
        if (unparsedArgs.length == 0) {
            ui.showErrorAndExit("必须指定 pack.toml 的 URI 地址！");
            return;
        }

        String title = cmd.getOptionValue("title");
        if (title != null) ui.setTitle(title);
        ui.show();

        String packFileRaw = unparsedArgs[0];
        PackwizPath<?> packFile;

        if (packFileRaw.matches("(?i)^https?://.*")) {
            packFile = ui.wrap("整合包文件的 HTTP/HTTPS URL 无效：" + packFileRaw, () -> {
                var uri = URI.create(packFileRaw);
                var baseUri = uri.resolve(".");
                String lastSegment = uri.getPath().isEmpty() ? "" : uri.getPath().split("/")[uri.getPath().split("/").length - 1];
                return new HttpUrlPath(baseUri, lastSegment);
            });
        } else if (packFileRaw.matches("(?i)^file:.*")) {
            packFile = ui.wrap("解析整合包文件路径失败：" + packFileRaw, () -> {
                var path = Paths.get(URI.create(packFileRaw));
                var parent = path.getParent();
                if (parent == null) { ui.showErrorAndExit("整合包文件路径无效：" + packFileRaw); return null; }
                return new PackwizFilePath(parent, path.getFileName().toString());
            });
        } else if (packFileRaw.matches("(?i)^[a-z][a-z\\d+\\-.]*://.*")) {
            ui.showErrorAndExit("不支持的整合包文件协议：" + packFileRaw);
            return;
        } else {
            var path = Path.of(packFileRaw);
            var parent = path.getParent();
            if (parent == null) { ui.showErrorAndExit("整合包文件路径无效：" + packFileRaw); return; }
            packFile = new PackwizFilePath(parent, path.getFileName().toString());
        }

        Side side = Side.CLIENT;
        String sideStr = cmd.getOptionValue("side");
        if (sideStr != null) {
            side = Side.from(sideStr);
            if (side == null) { ui.showErrorAndExit("未知的端名称：" + sideStr); return; }
        }

        PackwizFilePath packFolder = ui.wrap("整合包文件夹路径无效", () -> {
            String pf = cmd.getOptionValue("pack-folder");
            return pf != null ? new PackwizFilePath(Path.of(pf)) : new PackwizFilePath(Path.of("."));
        });

        PackwizFilePath multimcFolder = ui.wrap("MultiMC 文件夹路径无效", () -> {
            String mf = cmd.getOptionValue("multimc-folder");
            return mf != null ? new PackwizFilePath(Path.of(mf)) : new PackwizFilePath(Path.of(".."));
        });

        PackwizFilePath manifestFile = ui.wrap("清单文件路径无效", () -> {
            String metaFile = cmd.getOptionValue("meta-file");
            return packFolder.resolve(metaFile != null ? metaFile : "packwiz.json");
        });

        long timeout = 10;
        String timeoutStr = cmd.getOptionValue("timeout");
        if (timeoutStr != null) {
            try { timeout = Long.parseLong(timeoutStr); } catch (NumberFormatException e) {
                ui.showErrorAndExit("超时时间值无效"); return;
            }
        }

        try {
            new UpdateManager(new UpdateManager.Options(packFile, manifestFile, packFolder, multimcFolder, side, timeout), ui);
        } catch (Exception e) {
            ui.showErrorAndExit("更新过程失败", e);
        }
        System.out.println("完成！");
        ui.dispose();
    }

    private static void addOptions(Options options) {
        options.addOption("s", "side", true, "安装模组的端 (client/server，默认为 client)");
        options.addOption(null, "title", true, "安装窗口标题");
        options.addOption(null, "pack-folder", true, "安装整合包的文件夹（默认为 JAR 所在目录）");
        options.addOption(null, "multimc-folder", true, "MultiMC 整合包文件夹（默认为整合包目录的上级）");
        options.addOption(null, "meta-file", true, "存储整合包元数据的 JSON 文件（默认为 packwiz.json）");
        options.addOption("t", "timeout", true, "询问可选模组时自动启动前等待的秒数（默认为 10）");
        options.addOption("g", "no-gui", false, "不显示 GUI 界面");
        options.addOption("h", "help", false, "显示此帮助信息");
    }

    public static void main(String[] args) {
        new Main(args);
    }
}
