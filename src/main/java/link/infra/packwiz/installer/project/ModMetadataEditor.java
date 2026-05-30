package link.infra.packwiz.installer.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ModMetadataEditor {
    public static void setPinned(Path metaFile, boolean pinned) throws IOException {
        List<String> lines = Files.readAllLines(metaFile, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        boolean wrote = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("pin")) {
                if (pinned) {
                    out.append("pin = true\n");
                }
                wrote = true;
                continue;
            }
            out.append(line).append('\n');
            if (!wrote && pinned && trimmed.startsWith("side")) {
                out.append("pin = true\n");
                wrote = true;
            }
        }
        if (pinned && !wrote) out.append("pin = true\n");
        Files.writeString(metaFile, out.toString(), StandardCharsets.UTF_8);
    }
}
