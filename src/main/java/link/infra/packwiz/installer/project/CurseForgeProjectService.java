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
import java.util.Objects;
import java.util.Set;

public class CurseForgeProjectService {
    private static final int MAX_DEPENDENCY_CYCLES = 20;
    private final PackRepository repository;

    public CurseForgeProjectService(PackRepository repository) {
        this.repository = repository;
    }

    public CurseForgeSourcer.CurseForgeProjectFile resolveProjectPreview(String input) throws Exception {
        PackFile pack = repository.loadPack();
        try (var holder = new CloseableClientHolder()) {
            return CurseForgeSourcer.resolveProject(input, pack.supportedMinecraftVersions(), loaders(pack), holder.client());
        }
    }

    public ImportPlan planProjectImport(String input, String category, String side,
                                        boolean optional, boolean defaultEnabled) throws Exception {
        PackFile pack = repository.loadPack();
        var installed = installedCurseForgeProjectIds(pack);
        try (var holder = new CloseableClientHolder()) {
            var main = CurseForgeSourcer.resolveProject(input, pack.supportedMinecraftVersions(), loaders(pack), holder.client());
            var mainEntry = new ImportEntry(
                main,
                normalizeCategory(category, main.category()),
                side,
                optional,
                defaultEnabled,
                installed.contains(main.projectId())
            );
            var dependencies = resolveDependencies(main, pack, installed, side, holder.client());
            return new ImportPlan(mainEntry, dependencies);
        }
    }

