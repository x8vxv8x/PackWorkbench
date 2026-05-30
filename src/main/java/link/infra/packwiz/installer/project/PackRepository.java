package link.infra.packwiz.installer.project;

import link.infra.packwiz.installer.metadata.IndexFile;
import link.infra.packwiz.installer.metadata.ModFile;
import link.infra.packwiz.installer.metadata.PackFile;
import link.infra.packwiz.installer.metadata.hash.Hash;
import link.infra.packwiz.installer.metadata.hash.HashFormat;
import link.infra.packwiz.installer.target.path.PackwizFilePath;
import link.infra.packwiz.installer.target.path.PackwizPath;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PackRepository {
    private final Path root;
    private final Path packToml;
    private final PackwizFilePath packPath;

    public PackRepository(Path root) {
        this(root, root.resolve("pack.toml"));
    }

    public PackRepository(Path root, Path packToml) {
        this.root = root.toAbsolutePath().normalize();
        this.packToml = packToml.toAbsolutePath().normalize();
        this.packPath = new PackwizFilePath(this.packToml.getParent(), this.packToml.getFileName().toString());
    }

    public Path root() {
        return root;
    }

    public Path packToml() {
        return packToml;
    }

    public PackwizFilePath rootPath() {
        return new PackwizFilePath(root);
    }

    public PackwizFilePath packPath() {
        return packPath;
    }

    public PackFile loadPack() throws IOException {
        try (InputStream in = Files.newInputStream(packToml)) {
            return PackFile.fromToml(in, packPath);
        }
    }

    public IndexFile loadIndex(PackFile pack) throws IOException {
        if (pack.index == null || pack.index.file() == null) {
            throw new IOException("pack.toml 缺少 [index].file");
        }
        Path indexPath = resolvePackwizPath(pack.index.file());
        try (InputStream in = Files.newInputStream(indexPath)) {
            return IndexFile.fromToml(in, new PackwizFilePath(indexPath.getParent()));
        }
    }

    public IndexFile loadIndexWithMetadata(PackFile pack) throws IOException {
        IndexFile index = loadIndex(pack);
        for (var entry : index.files) {
            if (!entry.metafile) continue;
            try {
                entry.readLocalMeta(index, rootPath());
            } catch (Exception ignored) {
                // The table view should still show the index entry if one metadata file is broken.
            }
        }
        return index;
    }

    public void writeIndex(IndexFile index, HashFormat defaultHashFormat) throws IOException {
        Path indexPath = root.resolve("index.toml");
        Files.writeString(indexPath, serializeIndex(index, rootPath(), defaultHashFormat), StandardCharsets.UTF_8);
        updatePackIndexHash(indexPath, defaultHashFormat);
    }

    public void updatePackIndexHash(Path indexPath, HashFormat format) throws IOException {
        String digest = hashFile(indexPath, format);
        List<String> lines = Files.readAllLines(packToml, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        boolean inIndex = false;
        boolean wroteHash = false;
        boolean wroteHashFormat = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                if (inIndex) {
                    if (!wroteHashFormat) out.append("hash-format = ").append(TomlUtil.quote(TomlUtil.hashFormatName(format))).append('\n');
                    if (!wroteHash) out.append("hash = ").append(TomlUtil.quote(digest)).append('\n');
                }
                inIndex = "[index]".equals(trimmed);
            }
            if (inIndex && trimmed.startsWith("hash-format")) {
                out.append("hash-format = ").append(TomlUtil.quote(TomlUtil.hashFormatName(format))).append('\n');
                wroteHashFormat = true;
                continue;
            }
            if (inIndex && trimmed.startsWith("hash")) {
                out.append("hash = ").append(TomlUtil.quote(digest)).append('\n');
                wroteHash = true;
                continue;
            }
            out.append(line).append('\n');
        }
        if (inIndex) {
            if (!wroteHashFormat) out.append("hash-format = ").append(TomlUtil.quote(TomlUtil.hashFormatName(format))).append('\n');
            if (!wroteHash) out.append("hash = ").append(TomlUtil.quote(digest)).append('\n');
        }
        Files.writeString(packToml, out.toString(), StandardCharsets.UTF_8);
    }

    public int getCurseForgeProjectId(PackFile pack) {
        if (pack.export == null) return 0;
        Map<String, Object> curseforge = pack.export.get("curseforge");
        if (curseforge == null) return 0;
        Object value = curseforge.get("project-id");
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    public void setCurseForgeProjectId(int projectId) throws IOException {
        List<String> lines = Files.exists(packToml)
            ? Files.readAllLines(packToml, StandardCharsets.UTF_8)
            : new ArrayList<>();
        StringBuilder out = new StringBuilder();
        boolean inTarget = false;
        boolean foundTarget = false;
        boolean wroteProjectId = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                if (inTarget && !wroteProjectId) {
                    out.append("project-id = ").append(projectId).append('\n');
                    wroteProjectId = true;
                }
                inTarget = "[export.curseforge]".equals(trimmed);
                if (inTarget) foundTarget = true;
            }
            if (inTarget && trimmed.startsWith("project-id")) {
                out.append("project-id = ").append(projectId).append('\n');
                wroteProjectId = true;
                continue;
            }
            out.append(line).append('\n');
        }
        if (inTarget && !wroteProjectId) {
            out.append("project-id = ").append(projectId).append('\n');
        } else if (!foundTarget) {
            if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') out.append('\n');
            out.append("\n[export.curseforge]\n");
            out.append("project-id = ").append(projectId).append('\n');
        }
        Files.writeString(packToml, out.toString(), StandardCharsets.UTF_8);
    }

    public Path resolvePackwizPath(PackwizPath<?> path) {
        String raw = path.path();
        if (raw == null || raw.isBlank()) return root;
        return root.resolve(raw.replace('/', java.io.File.separatorChar)).normalize();
    }

    public String relativize(Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    public String hashFile(Path path, HashFormat format) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             Hash.HashingInputStream hashing = format.createSource(in)) {
            hashing.transferTo(OutputStream.nullOutputStream());
            return new Hash<>(hashing.getDigest(), format.encoding()).toString();
        }
    }

    public static String serializeIndex(IndexFile index, PackwizFilePath base, HashFormat defaultHashFormat) {
        var entries = index.files.stream()
            .sorted(Comparator.comparing(e -> normalize(e.file.rebase(base).path())))
            .toList();
        StringBuilder sb = new StringBuilder();
        sb.append("hash-format = ").append(TomlUtil.quote(TomlUtil.hashFormatName(defaultHashFormat))).append("\n\n");
        for (var entry : entries) {
            sb.append("[[files]]\n");
            sb.append("file = ").append(TomlUtil.quote(normalize(entry.file.rebase(base).path()))).append('\n');
            if (entry.fileHashFormat != null && entry.fileHashFormat != defaultHashFormat) {
                sb.append("hash-format = ").append(TomlUtil.quote(TomlUtil.hashFormatName(entry.fileHashFormat))).append('\n');
            }
            sb.append("hash = ").append(TomlUtil.quote(entry.hash)).append('\n');
            if (entry.alias != null) sb.append("alias = ").append(TomlUtil.quote(normalize(entry.alias.rebase(base).path()))).append('\n');
            if (entry.metafile) sb.append("metafile = true\n");
            if (entry.preserve) sb.append("preserve = true\n");
            if (entry.optional) sb.append("optional = true\n");
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String sideName(ModFile mod) {
        if (mod == null || mod.side == null) return "both";
        return mod.side.name().toLowerCase(Locale.ROOT);
    }

    private static String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }
}
