package link.infra.packwiz.installer.project;

import link.infra.packwiz.installer.metadata.IndexFile;
import link.infra.packwiz.installer.metadata.ModFile;
import link.infra.packwiz.installer.metadata.PackFile;
import link.infra.packwiz.installer.metadata.curseforge.CurseForgeSourcer;
import link.infra.packwiz.installer.metadata.curseforge.CurseForgeUpdateData;
import link.infra.packwiz.installer.target.ClientHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class CurseForgeProjectService {
    private static final int MAX_DEPENDENCY_CYCLES = 20;
    private final PackRepository repository;

    public CurseForgeProjectService(PackRepository repository) {
        this.repository = repository;
    }

    public List<Path> addProjectWithDependencies(String input, String category, String side,
                                                 boolean optional, boolean defaultEnabled) throws Exception {
        PackFile pack = repository.loadPack();
        var written = new ArrayList<Path>();
        var installed = installedCurseForgeProjectIds(pack);
        try (var holder = new CloseableClientHolder()) {
            var main = CurseForgeSourcer.resolveProject(input, pack.supportedMinecraftVersions(), loaders(pack), holder.client());
            written.add(write(main, category, side, optional, defaultEnabled));
            installed.add(main.projectId());
            var queue = new ArrayList<Integer>();
            for (int depId : main.requiredDependencies()) {
                queue.add(mapDependency(depId, pack));
            }
            int cycles = 0;
            while (!queue.isEmpty() && cycles < MAX_DEPENDENCY_CYCLES) {
                queue.removeIf(installed::contains);
                if (queue.isEmpty()) break;
                var current = new ArrayList<>(queue);
                queue.clear();
                for (int depId : current) {
                    if (installed.contains(depId)) continue;
                    var dep = CurseForgeSourcer.resolveProject(String.valueOf(depId), pack.supportedMinecraftVersions(), loaders(pack), holder.client());
                    written.add(write(dep, "mods", "both", false, true));
                    installed.add(dep.projectId());
                    for (int next : dep.requiredDependencies()) {
                        int mapped = mapDependency(next, pack);
                        if (!installed.contains(mapped) && !queue.contains(mapped)) queue.add(mapped);
                    }
                }
                cycles++;
            }
            if (cycles >= MAX_DEPENDENCY_CYCLES && !queue.isEmpty()) {
                throw new IllegalStateException("CurseForge 依赖递归过深，已停止在 " + MAX_DEPENDENCY_CYCLES + " 轮");
            }
        }
        new IndexRefresher(repository).refreshAndWrite();
        return written;
    }

    public DetectReport detectLocalJars(Path folder, boolean deleteMatchedFiles) throws Exception {
        PackFile pack = repository.loadPack();
        var fingerprints = new HashMap<Long, Path>();
        if (Files.isDirectory(folder)) {
            try (var stream = Files.list(folder)) {
                for (Path path : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .toList()) {
                    fingerprints.put(CurseForgeSourcer.fingerprint(path), path);
                }
            }
        }
        if (fingerprints.isEmpty()) return new DetectReport(0, 0, 0, List.of());
        var installed = installedCurseForgeProjectIds(pack);
        var written = new ArrayList<Path>();
        int skippedInstalled = 0;
        try (var holder = new CloseableClientHolder()) {
            var report = CurseForgeSourcer.matchFingerprints(fingerprints, holder.client());
            for (var match : report.matches()) {
                if (installed.contains(match.projectId())) {
                    skippedInstalled++;
                    continue;
                }
                written.add(new MetadataWriter(repository).writeCurseForgeMetadata(
                    match.category(),
                    match.projectName(),
                    match.filename(),
                    match.projectId(),
                    match.fileId(),
                    match.sha1(),
                    "both",
                    false,
                    true
                ));
                installed.add(match.projectId());
                if (deleteMatchedFiles) {
                    Path file = fingerprints.get(match.fingerprint());
                    if (file != null) Files.deleteIfExists(file);
                }
            }
            new IndexRefresher(repository).refreshAndWrite();
            return new DetectReport(fingerprints.size(), written.size(), skippedInstalled, report.unmatched());
        }
    }

    public List<UpdateResult> checkAllUpdates() throws Exception {
        PackFile pack = repository.loadPack();
        IndexFile index = repository.loadIndexWithMetadata(pack);
        var results = new ArrayList<UpdateResult>();
        try (var holder = new CloseableClientHolder()) {
            for (var entry : index.files) {
                if (!entry.metafile || entry.linkedFile == null) continue;
                UpdateResult result = checkUpdate(entry, pack, holder.client());
                if (result != null) results.add(result);
            }
        }
        return results;
    }

    public UpdateResult checkSingleUpdate(IndexFile.FileEntry entry) throws Exception {
        PackFile pack = repository.loadPack();
        try (var holder = new CloseableClientHolder()) {
            return checkUpdate(entry, pack, holder.client());
        }
    }

    public void applyUpdate(UpdateResult update) throws Exception {
        MetadataWriter writer = new MetadataWriter(repository);
        writer.writeCurseForgeMetadata(
            categoryForPath(update.metaPath()),
            update.name(),
            update.newFilename(),
            update.projectId(),
            update.newFileId(),
            update.newSha1(),
            update.side(),
            update.optional(),
            update.defaultEnabled(),
            update.pinned()
        );
        new IndexRefresher(repository).refreshAndWrite();
    }

    private UpdateResult checkUpdate(IndexFile.FileEntry entry, PackFile pack, ClientHolder holder) throws Exception {
        ModFile mod = entry.linkedFile;
        if (mod == null || mod.update.get("curseforge") == null) return null;
        if (mod.pin) {
            return new UpdateResult(entry.file.rebase(repository.rootPath()).path(), mod.name,
                0, 0, 0, mod.filename != null ? mod.filename.filename() : "", "",
                "已锁定，跳过更新", true, PackRepository.sideName(mod),
                mod.option != null && mod.option.optional(),
                mod.option != null && mod.option.defaultValue());
        }
        CurseForgeUpdateData cf = (CurseForgeUpdateData) mod.update.get("curseforge");
        var latest = CurseForgeSourcer.checkLatest(cf.projectId(), cf.fileId(),
            pack.supportedMinecraftVersions(), loaders(pack), holder);
        if (latest == null) {
            return new UpdateResult(entry.file.rebase(repository.rootPath()).path(), mod.name,
                cf.projectId(), cf.fileId(), cf.fileId(),
                mod.filename != null ? mod.filename.filename() : "", mod.download != null ? mod.download.hash() : "",
                "已是最新", false, PackRepository.sideName(mod),
                mod.option != null && mod.option.optional(),
                mod.option != null && mod.option.defaultValue());
        }
        return new UpdateResult(entry.file.rebase(repository.rootPath()).path(), latest.name(),
            latest.projectId(), cf.fileId(), latest.fileId(), latest.filename(), latest.sha1(),
            "可更新: " + (mod.filename != null ? mod.filename.filename() : mod.name) + " -> " + latest.filename(),
            false, PackRepository.sideName(mod),
            mod.option != null && mod.option.optional(),
            mod.option != null && mod.option.defaultValue());
    }

    private Path write(CurseForgeSourcer.CurseForgeProjectFile file, String category, String side,
                       boolean optional, boolean defaultEnabled) throws Exception {
        return new MetadataWriter(repository).writeCurseForgeMetadata(
            normalizeCategory(category, file.category()),
            file.name(),
            file.filename(),
            file.projectId(),
            file.fileId(),
            file.sha1(),
            side,
            optional,
            defaultEnabled
        );
    }

    private LinkedHashSet<Integer> installedCurseForgeProjectIds(PackFile pack) throws Exception {
        IndexFile index = repository.loadIndexWithMetadata(pack);
        var ids = new LinkedHashSet<Integer>();
        for (var entry : index.files) {
            if (entry.linkedFile != null && entry.linkedFile.update.get("curseforge") instanceof CurseForgeUpdateData cf) {
                ids.add(cf.projectId());
            }
        }
        return ids;
    }

    private List<String> loaders(PackFile pack) {
        var loaders = new ArrayList<String>();
        for (String key : List.of("neoforge", "quilt", "fabric", "forge")) {
            if (pack.versions.containsKey(key)) loaders.add(key);
        }
        return loaders;
    }

    private int mapDependency(int depId, PackFile pack) {
        return CurseForgeSourcer.mapDependencyOverride(
            depId,
            loaders(pack).contains("quilt"),
            pack.versions.get("minecraft")
        );
    }

    private String normalizeCategory(String preferred, String cfCategory) {
        if (preferred != null && !preferred.isBlank()) return preferred;
        return switch (cfCategory) {
            case "texture-packs" -> "resourcepacks";
            case "shaders" -> "shaderpacks";
            default -> "mods";
        };
    }

    private String categoryForPath(String metaPath) {
        if (metaPath == null) return "mods";
        if (metaPath.startsWith("resourcepacks/")) return "resourcepacks";
        if (metaPath.startsWith("shaderpacks/")) return "shaderpacks";
        return "mods";
    }

    public record UpdateResult(
        String metaPath,
        String name,
        int projectId,
        int oldFileId,
        int newFileId,
        String newFilename,
        String newSha1,
        String message,
        boolean pinned,
        String side,
        boolean optional,
        boolean defaultEnabled
    ) {
        public boolean updateAvailable() {
            return !pinned && projectId > 0 && newFileId > 0 && newFileId != oldFileId;
        }
    }

    public record DetectReport(
        int scanned,
        int matched,
        int skippedInstalled,
        List<Long> unmatched
    ) {}

    private static class CloseableClientHolder implements AutoCloseable {
        private final ClientHolder client = new ClientHolder();

        ClientHolder client() {
            return client;
        }

        @Override
        public void close() {
            client.close();
        }
    }
}
