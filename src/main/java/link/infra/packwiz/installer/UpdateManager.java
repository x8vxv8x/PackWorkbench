package link.infra.packwiz.installer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import link.infra.packwiz.installer.metadata.*;
import link.infra.packwiz.installer.metadata.curseforge.CurseForgeSourcer;
import link.infra.packwiz.installer.metadata.hash.Hash;
import link.infra.packwiz.installer.metadata.hash.HashFormat;
import link.infra.packwiz.installer.request.RequestException;
import link.infra.packwiz.installer.target.ClientHolder;
import link.infra.packwiz.installer.target.Side;
import link.infra.packwiz.installer.target.path.PackwizFilePath;
import link.infra.packwiz.installer.target.path.PackwizPath;
import link.infra.packwiz.installer.ui.IUserInterface;
import link.infra.packwiz.installer.ui.data.ExceptionDetails;
import link.infra.packwiz.installer.ui.data.InstallProgress;
import link.infra.packwiz.installer.util.Log;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

public class UpdateManager {
    private boolean cancelled = false;
    private boolean cancelledStartGame = false;
    private boolean errorsOccurred = false;

    private final Options opts;
    private final IUserInterface ui;

    public record Options(
        PackwizPath<?> packFile,
        PackwizFilePath manifestFile,
        PackwizFilePath packFolder,
        PackwizFilePath multimcFolder,
        Side side,
        long timeout
    ) {}

    public UpdateManager(Options opts, IUserInterface ui) {
        this.opts = opts;
        this.ui = ui;
        start();
    }

    private void checkCancellation() {
        if (ui.isCancelButtonPressed()) {
            showCancellationDialog();
            handleCancellation();
        }
    }

    private boolean handleExceptionResult(List<ExceptionDetails> exceptions, int numTotal, boolean allowsIgnore) {
        errorsOccurred = true;
        return switch (ui.showExceptions(exceptions, numTotal, allowsIgnore)) {
            case CONTINUE -> false;
            case CANCEL -> { cancelled = true; yield true; }
            case IGNORE -> { cancelledStartGame = true; yield true; }
        };
    }

