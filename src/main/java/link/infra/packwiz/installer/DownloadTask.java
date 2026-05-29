package link.infra.packwiz.installer;

import link.infra.packwiz.installer.metadata.IndexFile;
import link.infra.packwiz.installer.metadata.ManifestFile;
import link.infra.packwiz.installer.metadata.hash.Hash;
import link.infra.packwiz.installer.metadata.hash.HashFormat;
import link.infra.packwiz.installer.request.RequestException;
import link.infra.packwiz.installer.target.ClientHolder;
import link.infra.packwiz.installer.target.Side;
import link.infra.packwiz.installer.target.path.PackwizFilePath;
import link.infra.packwiz.installer.ui.data.ExceptionDetails;
import link.infra.packwiz.installer.ui.data.IOptionDetails;
import link.infra.packwiz.installer.util.Log;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public class DownloadTask implements IOptionDetails {
    public final IndexFile.FileEntry metadata;
    private final IndexFile index;
    private final Side downloadSide;

    private ManifestFile.File cachedFile = null;
    private Exception err = null;
    private boolean alreadyUpToDate = false;
    private boolean metadataRequired = true;
    private boolean invalidated = false;
    private boolean newOptional = true;
    private CompletionStatus completionStatus = CompletionStatus.INCOMPLETE;

    public enum CompletionStatus {
        INCOMPLETE, DOWNLOADED, ALREADY_EXISTS_CACHED, ALREADY_EXISTS_VALIDATED,
        SKIPPED_DISABLED, SKIPPED_WRONG_SIDE, DELETED_DISABLED, DELETED_WRONG_SIDE
    }

    private DownloadTask(IndexFile.FileEntry metadata, IndexFile index, Side downloadSide) {
        this.metadata = metadata;
        this.index = index;
        this.downloadSide = downloadSide;
    }

    public ExceptionDetails getExceptionDetails() {
        return err != null ? new ExceptionDetails(name(), err) : null;
    }

    public boolean failed() { return err != null; }

    public void markCurseForgeFailed() {
        err = new Exception("CurseForge 元数据解析失败 - 已从 API 中排除");
        alreadyUpToDate = false;
        completionStatus = CompletionStatus.SKIPPED_DISABLED;
    }

    public boolean isAlreadyUpToDate() { return alreadyUpToDate; }
    public CompletionStatus getCompletionStatus() { return completionStatus; }

    public boolean isOptional() {
        return metadata.linkedFile != null && metadata.linkedFile.option.optional();
    }

    public boolean isNewOptional() { return isOptional() && newOptional; }

    public boolean correctSide() {
        if (metadata.linkedFile == null) return true;
        return downloadSide.hasSide(metadata.linkedFile.side);
    }

    @Override public String name() { return metadata.linkedFile != null ? metadata.linkedFile.name : metadata.file.filename(); }

    @Override
    public boolean optionValue() {
        return cachedFile != null ? cachedFile.optionValue : true;
    }

    @Override
    public void setOptionValue(boolean value) {
        if (value && !optionValue()) alreadyUpToDate = false;
        if (cachedFile != null) cachedFile.optionValue = value;
    }

    @Override
    public String optionDescription() {
        return metadata.linkedFile != null ? metadata.linkedFile.option.description() : "";
    }

    public void invalidate() {
        invalidated = true;
        alreadyUpToDate = false;
    }

    private record HashInfo(Hash<?> hash, HashFormat format) {}

    private HashInfo resolveHashAndFormat() {
        if (metadata.linkedFile != null) {
            return new HashInfo(metadata.linkedFile.getHash(), metadata.linkedFile.download.hashFormat());
        }
        return new HashInfo(metadata.getHashObj(index), metadata.hashFormat);
    }

    private void updateCachedFile(PackwizFilePath packFolder) {
        if (cachedFile == null) cachedFile = new ManifestFile.File();
        try {
            cachedFile.hash = metadata.getHashObj(index);
        } catch (Exception e) {
            err = e;
            return;
        }
        cachedFile.isOptional = isOptional();
        cachedFile.cachedLocation = (PackwizFilePath) metadata.getDestURI().rebase(packFolder);
        if (metadata.linkedFile != null) {
            try {
                cachedFile.linkedFileHash = metadata.linkedFile.getHash();
            } catch (Exception e) {
                err = e;
            }
        }
    }

    public void updateFromCache(ManifestFile.File cachedFile) {
        if (err != null) return;
        if (cachedFile == null) {
            this.cachedFile = new ManifestFile.File();
            return;
        }
        this.cachedFile = cachedFile;
        if (!invalidated) {
            try {
                Hash<?> currHash = metadata.getHashObj(index);
                if (currHash.equals(cachedFile.hash)) {
                    alreadyUpToDate = true;
                    metadataRequired = false;
                    completionStatus = CompletionStatus.ALREADY_EXISTS_CACHED;
                }
            } catch (Exception e) {
                err = e;
                return;
            }
        }
        if (cachedFile.isOptional) metadataRequired = true;
    }

    public void downloadMetadata(ClientHolder clientHolder) {
        if (err != null) return;
        if (metadataRequired) {
            try {
                metadata.downloadMeta(index, clientHolder);
            } catch (Exception e) {
                err = e;
                return;
            }
            if (cachedFile != null) {
                var linkedFile = metadata.linkedFile;
                if (linkedFile != null && linkedFile.option.optional()) {
                    if (cachedFile.isOptional) {
                        newOptional = false;
                    } else {
                        cachedFile.optionValue = linkedFile.option.defaultValue();
                    }
                }
                cachedFile.isOptional = isOptional();
                cachedFile.onlyOtherSide = !correctSide();
            }
        }
    }

    public void validateExistingFile(PackwizFilePath packFolder, ClientHolder clientHolder) {
        if (alreadyUpToDate) return;
        try {
            var destPath = (PackwizFilePath) metadata.getDestURI().rebase(packFolder);
            try (var src = destPath.source(clientHolder)) {
                var hashInfo = resolveHashAndFormat();
                // Read all data through hashing stream
                var hashing = new Hash.HashingInputStream(src, hashInfo.format.algorithm());
                hashing.transferTo(OutputStream.nullOutputStream());
                byte[] computedHash = hashing.getDigest();

                if (java.util.Arrays.equals(hashInfo.hash().value(), computedHash)) {
                    alreadyUpToDate = true;
                    completionStatus = CompletionStatus.ALREADY_EXISTS_VALIDATED;
                    updateCachedFile(packFolder);
                }
            }
        } catch (RequestException | IOException ignored) {
            // If the file doesn't exist we'll be downloading it
        }
    }

    public void download(PackwizFilePath packFolder, ClientHolder clientHolder) {
        if (err != null) return;

        // Exclude wrong-side and optional false files
        if (cachedFile != null) {
            if ((cachedFile.isOptional && !cachedFile.optionValue) || !correctSide()) {
                if (cachedFile.cachedLocation != null) {
                    try {
                        boolean deleted = Files.deleteIfExists(cachedFile.cachedLocation.nioPath());
                        completionStatus = deleted
                            ? (correctSide() ? CompletionStatus.DELETED_DISABLED : CompletionStatus.DELETED_WRONG_SIDE)
                            : (correctSide() ? CompletionStatus.SKIPPED_DISABLED : CompletionStatus.SKIPPED_WRONG_SIDE);
                    } catch (IOException e) {
                        Log.warn("Failed to delete file", e);
                    }
                } else {
                    completionStatus = correctSide() ? CompletionStatus.SKIPPED_DISABLED : CompletionStatus.SKIPPED_WRONG_SIDE;
                }
                cachedFile.cachedLocation = null;
                return;
            }
        }
        if (alreadyUpToDate) return;

        var destPath = (PackwizFilePath) metadata.getDestURI().rebase(packFolder);

        if (metadata.preserve && Files.exists(destPath.nioPath())) return;

        try {
            var hashInfo = resolveHashAndFormat();
            var src = metadata.getSource(clientHolder);
            var data = new ByteArrayOutputStream();

            // Read all data into buffer + compute hash
            var hashing = new Hash.HashingInputStream(src, hashInfo.format().algorithm());
            hashing.transferTo(data);
            byte[] computedHash = hashing.getDigest();

            if (java.util.Arrays.equals(hashInfo.hash().value(), computedHash)) {
                Files.createDirectories(destPath.nioPath().getParent());
                Files.copy(new ByteArrayInputStream(data.toByteArray()), destPath.nioPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                Log.warn("哈希值无效：" + metadata.getDestURI());
                Log.warn("期望值：" + hashInfo.hash());
                err = new Exception("哈希值无效！");
                return;
            }
            // Delete old file if location changed
            if (cachedFile != null && cachedFile.cachedLocation != null && !destPath.equals(cachedFile.cachedLocation)) {
                try {
                    Files.delete(cachedFile.cachedLocation.nioPath());
                } catch (IOException e) {
                    Log.warn("Failed to delete old file location", e);
                }
            }
        } catch (Exception e) {
            err = e;
            return;
        }

        updateCachedFile(packFolder);
        completionStatus = CompletionStatus.DOWNLOADED;
    }

    public ManifestFile.File getCachedFile() { return cachedFile; }

    public static List<DownloadTask> createTasksFromIndex(IndexFile index, Side downloadSide) {
        var tasks = new ArrayList<DownloadTask>();
        for (var file : index.files) {
            tasks.add(new DownloadTask(file, index, downloadSide));
        }
        return tasks;
    }
}
