package link.infra.packwiz.installer.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

public final class Log {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 日志监听器列表（线程安全） */
    private static final CopyOnWriteArrayList<BiConsumer<String, String>> listeners = new CopyOnWriteArrayList<>();

    private Log() {}

    /** 添加日志监听器。level 为 "INFO" / "WARN" / "FATAL"。 */
    public static void addListener(BiConsumer<String, String> listener) {
        listeners.add(listener);
    }

    public static void removeListener(BiConsumer<String, String> listener) {
        listeners.remove(listener);
    }

    public static void info(String message) {
        String formatted = "[" + now() + "] [Info] " + message;
        System.out.println(formatted);
        notifyListeners("INFO", message);
    }

    public static void warn(String message) {
        String formatted = "[" + now() + "] [Warning] " + message;
        System.err.println(formatted);
        notifyListeners("WARN", message);
    }

    public static void warn(String message, Exception e) {
        String formatted = "[" + now() + "] [Warning] " + message + ": " + e.getMessage();
        System.err.println(formatted);
        notifyListeners("WARN", message + ": " + e.getMessage());
    }

    public static void fatal(String message, Exception e) {
        String formatted = "[" + now() + "] [FATAL] " + message + ": " + e.getMessage();
        System.err.println(formatted);
        notifyListeners("FATAL", message + ": " + e.getMessage());
    }

    private static void notifyListeners(String level, String message) {
        for (var listener : listeners) {
            try {
                listener.accept(level, message);
            } catch (Exception ignored) {
                // 监听器异常不应影响日志输出
            }
        }
    }

    private static String now() {
        return LocalDateTime.now().format(FMT);
    }
}