    private void start() {
        var clientHolder = new ClientHolder();
        ui.setCancelCallback(clientHolder::close);

        ui.submitProgress(new InstallProgress("加载清单文件..."));
        var gson = new GsonBuilder()
            .registerTypeAdapter(Hash.class, new Hash.TypeHandler())
            .enableComplexMapKeySerialization()
            .create();

        ManifestFile manifest;
        try (var reader = new InputStreamReader(opts.manifestFile().source(clientHolder), StandardCharsets.UTF_8)) {
            manifest = gson.fromJson(reader, ManifestFile.class);
        } catch (RequestException.Response.File.FileNotFound e) {
            ui.setFirstInstall(true);
            manifest = new ManifestFile();
        } catch (Exception e) {
            ui.showErrorAndExit("本地清单文件无效，请尝试删除 " + opts.manifestFile(), e);
            return;
        }

        checkCancellation();

        ui.submitProgress(new InstallProgress("加载整合包文件..."));
        InputStream packSource;
        Hash.HashingInputStream packHashing;
        try {
            packSource = opts.packFile().source(clientHolder);
            packHashing = HashFormat.SHA256.createSource(packSource);
        } catch (Exception e) {
            ui.showErrorAndExit("下载 pack.toml 失败", e);
            return;
        }

        PackFile pf;
        try (packHashing) {
            pf = PackFile.fromToml(packHashing, opts.packFile());
        } catch (Exception e) {
            ui.showErrorAndExit("解析 pack.toml 失败", e);
            return;
        }
        byte[] packFileHash = packHashing.getDigest();

        checkCancellation();

        // Launcher checks
        var lu = new LauncherUtils(opts, ui);
        ui.submitProgress(new InstallProgress("加载 MultiMC 整合包文件..."));
        try {
            switch (lu.handleMultiMC(pf, gson)) {
                case CANCELLED -> cancelled = true;
                case NOT_FOUND -> Log.info("MultiMC not detected");
                default -> {}
            }
            handleCancellation();
        } catch (Exception e) {
            ui.showErrorAndExit(e.getMessage() != null ? e.getMessage() : "未知错误", e);
            return;
        }

        checkCancellation();

        ui.submitProgress(new InstallProgress("检查本地文件..."));
        boolean invalidateAll = opts.side() != manifest.cachedSide;
        var invalidatedUris = new ArrayList<PackwizFilePath>();

        if (!invalidateAll) {
            for (var entry : manifest.cachedFiles.entrySet()) {
                var fileUri = entry.getKey();
                var file = entry.getValue();
                if (file.onlyOtherSide) continue;

                boolean invalid = false;
                if (!file.isOptional || file.optionValue) {
                    if (file.cachedLocation != null) {
                        if (!Files.exists(file.cachedLocation.nioPath())) invalid = true;
                    } else {
                        invalid = true;
                    }
                }
                if (invalid) {
                    Log.info("File " + fileUri.filename() + " invalidated, marked for redownloading");
                    invalidatedUris.add(fileUri);
                }
            }

            if (manifest.packFileHash != null && java.util.Arrays.equals(manifest.packFileHash.value(), packFileHash)
                && invalidatedUris.isEmpty()) {
                ui.submitProgress(new InstallProgress("整合包已是最新版本！", 1, 1));
                if (!ui.isOptionsButtonPressed()) return;
            }
        }

        Log.info("Modpack name: " + pf.name);

        checkCancellation();

        try {
            processIndex(pf.index.file(), pf.index.hashFormat().fromString(pf.index.hash()),
                pf.index.hashFormat(), manifest, invalidatedUris, invalidateAll, clientHolder);
        } catch (Exception e) {
            ui.showErrorAndExit("处理索引文件失败", e);
            return;
        }

        handleCancellation();

        if (errorsOccurred) {
            manifest.indexFileHash = null;
            manifest.packFileHash = null;
        } else {
            manifest.packFileHash = new Hash<>(packFileHash, Hash.Encoding.HEX);
        }

        manifest.cachedSide = opts.side();
        try (var writer = Files.newBufferedWriter(opts.manifestFile().nioPath(), StandardCharsets.UTF_8)) {
            gson.toJson(manifest, writer);
        } catch (IOException e) {
            ui.showErrorAndExit("保存本地清单文件失败", e);
        }
    }

    private void processIndex(PackwizPath<?> indexUri, Hash<?> indexHash, HashFormat hashFormat,
                              ManifestFile manifest, List<PackwizFilePath> invalidatedFiles,
                              boolean invalidateAll, ClientHolder clientHolder) throws Exception {
        if (!invalidateAll) {
            if (indexHash.equals(manifest.indexFileHash) && invalidatedFiles.isEmpty()) {
                ui.submitProgress(new InstallProgress("整合包文件已是最新版本！", 1, 1));
                if (!ui.isOptionsButtonPressed()) return;
                if (ui.isCancelButtonPressed()) { showCancellationDialog(); return; }
            }
        }
        manifest.indexFileHash = indexHash;

        InputStream indexSource;
        Hash.HashingInputStream indexHashing;
        try {
            indexSource = indexUri.source(clientHolder);
            indexHashing = hashFormat.createSource(indexSource);
        } catch (Exception e) {
            ui.showErrorAndExit("下载索引文件失败", e);
            return;
        }

        IndexFile indexFile;
        try (indexHashing) {
            indexFile = IndexFile.fromToml(indexHashing, indexUri.parent());
        } catch (Exception e) {
            ui.showErrorAndExit("解析索引文件失败", e);
            return;
        }
        byte[] computedIndexHash = indexHashing.getDigest();
        if (!java.util.Arrays.equals(indexHash.value(), computedIndexHash)) {
            ui.showErrorAndExit("索引文件哈希值无效！整合包开发者需要重新运行 packwiz refresh");
            return;
        }

        if (ui.isCancelButtonPressed()) { showCancellationDialog(); return; }

        ui.submitProgress(new InstallProgress("检查本地文件..."));
        var it = manifest.cachedFiles.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var uri = entry.getKey();
            var file = entry.getValue();
            if (file.cachedLocation != null) {
                boolean removed = indexFile.files.stream().noneMatch(f -> f.getDestURI().rebase(opts.packFolder()).equals(uri));
                if (removed) {
                    try { Files.deleteIfExists(file.cachedLocation.nioPath()); } catch (IOException e) {
                        Log.warn("Failed to delete file removed from index", e);
                    }
                    it.remove();
                }
            }
        }

