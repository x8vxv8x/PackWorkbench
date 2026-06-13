package link.infra.packwiz.installer.util;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AppShutdown {
    private static final List<AutoCloseable> closeables = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean exiting = new AtomicBoolean(false);

    private AppShutdown() {}

    public static void register(AutoCloseable closeable) {
        if (closeable != null) closeables.add(closeable);
    }

    public static void unregister(AutoCloseable closeable) {
        closeables.remove(closeable);
    }

    public static void exit(int status) {
        if (!exiting.compareAndSet(false, true)) return;

        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(2500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            Runtime.getRuntime().halt(status);
        }, "packworkbench-force-exit");
        watchdog.setDaemon(true);
        watchdog.start();

        closeWindows();
        closeResources();
        System.exit(status);
    }

    private static void closeWindows() {
        Runnable close = () -> {
            for (Window window : Window.getWindows()) {
                try {
                    window.dispose();
                } catch (Exception ignored) {
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            close.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(close);
            } catch (Exception ignored) {
                close.run();
            }
        }
    }

    private static void closeResources() {
        for (AutoCloseable closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception e) {
                Log.warn("退出时释放资源失败: " + e.getMessage());
            }
        }
        closeables.clear();
    }
}