    public List<Path> importProject(ImportPlan plan, List<Integer> selectedDependencyProjectIds) throws Exception {
        if (plan == null) throw new IllegalArgumentException("导入计划不能为空");
        PackFile pack = repository.loadPack();
        var written = new ArrayList<Path>();
        var installed = installedCurseForgeProjectIds(pack);

        written.add(write(
            plan.main().file(),
            plan.main().category(),
            plan.main().side(),
            plan.main().optional(),
            plan.main().defaultEnabled()
        ));
        installed.add(plan.main().projectId());

        Set<Integer> selected = selectedDependencyProjectIds == null
            ? Set.of()
            : new LinkedHashSet<>(selectedDependencyProjectIds);
        for (var dependency : plan.dependencies()) {
            if (!selected.contains(dependency.projectId()) || dependency.alreadyInstalled() || installed.contains(dependency.projectId())) continue;
            written.add(write(
                dependency.file(),
                dependency.category(),
                dependency.side(),
                dependency.optional(),
                dependency.defaultEnabled()
            ));
            installed.add(dependency.projectId());
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
                Path file = fingerprints.get(match.fingerprint());
                if (file == null && match.fingerprint() == 0) {
                    file = findMatchedFileByFileId(fingerprints, report.matches(), match.fileId());
                }
                if (installed.contains(match.projectId())) {
                    skippedInstalled++;
                    if (deleteMatchedFiles && file != null) Files.deleteIfExists(file);
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
                    if (file != null) Files.deleteIfExists(file);
                }
            }
            new IndexRefresher(repository).refreshAndWrite();
            return new DetectReport(fingerprints.size(), written.size(), skippedInstalled, report.unmatched());
        }
    }

    private Path findMatchedFileByFileId(Map<Long, Path> fingerprints,
                                         List<CurseForgeSourcer.FingerprintMatch> matches,
                                         int fileId) {
        for (var match : matches) {
            if (match.fileId() == fileId && fingerprints.containsKey(match.fingerprint())) {
                return fingerprints.get(match.fingerprint());
            }
        }
        return null;
    }

    public List<UpdateResult> checkAllUpdates() throws Exception {
        PackFile pack = repository.loadPack();
        IndexFile index = repository.loadIndexWithMetadata(pack);
        var results = new ArrayList<UpdateResult>();
        try (var holder = new CloseableClientHolder()) {
            var candidates = new ArrayList<IndexFile.FileEntry>();
            var projectIds = new LinkedHashSet<Integer>();
            for (var entry : index.files) {
                if (!entry.metafile || entry.linkedFile == null) continue;
                ModFile mod = entry.linkedFile;
                if (mod.update.get("curseforge") == null) continue;
                if (mod.pin) {
                    results.add(pinnedResult(entry, mod));
                    continue;
                }
                CurseForgeUpdateData cf = (CurseForgeUpdateData) mod.update.get("curseforge");
                candidates.add(entry);
                projectIds.add(cf.projectId());
            }
            var latestByProject = CurseForgeSourcer.checkLatestMultipleByProject(
                projectIds,
                pack.supportedMinecraftVersions(),
                loaders(pack),
                holder.client()
            );
            for (var entry : candidates) {
                UpdateResult result = checkUpdate(entry, latestByProject);
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
        try (var holder = new CloseableClientHolder()) {
            writeUpdate(writer, hydrateUpdate(update, holder.client()));
        }
        new IndexRefresher(repository).refreshAndWrite();
    }

    public void applyUpdates(List<UpdateResult> updates) throws Exception {
        var applicable = updates.stream()
            .filter(UpdateResult::updateAvailable)
            .toList();
        if (applicable.isEmpty()) return;
        MetadataWriter writer = new MetadataWriter(repository);
        try (var holder = new CloseableClientHolder()) {
            var fileIds = applicable.stream().map(UpdateResult::newFileId).toList();
            var files = CurseForgeSourcer.resolveProjectFiles(fileIds, holder.client());
            for (UpdateResult update : applicable) {
                var file = files.get(update.newFileId());
                writeUpdate(writer, file != null ? update.withFile(file) : update);
            }
        }
        new IndexRefresher(repository).refreshAndWrite();
    }

    public String loadUpdateChangelog(UpdateResult update) throws Exception {
        if (update == null || !update.updateAvailable()) {
            throw new IllegalArgumentException("该条目没有可加载的 CurseForge 更新日志");
        }
        try (var holder = new CloseableClientHolder()) {
            return CurseForgeSourcer.getFileChangelog(update.projectId(), update.newFileId(), holder.client());
        }
    }

    private UpdateResult checkUpdate(IndexFile.FileEntry entry, PackFile pack, ClientHolder holder) throws Exception {
        ModFile mod = entry.linkedFile;
        if (mod == null || mod.update.get("curseforge") == null) return null;
        if (mod.pin) {
            return pinnedResult(entry, mod);
        }
        CurseForgeUpdateData cf = (CurseForgeUpdateData) mod.update.get("curseforge");
        var latest = CurseForgeSourcer.checkLatest(cf.projectId(), cf.fileId(),
            pack.supportedMinecraftVersions(), loaders(pack), holder);
        return updateResult(entry, mod, cf, latest, null);
    }

    private UpdateResult checkUpdate(IndexFile.FileEntry entry,
                                     Map<Integer, CurseForgeSourcer.LatestProjectFileResult> latestByProject) {
        ModFile mod = entry.linkedFile;
        if (mod == null || mod.update.get("curseforge") == null) return null;
        CurseForgeUpdateData cf = (CurseForgeUpdateData) mod.update.get("curseforge");
        var latest = latestByProject.get(cf.projectId());
        if (latest == null) {
            return updateResult(entry, mod, cf, null, new IllegalStateException("CurseForge 未返回项目数据"));
        }
        return updateResult(entry, mod, cf, latest.file(), latest.error());
    }

    private UpdateResult pinnedResult(IndexFile.FileEntry entry, ModFile mod) {
            return new UpdateResult(entry.file.rebase(repository.rootPath()).path(), mod.name,
                0, 0, 0, mod.filename != null ? mod.filename.filename() : "", "",
                mod.filename != null ? mod.filename.filename() : "",
                "已锁定，跳过更新", true, PackRepository.sideName(mod),
                mod.option != null && mod.option.optional(),
                mod.option != null && mod.option.defaultValue());
    }

    private UpdateResult updateResult(IndexFile.FileEntry entry, ModFile mod, CurseForgeUpdateData cf,
                                      CurseForgeSourcer.CurseForgeProjectFile latest, Exception error) {
        if (error != null) {
            return new UpdateResult(entry.file.rebase(repository.rootPath()).path(), mod.name,
                cf.projectId(), cf.fileId(), cf.fileId(),
                mod.filename != null ? mod.filename.filename() : "", mod.download != null ? mod.download.hash() : "",
                mod.filename != null ? mod.filename.filename() : "",
                "检查失败: " + Objects.toString(error.getMessage(), error.getClass().getSimpleName()),
                false, PackRepository.sideName(mod),
                mod.option != null && mod.option.optional(),
                mod.option != null && mod.option.defaultValue());
        }
        if (latest == null) {
            return new UpdateResult(entry.file.rebase(repository.rootPath()).path(), mod.name,
                cf.projectId(), cf.fileId(), cf.fileId(),
                mod.filename != null ? mod.filename.filename() : "", mod.download != null ? mod.download.hash() : "",
                mod.filename != null ? mod.filename.filename() : "",
                "已是最新", false, PackRepository.sideName(mod),
                mod.option != null && mod.option.optional(),
                mod.option != null && mod.option.defaultValue());
        }
        if (latest.fileId() <= cf.fileId()) {
            return new UpdateResult(entry.file.rebase(repository.rootPath()).path(), mod.name,
                cf.projectId(), cf.fileId(), cf.fileId(),
                mod.filename != null ? mod.filename.filename() : "", mod.download != null ? mod.download.hash() : "",
                mod.filename != null ? mod.filename.filename() : "",
                "已是最新", false, PackRepository.sideName(mod),
                mod.option != null && mod.option.optional(),
                mod.option != null && mod.option.defaultValue());
        }
        return new UpdateResult(entry.file.rebase(repository.rootPath()).path(), latest.name(),
            latest.projectId(), cf.fileId(), latest.fileId(), latest.filename(), latest.sha1(),
            mod.filename != null ? mod.filename.filename() : "",
            "可更新: " + (mod.filename != null ? mod.filename.filename() : mod.name) + " -> " + latest.filename(),
            false, PackRepository.sideName(mod),
            mod.option != null && mod.option.optional(),
            mod.option != null && mod.option.defaultValue());
    }

    private UpdateResult hydrateUpdate(UpdateResult update, ClientHolder holder) throws Exception {
        if (!update.updateAvailable() || update.newSha1() != null && !update.newSha1().isBlank()) return update;
        var files = CurseForgeSourcer.resolveProjectFiles(List.of(update.newFileId()), holder);
        var file = files.get(update.newFileId());
        return file != null ? update.withFile(file) : update;
    }

    private void writeUpdate(MetadataWriter writer, UpdateResult update) throws Exception {
        writer.writeCurseForgeMetadataAt(
            update.metaPath(),
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
        if (pack.versions.containsKey("quilt")) {
            loaders.add("quilt");
            loaders.add("fabric");
        } else if (pack.versions.containsKey("fabric")) {
            loaders.add("fabric");
        }
        if (pack.versions.containsKey("neoforge")) {
            loaders.add("neoforge");
            loaders.add("forge");
        } else if (pack.versions.containsKey("forge")) {
            loaders.add("forge");
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

    private List<ImportEntry> resolveDependencies(CurseForgeSourcer.CurseForgeProjectFile main,
                                                  PackFile pack,
                                                  LinkedHashSet<Integer> installed,
                                                  String side,
                                                  ClientHolder clientHolder) throws Exception {
        var planned = new ArrayList<ImportEntry>();
        var plannedIds = new LinkedHashSet<Integer>();
        var queue = new ArrayList<Integer>();
        for (int depId : main.requiredDependencies()) {
            int mapped = mapDependency(depId, pack);
            if (!installed.contains(mapped) && !queue.contains(mapped)) queue.add(mapped);
        }

        int cycles = 0;
        while (!queue.isEmpty() && cycles < MAX_DEPENDENCY_CYCLES) {
            var current = new LinkedHashSet<>(queue);
            queue.clear();
            var deps = CurseForgeSourcer.resolveProjects(current, pack.supportedMinecraftVersions(), loaders(pack), clientHolder);
            for (var dep : deps) {
                int depId = dep.projectId();
                if (plannedIds.contains(depId)) continue;
                boolean alreadyInstalled = installed.contains(depId);
                planned.add(new ImportEntry(
                    dep,
                    normalizeCategory(null, dep.category()),
                    side,
                    false,
                    true,
                    alreadyInstalled
                ));
                plannedIds.add(depId);
                if (!alreadyInstalled) {
                    for (int next : dep.requiredDependencies()) {
                        int mapped = mapDependency(next, pack);
                        if (!plannedIds.contains(mapped) && !queue.contains(mapped)) {
                            queue.add(mapped);
                        }
                    }
                }
            }
            cycles++;
        }
        if (cycles >= MAX_DEPENDENCY_CYCLES && !queue.isEmpty()) {
            throw new IllegalStateException("CurseForge 依赖递归过深，已停止在 " + MAX_DEPENDENCY_CYCLES + " 轮");
        }
        return planned;
    }

    public record ImportPlan(
        ImportEntry main,
        List<ImportEntry> dependencies
    ) {}

    public record ImportEntry(
        CurseForgeSourcer.CurseForgeProjectFile file,
        String category,
        String side,
        boolean optional,
        boolean defaultEnabled,
        boolean alreadyInstalled
    ) {
        public int projectId() {
            return file.projectId();
        }

        public String name() {
            return file.name();
        }
    }

    public record UpdateResult(
        String metaPath,
        String name,
        int projectId,
        int oldFileId,
        int newFileId,
        String newFilename,
        String newSha1,
        String oldFilename,
        String message,
        boolean pinned,
        String side,
        boolean optional,
        boolean defaultEnabled
    ) {
        public boolean updateAvailable() {
            return !pinned && projectId > 0 && newFileId > 0 && newFileId != oldFileId;
        }

        public UpdateResult withFile(CurseForgeSourcer.CurseForgeProjectFile file) {
            return new UpdateResult(
                metaPath,
                name,
                file.projectId(),
                oldFileId,
                file.fileId(),
                file.filename(),
                file.sha1(),
                oldFilename,
                message,
                pinned,
                side,
                optional,
                defaultEnabled
            );
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
