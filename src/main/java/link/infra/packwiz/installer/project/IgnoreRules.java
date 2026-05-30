package link.infra.packwiz.installer.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

public class IgnoreRules {
    private final List<Rule> rules = new ArrayList<>();

    public static IgnoreRules load(Path root, Path file, List<String> defaults) throws IOException {
        IgnoreRules ignore = new IgnoreRules();
        for (String pattern : defaults) {
            ignore.add(pattern);
        }
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                ignore.add(line);
            }
        }
        return ignore;
    }

    public boolean matches(String relativePath, boolean directory) {
        String normalized = normalize(relativePath);
        boolean ignored = false;
        for (Rule rule : rules) {
            if (rule.matches(normalized, directory)) {
                ignored = !rule.negated();
            }
        }
        return ignored;
    }

    private void add(String rawPattern) {
        String line = rawPattern == null ? "" : rawPattern.trim();
        if (line.isEmpty() || line.startsWith("#")) return;
        boolean negated = line.startsWith("!");
        if (negated) line = line.substring(1).trim();
        if (line.isEmpty()) return;
        rules.add(new Rule(line, negated));
    }

    private static String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/').replaceAll("^/+", "");
    }

    private record Rule(String pattern, boolean negated) {
        boolean matches(String path, boolean directory) {
            String p = normalize(pattern);
            boolean directoryRule = p.endsWith("/");
            if (directoryRule) p = p.substring(0, p.length() - 1);
            if (directoryRule && !directory) {
                return path.equals(p) || path.startsWith(p + "/") || path.contains("/" + p + "/");
            }
            if (!p.contains("*") && !p.contains("?") && !p.contains("[")) {
                String exact = p.replaceAll("^/+", "");
                if (path.equals(exact)) return true;
                if (path.startsWith(exact + "/")) return true;
                return !exact.contains("/") && (path.endsWith("/" + exact) || path.contains("/" + exact + "/"));
            }
            String glob = p.startsWith("/") ? p.substring(1) : p;
            if (!glob.contains("/")) {
                glob = "**/" + glob;
            }
            PathMatcher matcher = java.nio.file.FileSystems.getDefault().getPathMatcher(
                "glob:" + glob.replace('/', java.io.File.separatorChar)
            );
            return matcher.matches(Path.of(path.replace('/', java.io.File.separatorChar)));
        }
    }
}