        if (ui.isCancelButtonPressed()) { showCancellationDialog(); return; }
        ui.submitProgress(new InstallProgress("比较新文件..."));

        var tasks = DownloadTask.createTasksFromIndex(indexFile, opts.side());
        for (var f : tasks) {
            if (invalidateAll) f.invalidate();
            else if (invalidatedFiles.contains((PackwizFilePath) f.metadata.getDestURI().rebase(opts.packFolder()))) f.invalidate();
            var file = manifest.cachedFiles.get(f.metadata.getDestURI().rebase(opts.packFolder()));
            if (file != null) file.backup();
            f.updateFromCache(file);
        }

        if (ui.isCancelButtonPressed()) { showCancellationDialog(); return; }

        tasks.parallelStream().forEach(f -> f.downloadMetadata(clientHolder));

        var metadataFailures = tasks.stream().map(DownloadTask::getExceptionDetails).filter(Objects::nonNull).toList();
        if (!metadataFailures.isEmpty()) {
            if (handleExceptionResult(metadataFailures, tasks.size(), true)) return;
        }

        if (ui.isCancelButtonPressed()) { showCancellationDialog(); return; }

        tasks.removeIf(DownloadTask::failed);
        var optionTasks = tasks.stream().filter(DownloadTask::correctSide).filter(DownloadTask::isOptional).toList();
        boolean optionsChanged = optionTasks.stream().anyMatch(DownloadTask::isNewOptional);
        if (!optionTasks.isEmpty() && !optionsChanged) {
            if (!ui.isOptionsButtonPressed()) {
                ui.submitProgress(new InstallProgress("重新配置可选模组？", 0, 1));
                ui.awaitOptionalButton(true, opts.timeout());
                if (ui.isCancelButtonPressed()) { showCancellationDialog(); return; }
            }
        }
        if (ui.isOptionsButtonPressed() || optionsChanged) {
            if (ui.showOptions(new ArrayList<>(optionTasks))) {
                cancelled = true;
                handleCancellation();
            }
        }
        ui.disableOptionsButton(!optionTasks.isEmpty());

        while (true) {
            switch (validateAndResolve(tasks, clientHolder)) {
                case RETRY -> {}
                case QUIT -> { return; }
                case SUCCESS -> { break; }
            }
            break;
        }

        tasks.removeIf(DownloadTask::failed);

