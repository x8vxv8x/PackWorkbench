package link.infra.packwiz.installer.sync;

import link.infra.packwiz.installer.config.InstallerConfig;
import link.infra.packwiz.installer.metadata.*;
import link.infra.packwiz.installer.metadata.curseforge.CurseForgeSourcer;
import link.infra.packwiz.installer.metadata.hash.Hash;
import link.infra.packwiz.installer.metadata.hash.HashFormat;
import link.infra.packwiz.installer.target.ClientHolder;
import link.infra.packwiz.installer.target.Side;
import link.infra.packwiz.installer.target.path.HttpUrlPath;
import link.infra.packwiz.installer.target.path.PackwizFilePath;
import link.infra.packwiz.installer.target.path.PackwizPath;
import link.infra.packwiz.installer.ui.data.ExceptionDetails;
import link.infra.packwiz.installer.util.Log;

import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.HexFormat;

/**
 * 同步管理器。
 * 基于 git+packwiz 工作流：元数据从本地读取，仅联网下载实际文件。
 * 使用 ManifestFile (packwiz.json) 跟踪安装状态，实现完整的增量同步、清理和回滚。
 */
public class SyncManager {
    private final InstallerConfig config;
    private final Path rootDir;
    private final ClientHolder clientHolder;

    // ===== 数据结构 =====

    public static class SyncResult {
        public final List<ModChange> added = new ArrayList<>();
        public final List<ModChange> updated = new ArrayList<>();
        public final List<ModChange> removed = new ArrayList<>();
        public final List<ModChange> failed = new ArrayList<>();
        public String packName = "";
        public String lastSyncTime = "";
        public boolean unchanged = false;
    }

    public static class ModChange {
        public final String name;
        public final String destPath;
        public final String downloadUrl;
        public final String hash;
        public final String changeType; // "added" | "updated" | "removed" | "failed" | "cleaned"
        public final String error;
        public final String oldVersion;
        public final String newVersion;

        public ModChange(String name, String destPath, String downloadUrl, String hash, String changeType) {
            this(name, destPath, downloadUrl, hash, changeType, "", "", "");
        }

        public ModChange(String name, String destPath, String downloadUrl, String hash, String changeType,
                         String error, String oldVersion, String newVersion) {
            this.name = name;
            this.destPath = destPath;
            this.downloadUrl = downloadUrl;
            this.hash = hash;
            this.changeType = changeType;
            this.error = error;
            this.oldVersion = oldVersion;
            this.newVersion = newVersion;
        }
    }

    public SyncManager(InstallerConfig config, Path rootDir) {
        this.config = config;
        this.rootDir = rootDir;
        this.clientHolder = new ClientHolder();
    }

    // ===== 同步检查 =====

