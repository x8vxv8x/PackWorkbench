package link.infra.packwiz.installer.exporter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import link.infra.packwiz.installer.config.InstallerConfig;
import link.infra.packwiz.installer.metadata.DownloadMode;
import link.infra.packwiz.installer.metadata.IndexFile;
import link.infra.packwiz.installer.metadata.ModFile;
import link.infra.packwiz.installer.metadata.PackFile;
import link.infra.packwiz.installer.metadata.curseforge.CurseForgeUpdateData;
import link.infra.packwiz.installer.project.IndexRefresher;
import link.infra.packwiz.installer.project.IgnoreRules;
import link.infra.packwiz.installer.project.PackRepository;
import link.infra.packwiz.installer.target.ClientHolder;
import link.infra.packwiz.installer.target.Side;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class CurseForgeExportBuilder {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final PackRepository repository;

    public CurseForgeExportBuilder(PackRepository repository) {
        this.repository = repository;
    }

    public ExportReport export(String sideName, Path output, int projectId) throws Exception {
        new IndexRefresher(repository).refreshAndWrite();
        PackFile pack = repository.loadPack();
        IndexFile index = repository.loadIndexWithMetadata(pack);
        Side side = Side.from(sideName == null ? "client" : sideName);
        if (side == null) side = Side.CLIENT;
        Path out = output != null ? output : repository.root().resolve(defaultOutputName(pack));
        Files.createDirectories(out.toAbsolutePath().normalize().getParent());

        var cfFiles = new ArrayList<ManifestFileRef>();
        var modList = new ArrayList<ModListEntry>();
        List<OverrideSource> overrideEntries;

        var clientHolder = new ClientHolder();
        try {
            for (var entry : index.files) {
                if (!includedForSide(entry, side)) continue;
                if (entry.metafile && entry.linkedFile != null) {
                    ModFile mod = entry.linkedFile;
                    modList.add(ModListEntry.from(entry));
                    var cf = mod.update.get("curseforge");
                    if (cf instanceof CurseForgeUpdateData cfData) {
                        cfFiles.add(new ManifestFileRef(cfData.projectId(), cfData.fileId(),
                            !(mod.option != null && mod.option.optional() && !mod.option.defaultValue())));
                    }
                }
            }

            overrideEntries = collectOverrides(out);
            try (OutputStream fileOut = Files.newOutputStream(out);
                 ZipOutputStream zip = new ZipOutputStream(fileOut, StandardCharsets.UTF_8)) {
                addDirectory(zip, "overrides/");
                for (OverrideSource source : overrideEntries) {
                    addStream(zip, "overrides/" + normalizeZipPath(source.path()), source.open());
                }
                addString(zip, "manifest.json", GSON.toJson(createManifest(pack, cfFiles, projectId)));
                addString(zip, "modlist.html", createModlist(modList));
            }
        } finally {
            clientHolder.close();
        }

        return new ExportReport(out, cfFiles.size(), overrideEntries.size(), modList.size());
    }

    private List<OverrideSource> collectOverrides(Path output) throws IOException {
        InstallerConfig.ensureConfigDir(repository.root());
        IgnoreRules ignore = IgnoreRules.load(
            repository.root(),
            InstallerConfig.getOverridesIgnoreFile(repository.root()),
            defaultOverrideIgnorePatterns(output)
        );
        var overrides = new ArrayList<OverrideSource>();
        try (var stream = Files.walk(repository.root())) {
            var files = stream
                .filter(Files::isRegularFile)
                .sorted(java.util.Comparator.comparing(repository::relativize))
                .toList();
            for (Path file : files) {
                String rel = repository.relativize(file);
                if (ignore.matches(rel, false)) continue;
                overrides.add(new OverrideSource(rel, () -> Files.newInputStream(file)));
            }
        }
        return overrides;
    }

    private List<String> defaultOverrideIgnorePatterns(Path output) {
        var patterns = new ArrayList<>(List.of(
            ".git/",
            ".gradle/",
            ".idea/",
            "build/",
            "gradle/",
            "src/",
            ".packwiz-installer/",
            "pack.toml",
            "index.toml",
            "PLAN.md",
            "README.md",
            "LICENSE",
            "*.zip",
            "mods/.index/",
            "resourcepacks/.index/",
            "shaderpacks/.index/",
            "mods/*.jar"
        ));
        if (output != null && output.toAbsolutePath().normalize().startsWith(repository.root())) {
            patterns.add(repository.relativize(output));
        }
        return patterns;
    }

    private boolean includedForSide(IndexFile.FileEntry entry, Side target) {
        if (target == Side.BOTH) return true;
        if (entry.linkedFile == null || entry.linkedFile.side == null) return true;
        return entry.linkedFile.side.hasSide(target);
    }

    private Manifest createManifest(PackFile pack, List<ManifestFileRef> files, int projectId) {
        var manifest = new Manifest();
        manifest.minecraft.version = pack.versions.getOrDefault("minecraft", "");
        String loader = primaryLoader(pack.versions);
        if (loader != null) {
            manifest.minecraft.modLoaders.add(new ModLoader(loader, true));
        }
        manifest.manifestType = "minecraftModpack";
        manifest.manifestVersion = 1;
        manifest.name = pack.name != null ? pack.name : "pack";
        manifest.version = pack.version != null ? pack.version : "";
        manifest.author = pack.author != null ? pack.author : "";
        manifest.projectID = projectId;
        manifest.files = files;
        manifest.overrides = "overrides";
        return manifest;
    }

    private String primaryLoader(Map<String, String> versions) {
        if (versions.containsKey("fabric")) return "fabric-" + versions.get("fabric");
        if (versions.containsKey("forge")) return "forge-" + versions.get("forge");
        if (versions.containsKey("neoforge")) return "neoforge-" + versions.get("neoforge");
        if (versions.containsKey("quilt")) return "quilt-" + versions.get("quilt");
        return null;
    }

    private String createModlist(List<ModListEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("<ul>\r\n");
        for (var entry : entries) {
            sb.append("<li>");
            if (entry.projectId() > 0) {
                sb.append("<a href=\"https://www.curseforge.com/projects/")
                    .append(entry.projectId())
                    .append("\">")
                    .append(escapeHtml(entry.name()))
                    .append("</a>");
            } else {
                sb.append(escapeHtml(entry.name()));
            }
            sb.append("</li>\r\n");
        }
        sb.append("</ul>\r\n");
        return sb.toString();
    }

    private String defaultOutputName(PackFile pack) {
        String name = pack.name == null || pack.name.isBlank() ? "modpack" : pack.name;
        return name.replaceAll("[\\\\/:*?\"<>|]+", "-") + ".zip";
    }

    private void addDirectory(ZipOutputStream zip, String path) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.closeEntry();
    }

    private void addString(ZipOutputStream zip, String path, String content) throws IOException {
        addStream(zip, path, new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    private void addStream(ZipOutputStream zip, String path, InputStream input) throws IOException {
        ZipEntry entry = new ZipEntry(normalizeZipPath(path));
        zip.putNextEntry(entry);
        try (input) {
            input.transferTo(zip);
        }
        zip.closeEntry();
    }

    private String normalizeZipPath(String path) {
        return path.replace('\\', '/').replaceAll("^/+", "");
    }

    private String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    public record ExportReport(Path output, int curseForgeFiles, int overrideFiles, int listedMods) {}

    private record OverrideSource(String path, InputSupplier supplier) {
        InputStream open() throws IOException {
            try {
                return supplier.open();
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }

    @FunctionalInterface
    private interface InputSupplier {
        InputStream open() throws Exception;
    }

    private record ModListEntry(String name, int projectId) {
        static ModListEntry from(IndexFile.FileEntry entry) {
            String name = entry.getName();
            int project = 0;
            if (entry.linkedFile != null && entry.linkedFile.update.get("curseforge") instanceof CurseForgeUpdateData cf) {
                project = cf.projectId();
            }
            return new ModListEntry(name, project);
        }
    }

    private static class Manifest {
        Minecraft minecraft = new Minecraft();
        String manifestType;
        int manifestVersion;
        String name;
        String version;
        String author;
        int projectID;
        List<ManifestFileRef> files = new ArrayList<>();
        String overrides;
    }

    private static class Minecraft {
        String version;
        List<ModLoader> modLoaders = new ArrayList<>();
    }

    private record ModLoader(String id, boolean primary) {}

    private record ManifestFileRef(
        @SerializedName("projectID") int projectId,
        @SerializedName("fileID") int fileId,
        boolean required
    ) {}
}
