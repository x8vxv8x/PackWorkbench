package link.infra.packwiz.installer.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class Log {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Log() {}

    public static void info(String message) {
        System.out.println("[" + now() + "] [Info] " + message);
    }

    public static void warn(String message) {
        System.err.println("[" + now() + "] [Warning] " + message);
    }

    public static void warn(String message, Exception e) {
        System.err.println("[" + now() + "] [Warning] " + message + ": " + e.getMessage());
    }

    public static void fatal(String message, Exception e) {
        System.err.println("[" + now() + "] [FATAL] " + message + ": " + e.getMessage());
    }

    private static String now() {
        return LocalDateTime.now().format(FMT);
    }
}