    /**
     * 执行同步检查：从本地读取索引，对比清单，返回变更预览。
     */
    public SyncResult checkForChanges() throws Exception {
        SyncResult result = new SyncResult();
        PackwizFilePath packFolder = new PackwizFilePath(config.resolveInstallFolder(rootDir));

        // 加载清单
        Path manifestPath = InstallerConfig.getManifestFile(rootDir);
        ManifestFile manifest = ManifestFile.load(manifestPath, packFolder);

        // 本地读取 pack.toml
        PackwizPath<?> packFilePath = resolvePackUrl(config.getPackUrl());
        InputStream packSource = packFilePath.source(clientHolder);
        Hash.HashingInputStream packHashing = HashFormat.SHA256.createSource(packSource);
        PackFile pf;
        try (packHashing) {
            pf = PackFile.fromToml(packHashing, packFilePath);
        }
        result.packName = pf.name != null ? pf.name : "未知";
        Hash<?> currentPackHash = new Hash<>(packHashing.getDigest(), Hash.Encoding.HEX);

        // 本地读取 index.toml
        PackwizPath<?> indexUri = pf.index.file();
        InputStream indexSource = indexUri.source(clientHolder);
        IndexFile indexFile;
        try (indexSource) {
            indexFile = IndexFile.fromToml(indexSource, indexUri.parent());
        }

        // 过滤同步范围内的条目
        List<IndexFile.FileEntry> syncedEntries = new ArrayList<>();
        for (var entry : indexFile.files) {
            PackwizPath<?> destUri = entry.getDestURI();
            String relativePath = destUri.rebase(packFolder).path();
            if (shouldSync(relativePath)) {
                syncedEntries.add(entry);
            }
        }
        Log.info("同步范围: " + syncedEntries.size() + " / " + indexFile.files.size() + " 个条目");

        // 读取本地 .pw.toml 元数据
        for (var entry : syncedEntries) {
            if (entry.metafile) {
                try {
                    entry.readLocalMeta(indexFile, packFolder);
                } catch (Exception e) {
                    Log.warn("读取元数据失败: " + entry.file + " - " + e.getMessage());
                }
            }
        }

        // Side 过滤
        if (!"both".equals(config.getSide())) {
            Side targetSide = Side.from(config.getSide());
            syncedEntries.removeIf(entry -> {
                if (entry.linkedFile != null && !entry.linkedFile.side.hasSide(targetSide)) {
                    Log.info("跳过 (侧不匹配): " + entry.getName());
                    return true;
                }
                return false;
            });
        }

        // 构建 index map: destPath -> FileEntry
        Map<String, IndexFile.FileEntry> indexMap = new LinkedHashMap<>();
        for (var entry : syncedEntries) {
            PackwizPath<?> destUri = entry.getDestURI();
            String destPath = destUri.rebase(packFolder).path();
            if (destPath != null) {
                indexMap.put(destPath, entry);
            }
        }

        // 磁盘失效检查
        boolean invalidateAll = !"both".equals(config.getSide()) && config.getSide() != null
            && !config.getSide().equals(manifest.cachedSide != null ? manifest.cachedSide.name().toLowerCase() : "");
        List<PackwizFilePath> invalidatedUris = new ArrayList<>();
        if (!invalidateAll) {
            for (var manifestEntry : manifest.cachedFiles.entrySet()) {
                ManifestFile.File file = manifestEntry.getValue();
                if (file.onlyOtherSide) continue;
                if (!file.isOptional || file.optionValue) {
                    if (file.cachedLocation != null) {
                        if (!Files.exists(file.cachedLocation.nioPath())) {
                            invalidatedUris.add(manifestEntry.getKey());
                            Log.info("文件已失效: " + manifestEntry.getKey());
                        }
                    } else {
                        invalidatedUris.add(manifestEntry.getKey());
                    }
                }
            }
        }

        // Pack Hash 快速跳过
        if (!invalidateAll && currentPackHash.equals(manifest.packFileHash) && invalidatedUris.isEmpty()) {
            Log.info("Pack 未变化，无需同步");
            result.unchanged = true;
            return result;
        }

        // Diff: index vs manifest
        for (var indexEntry : indexMap.entrySet()) {
            String destPath = indexEntry.getKey();
            var fileEntry = indexEntry.getValue();

            // 只处理 metafile 条目（需要下载实际文件）
            if (!fileEntry.metafile) continue;

            String modName = fileEntry.getName();
            var dlInfo = getDownloadInfo(fileEntry);
            String downloadUrl = dlInfo != null ? dlInfo.url() : "";
            String downloadHash = dlInfo != null ? dlInfo.hash() : "";
            // 用 index 中的 metafile hash 做 diff 对比（与 manifest 中存储的一致）
            String indexHash = fileEntry.hash;

            // 在 manifest 中查找对应的条目
            PackwizFilePath manifestKey = new PackwizFilePath(packFolder.nioPath(), destPath);
            ManifestFile.File manifestFile = manifest.cachedFiles.get(manifestKey);
            if (manifestFile == null) {
                result.added.add(new ModChange(modName, destPath, downloadUrl, downloadHash, "added"));
            } else if (!indexHash.equals(manifestFile.hash != null ? manifestFile.hash.toString() : "")) {
                result.updated.add(new ModChange(modName, destPath, downloadUrl, downloadHash, "updated",
                    "", "", modName));
            }
        }

        // Manifest 有, index 无 → 将删除/清理
        Set<String> indexDestPaths = indexMap.keySet();
        for (var manifestEntry : manifest.cachedFiles.entrySet()) {
            PackwizFilePath manifestKey = manifestEntry.getKey();
            ManifestFile.File file = manifestEntry.getValue();
            String relPath = manifestKey.path();
            if (file.cachedLocation != null && relPath != null && !indexDestPaths.contains(relPath)) {
                result.removed.add(new ModChange(
                    relPath, relPath, "", "", "removed"
                ));
            }
        }

        return result;
    }

    // ===== 执行同步 =====

