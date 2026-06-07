package link.infra.packwiz.installer.project;

import link.infra.packwiz.installer.metadata.hash.HashFormat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public class MetadataWriter {
    private final PackRepository repository;

    public MetadataWriter(PackRepository repository) {
        this.repository = repository;
    }

    public Path writeUrlMetadata(String category, String name, String filename, String url, HashFormat hashFormat, String hash,
                                 String side, boolean optional, boolean defaultEnabled) throws IOException {
        String folder = normalizeCategory(category);
        String slug = slugify(name.isBlank() ? filename : name);
        Path meta = repository.root().resolve(folder).resolve(".index").resolve(slug + ".pw.toml");
        Files.createDirectories(meta.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("name = ").append(TomlUtil.quote(name)).append('\n');
        sb.append("filename = ").append(TomlUtil.quote(filename)).append('\n');
        sb.append("side = ").append(TomlUtil.quote(side)).append("\n\n");
        sb.append("[download]\n");
        sb.append("url = ").append(TomlUtil.quote(url)).append('\n');
        sb.append("hash-format = ").append(TomlUtil.quote(TomlUtil.hashFormatName(hashFormat))).append('\n');
        sb.append("hash = ").append(TomlUtil.quote(hash)).append('\n');
        sb.append("mode = \"url\"\n");
        appendOption(sb, optional, defaultEnabled);
        Files.writeString(meta, sb.toString(), StandardCharsets.UTF_8);
        return meta;
    }

    public Path writeCurseForgeMetadata(String category, String name, String filename, int projectId, int fileId,
                                        String hash, String side, boolean optional, boolean defaultEnabled) throws IOException {
        String folder = normalizeCategory(category);
        String slug = slugify(name.isBlank() ? filename : name);
        Path meta = repository.root().resolve(folder).resolve(".index").resolve(slug + ".pw.toml");
        return writeCurseForgeMetadataTo(meta, name, filename, projectId, fileId, hash, side, optional, defaultEnabled);
    }

    public Path writeCurseForgeMetadataAt(String metaPath, String name, String filename, int projectId, int fileId,
                                          String hash, String side, boolean optional, boolean defaultEnabled,
                                          boolean pinned) throws IOException {
        Path meta = repository.root().resolve(metaPath.replace('/', java.io.File.separatorChar)).normalize();
        if (!meta.startsWith(repository.root())) {
            throw new IOException("元数据路径位于项目目录外: " + metaPath);
        }
        writeCurseForgeMetadataTo(meta, name, filename, projectId, fileId, hash, side, optional, defaultEnabled);
        if (pinned) writePinned(meta);
        return meta;
    }

    public Path writeCurseForgeMetadata(String category, String name, String filename, int projectId, int fileId,
                                        String hash, String side, boolean optional, boolean defaultEnabled,
                                        boolean pinned) throws IOException {
        Path meta = writeCurseForgeMetadata(category, name, filename, projectId, fileId, hash, side, optional, defaultEnabled);
        if (pinned) writePinned(meta);
        return meta;
    }

    private Path writeCurseForgeMetadataTo(Path meta, String name, String filename, int projectId, int fileId,
                                           String hash, String side, boolean optional, boolean defaultEnabled) throws IOException {
        Files.createDirectories(meta.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("name = ").append(TomlUtil.quote(name)).append('\n');
        sb.append("filename = ").append(TomlUtil.quote(filename)).append('\n');
        sb.append("side = ").append(TomlUtil.quote(side)).append("\n\n");
        sb.append("[download]\n");
        sb.append("hash-format = \"sha1\"\n");
        sb.append("hash = ").append(TomlUtil.quote(hash)).append('\n');
        sb.append("mode = \"metadata:curseforge\"\n\n");
        sb.append("[update.curseforge]\n");
        sb.append("file-id = ").append(fileId).append('\n');
        sb.append("project-id = ").append(projectId).append('\n');
        appendOption(sb, optional, defaultEnabled);
        Files.writeString(meta, sb.toString(), StandardCharsets.UTF_8);
        return meta;
    }

    private void writePinned(Path meta) throws IOException {
        List<String> lines = Files.readAllLines(meta, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        boolean wrote = false;
        for (String line : lines) {
            out.append(line).append('\n');
            if (!wrote && line.startsWith("side")) {
                out.append("pin = true\n");
                wrote = true;
            }
        }
        if (!wrote) out.append("pin = true\n");
        Files.writeString(meta, out.toString(), StandardCharsets.UTF_8);
    }

    public static String filenameFromUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        } catch (Exception e) {
            int slash = url.lastIndexOf('/');
            return slash >= 0 ? url.substring(slash + 1) : url;
        }
    }

    private void appendOption(StringBuilder sb, boolean optional, boolean defaultEnabled) {
        if (!optional) return;
        sb.append("\n[option]\n");
        sb.append("optional = true\n");
        sb.append("default = ").append(defaultEnabled ? "true" : "false").append('\n');
    }

    private String normalizeCategory(String category) {
        String value = category == null ? "mods" : category.trim().replace('\\', '/');
        if (value.isBlank()) return "mods";
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "mod", "mods" -> "mods";
            case "resourcepack", "resourcepacks", "resource packs" -> "resourcepacks";
            case "shaderpack", "shaderpacks", "shader packs" -> "shaderpacks";
            default -> value;
        };
    }

    private String slugify(String value) {
        String lower = value.toLowerCase(Locale.ROOT)
            .replaceAll("\\.(jar|zip)$", "")
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("^-+|-+$", "");
        return lower.isBlank() ? "file" : lower;
    }
}