        var threadPool = Executors.newFixedThreadPool(10);
        var completionService = new ExecutorCompletionService<DownloadTask>(threadPool);
        for (var t : tasks) {
            completionService.submit(() -> { t.download(opts.packFolder(), clientHolder); return t; });
        }
        for (int i = 0; i < tasks.size(); i++) {
            DownloadTask task;
            try {
                task = completionService.take().get();
            } catch (InterruptedException e) {
                ui.showErrorAndExit("处理下载任务时被中断", e);
                return;
            } catch (ExecutionException e) {
                ui.showErrorAndExit("执行下载任务失败", e.getCause() != null ? (Exception) e.getCause() : e);
                return;
            }

            var file = task.getCachedFile();
            if (file != null) {
                if (task.failed() && file.revert != null) {
                    manifest.cachedFiles.putIfAbsent((PackwizFilePath) task.metadata.getDestURI().rebase(opts.packFolder()), file.revert);
                } else if (!task.failed()) {
                    manifest.cachedFiles.putIfAbsent((PackwizFilePath) task.metadata.getDestURI().rebase(opts.packFolder()), file);
                }
            }

            var exDetails = task.getExceptionDetails();
            String progress;
            if (exDetails != null) {
                progress = "下载失败 " + exDetails.name() + ": " + exDetails.exception().getMessage();
            } else {
                progress = switch (task.getCompletionStatus()) {
                    case INCOMPLETE -> task.name() + " 等待中";
                    case DOWNLOADED -> "已下载 " + task.name();
                    case ALREADY_EXISTS_CACHED -> task.name() + " 已存在（缓存）";
                    case ALREADY_EXISTS_VALIDATED -> task.name() + " 已存在（已验证）";
                    case SKIPPED_DISABLED -> "已跳过 " + task.name() + "（已禁用）";
                    case SKIPPED_WRONG_SIDE -> "已跳过 " + task.name() + "（错误端）";
                    case DELETED_DISABLED -> "已删除 " + task.name() + "（已禁用）";
                    case DELETED_WRONG_SIDE -> "已删除 " + task.name() + "（错误端）";
                };
            }
            ui.submitProgress(new InstallProgress(progress, i + 1, tasks.size()));

            if (ui.isCancelButtonPressed()) {
                clientHolder.close();
                threadPool.shutdown();
                cancelled = true;
                return;
            }
        }
        threadPool.shutdown();

        var postDownloadFailures = tasks.stream().map(DownloadTask::getExceptionDetails).filter(Objects::nonNull).toList();
        if (!postDownloadFailures.isEmpty()) {
            handleExceptionResult(postDownloadFailures, tasks.size(), false);
        }
    }

    private enum ResolveResult { RETRY, QUIT, SUCCESS }

    private ResolveResult validateAndResolve(List<DownloadTask> tasks, ClientHolder clientHolder) {
        ui.submitProgress(new InstallProgress("验证现有文件..."));

        for (var task : tasks) {
            if (task.correctSide()) task.validateExistingFile(opts.packFolder(), clientHolder);
        }

        var cfFiles = tasks.stream()
            .filter(t -> !t.isAlreadyUpToDate())
            .filter(DownloadTask::correctSide)
            .map(t -> t.metadata)
            .filter(m -> m.linkedFile != null && m.linkedFile.download.mode() == DownloadMode.CURSEFORGE)
            .toList();

        if (!cfFiles.isEmpty()) {
            ui.submitProgress(new InstallProgress("解析 CurseForge 元数据..."));
            var resolveFailures = CurseForgeSourcer.resolveCfMetadata(cfFiles, opts.packFolder(), clientHolder);
            if (!resolveFailures.isEmpty()) {
                errorsOccurred = true;
                return switch (ui.showExceptions(resolveFailures, cfFiles.size(), true)) {
                    case CONTINUE -> {
                        var failedNames = resolveFailures.stream().map(ExceptionDetails::name).collect(java.util.stream.Collectors.toSet());
                        for (var task : tasks) {
                            if (failedNames.contains(task.name())) task.markCurseForgeFailed();
                        }
                        yield ResolveResult.SUCCESS;
                    }
                    case CANCEL -> { cancelled = true; yield ResolveResult.QUIT; }
                    case IGNORE -> { cancelledStartGame = true; yield ResolveResult.QUIT; }
                };
            }
        }
        return ResolveResult.SUCCESS;
    }

    private void showCancellationDialog() {
        switch (ui.showCancellationDialog()) {
            case QUIT -> cancelled = true;
            case CONTINUE -> cancelledStartGame = true;
        }
    }

    private void handleCancellation() {
        if (cancelled) {
            Log.info("更新已被用户取消！");
            System.exit(1);
        } else if (cancelledStartGame) {
            Log.info("更新已被用户取消！继续启动游戏...");
            System.exit(0);
        }
    }
}