    /**
     * 执行同步：清理已删除文件、下载新增/更新文件、更新清单。
     */
    public SyncResult executeSync(SyncResult preview, java.util.function.BiConsumer<Integer, String> progressCallback) {
        SyncResult result = new SyncResult();
        result.packName = preview.packName;
        result.lastSyncTime = preview.lastSyncTime;

        PackwizFilePath packFolder = new PackwizFilePath(config.resolveInstallFolder(rootDir));
        Path manifestPath = InstallerConfig.getManifestFile(rootDir);
        ManifestFile manifest = ManifestFile.load(manifestPath, packFolder);

        // 重新加载 index（用于 CurseForge 解析）
        final IndexFile indexFile = loadIndexFile(packFolder);

        // Phase 0: 清理 manifest 中已不在 index 中的文件
        if (indexFile != null) {
            int cleaned = cleanupRemovedFiles(manifest, indexFile, packFolder);
            if (cleaned > 0) Log.info("已清理 " + cleaned + " 个已移除的文件");
        }

        // 计算总任务数
        List<ModChange> downloadTasks = new ArrayList<>();
        downloadTasks.addAll(preview.added);
        downloadTasks.addAll(preview.updated);
        int total = downloadTasks.size() + preview.removed.size();
        int completed = 0;

        // Phase 1: 删除移除的文件
        for (var change : preview.removed) {
            try {
                Path filePath = packFolder.nioPath().resolve(change.destPath);
                if (Files.deleteIfExists(filePath)) {
                    Log.info("已删除: " + change.destPath);
                }
                manifest.cachedFiles.remove(new PackwizFilePath(packFolder.nioPath(), change.destPath));
                result.removed.add(change);
            } catch (IOException e) {
                Log.warn("删除失败: " + change.destPath + " - " + e.getMessage());
            }
            completed++;
            if (progressCallback != null) progressCallback.accept(completed, "删除: " + change.name);
        }

        // Phase 2: 解析 CurseForge URLs
        if (indexFile != null) {
            var cfEntries = downloadTasks.stream()
                .map(change -> findIndexEntry(indexFile, change.destPath, packFolder))
                .filter(Objects::nonNull)
                .filter(e -> e.linkedFile != null && e.linkedFile.update.containsKey("curseforge"))
                .filter(e -> !e.linkedFile.resolvedUpdateData.containsKey("curseforge"))
                .collect(Collectors.toList());

            if (!cfEntries.isEmpty()) {
                Log.info("解析 CurseForge 元数据 (" + cfEntries.size() + " 个)...");
                if (progressCallback != null) progressCallback.accept(completed, "解析 CurseForge...");
                var failures = CurseForgeSourcer.resolveCfMetadata(cfEntries, packFolder, clientHolder);
                for (var failure : failures) {
                    Log.warn("CurseForge 解析失败: " + failure.name() + " - " + failure.exception().getMessage());
                }
            }
        }

        // Phase 3: 并行下载
        var threadPool = Executors.newFixedThreadPool(10);
        var completionService = new ExecutorCompletionService<ModChange>(threadPool);

        for (var change : downloadTasks) {
            completionService.submit(() -> {
                downloadFile(change, packFolder, indexFile);
                return change;
            });
        }

        for (int i = 0; i < downloadTasks.size(); i++) {
            try {
                ModChange completedChange = completionService.take().get();
                // 下载成功
                IndexFile.FileEntry entry = indexFile != null
                    ? findIndexEntry(indexFile, completedChange.destPath, packFolder) : null;

                ManifestFile.File manifestFile = new ManifestFile.File();
                if (entry != null) {
                    manifestFile.hash = entry.getHashObj(indexFile);
                    manifestFile.linkedFileHash = entry.linkedFile != null ? entry.linkedFile.getHash() : null;
                    manifestFile.isOptional = entry.linkedFile != null && entry.linkedFile.option.optional();
                    manifestFile.onlyOtherSide = false;
                }
                manifestFile.cachedLocation = new PackwizFilePath(packFolder.nioPath(), completedChange.destPath);
                manifest.cachedFiles.put(new PackwizFilePath(packFolder.nioPath(), completedChange.destPath), manifestFile);

                String status = completedChange.changeType.equals("added") ? "已新增" : "已更新";
                result.added.add(completedChange);
                Log.info(status + ": " + completedChange.name);

            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                ModChange failedTask = downloadTasks.get(i);
                String errorMsg = cause.getMessage() != null ? cause.getMessage() : "未知错误";
                result.failed.add(new ModChange(
                    failedTask.name, failedTask.destPath, failedTask.downloadUrl, failedTask.hash,
                    failedTask.changeType, errorMsg, "", ""
                ));
                Log.warn("下载失败: " + failedTask.name + " - " + errorMsg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            completed++;
            if (progressCallback != null) progressCallback.accept(completed, "下载: " + downloadTasks.get(i).name);
        }

        threadPool.shutdown();

        // 保存清单
        manifest.cachedSide = Side.from(config.getSide());
        if (result.failed.isEmpty()) {
            // 只有全部成功时才更新 pack hash
            try {
                PackwizPath<?> packFilePath = resolvePackUrl(config.getPackUrl());
                InputStream packSource = packFilePath.source(clientHolder);
                Hash.HashingInputStream hashing = HashFormat.SHA256.createSource(packSource);
                try (hashing) { hashing.transferTo(new OutputStream() { @Override public void write(int b) {} }); }
                manifest.packFileHash = new Hash<>(hashing.getDigest(), Hash.Encoding.HEX);
            } catch (Exception e) {
                Log.warn("计算 pack hash 失败: " + e.getMessage());
            }
        }
        manifest.save(manifestPath, packFolder);

        return result;
    }

    // ===== 内部方法 =====

    /** 从本地加载 index.toml 文件 */
    private IndexFile loadIndexFile(PackwizFilePath packFolder) {
        try {
            PackwizPath<?> packFilePath = resolvePackUrl(config.getPackUrl());
            InputStream packSource = packFilePath.source(clientHolder);
            PackFile pf;
            try (packSource) {
                pf = PackFile.fromToml(packSource, packFilePath);
            }
            PackwizPath<?> indexUri = pf.index.file();
            InputStream indexSource = indexUri.source(clientHolder);
            IndexFile indexFile;
            try (indexSource) {
                indexFile = IndexFile.fromToml(indexSource, indexUri.parent());
            }
            // 读取元数据
            for (var entry : indexFile.files) {
                if (entry.metafile) {
                    try { entry.readLocalMeta(indexFile, packFolder); } catch (Exception ignored) {}
                }
            }
            return indexFile;
        } catch (Exception e) {
            Log.warn("加载索引失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 清理 manifest 中存在但 index 中不存在的文件。
     */
    private int cleanupRemovedFiles(ManifestFile manifest, IndexFile indexFile, PackwizFilePath packFolder) {
        int cleaned = 0;
        Set<String> indexDestPaths = new HashSet<>();
        for (var entry : indexFile.files) {
            String destPath = entry.getDestURI().rebase(packFolder).path();
            indexDestPaths.add(destPath);
        }

        var iterator = manifest.cachedFiles.entrySet().iterator();
        while (iterator.hasNext()) {
            var manifestEntry = iterator.next();
            PackwizFilePath path = manifestEntry.getKey();
            ManifestFile.File file = manifestEntry.getValue();

            // 只清理同步范围内的文件
            if (!shouldSync(path.path())) continue;

            if (file.cachedLocation != null && !indexDestPaths.contains(path.toString())) {
                try {
                    if (Files.deleteIfExists(file.cachedLocation.nioPath())) {
                        Log.info("已清理 (从索引移除): " + path.path());
                        cleaned++;
                    }
                } catch (IOException e) {
                    Log.warn("清理失败: " + path.path() + " - " + e.getMessage());
                }
                iterator.remove();
            }
        }
        return cleaned;
    }

    /**
     * 下载单个文件（含 hash 校验）。
     */
    private void downloadFile(ModChange change, PackwizFilePath packFolder, IndexFile indexFile) throws Exception {
        // Preserve 检查
        IndexFile.FileEntry entry = indexFile != null ? findIndexEntry(indexFile, change.destPath, packFolder) : null;
        if (entry != null && entry.preserve) {
            Path destPath = packFolder.nioPath().resolve(change.destPath);
            if (Files.exists(destPath)) {
                Log.info("跳过 (preserve): " + change.destPath);
                return;
            }
        }

        // 解析下载 URL（CurseForge 条目在 preview 中 URL 为空，需要从 index 解析）
        String downloadUrl = change.downloadUrl;
        if ((downloadUrl == null || downloadUrl.isEmpty()) && entry != null) {
            var dlInfo = getDownloadInfo(entry);
            if (dlInfo != null) downloadUrl = dlInfo.url();
        }
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            throw new Exception("无法获取下载 URL: " + change.name);
        }

        PackwizPath<?> downloadPath = resolvePackUrl(downloadUrl);
        InputStream source = downloadPath.source(clientHolder);

        Path destPath = packFolder.nioPath().resolve(change.destPath);
        Files.createDirectories(destPath.getParent());

        // 流式下载 + hash 校验
        // 从 entry 获取正确的 hash 和 hashFormat（而非 change.hash）
        HashFormat hashFormat = HashFormat.SHA256;
        String expectedHash = "";
        if (entry != null && entry.linkedFile != null && entry.linkedFile.download != null) {
            hashFormat = entry.linkedFile.download.hashFormat();
            expectedHash = entry.linkedFile.download.hash();
        } else if (entry != null) {
            hashFormat = entry.effectiveHashFormat(indexFile);
            expectedHash = entry.hash;
        }

        Hash.HashingInputStream hashSource = hashFormat.createSource(source);
        try (hashSource; var out = Files.newOutputStream(destPath)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = hashSource.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }

        // Hash 校验
        String actualHash = HexFormat.of().formatHex(hashSource.getDigest());
        if (expectedHash != null && !expectedHash.isEmpty() && !actualHash.equals(expectedHash)) {
            try { Files.deleteIfExists(destPath); } catch (IOException ignored) {}
            throw new Exception("Hash 不匹配: 期望 " + expectedHash + ", 实际 " + actualHash);
        }
    }

    /** 解析下载 URL（支持 HTTP、file: URI、本地路径） */
    private PackwizPath<?> resolvePackUrl(String url) {
        if (url == null || url.isEmpty()) throw new IllegalArgumentException("URL 为空");
        if (url.matches("(?i)^https?://.*")) {
            var uri = URI.create(url);
            return new HttpUrlPath(uri);
        }
        if (url.matches("(?i)^file:.*")) {
            Path path = Path.of(URI.create(url));
            return new PackwizFilePath(path.getParent(), path.getFileName().toString());
        }
        Path path = Path.of(url.replace("\\", "/"));
        if (path.getParent() != null) {
            return new PackwizFilePath(path.getParent(), path.getFileName().toString());
        }
        return new PackwizFilePath(Path.of("."), path.getFileName().toString());
    }

    /**
     * 获取文件的下载信息（URL + hash）。
     * CurseForge 条目在 API 解析前返回 null。
     */
    private DownloadInfo getDownloadInfo(IndexFile.FileEntry entry) {
        if (entry.linkedFile != null) {
            // CurseForge 解析后的 URL
            var resolved = entry.linkedFile.resolvedUpdateData.get("curseforge");
            if (resolved != null) {
                String hash = entry.linkedFile.download != null ? entry.linkedFile.download.hash() : entry.hash;
                return new DownloadInfo(resolved.toString(), hash);
            }
            // 直接 URL
            if (entry.linkedFile.download != null && entry.linkedFile.download.url() != null) {
                return new DownloadInfo(
                    entry.linkedFile.download.url().toString(),
                    entry.linkedFile.download.hash()
                );
            }
            // CurseForge 模式但尚未解析 → 返回 null
            if (entry.linkedFile.download != null
                && entry.linkedFile.download.mode() == DownloadMode.CURSEFORGE) {
                return null;
            }
        }
        // 非 metafile 或无下载信息
        return null;
    }

    private record DownloadInfo(String url, String hash) {}

    /** 判断文件是否在同步范围内 */
    private boolean shouldSync(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return false;
        String normalized = relativePath.replace('\\', '/');
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        int slashIdx = normalized.indexOf('/');
        if (slashIdx < 0) return false; // 根目录文件不同步
        String topFolder = normalized.substring(0, slashIdx);
        return config.getSyncFolders().isEmpty()
            || config.getSyncFolders().contains("*")
            || config.getSyncFolders().contains(topFolder);
    }

    /** 在 index 中查找对应的 FileEntry */
    private IndexFile.FileEntry findIndexEntry(IndexFile indexFile, String destPath, PackwizFilePath packFolder) {
        if (indexFile == null) return null;
        for (var entry : indexFile.files) {
            String entryDest = entry.getDestURI().rebase(packFolder).path();
            if (entryDest.equals(destPath)) return entry;
        }
        return null;
    }

    public void close() {
        clientHolder.close();
    }
}
