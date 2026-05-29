package link.infra.packwiz.installer.metadata;

import link.infra.packwiz.installer.metadata.hash.Hash;
import link.infra.packwiz.installer.metadata.hash.HashFormat;
import link.infra.packwiz.installer.target.ClientHolder;
import link.infra.packwiz.installer.target.path.PackwizFilePath;
import link.infra.packwiz.installer.target.path.PackwizPath;

import com.moandjiezana.toml.Toml;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents an index.toml file - the list of files in a packwiz modpack.
 */
public class IndexFile {
    public HashFormat hashFormat = HashFormat.SHA256;
    public List<FileEntry> files = new ArrayList<>();

    public static class FileEntry {
        public PackwizPath<?> file;
        public HashFormat fileHashFormat; // 条目自身的 hash-format，可能为 null
        public String hash;
        public PackwizPath<?> alias = null;
        public boolean metafile = false;
        public boolean preserve = false;
        public boolean optional = false;

        // Resolved after metadata read
        public transient ModFile linkedFile = null;

        public FileEntry(PackwizPath<?> file, HashFormat hashFormat, String hash) {
            this.file = file;
            this.fileHashFormat = hashFormat;
            this.hash = hash;
        }

        /** 获取该条目实际使用的 hash format（条目自身优先，否则用 index 全局的） */
        public HashFormat effectiveHashFormat(IndexFile index) {
            return fileHashFormat != null ? fileHashFormat : index.hashFormat;
        }

        public Hash<?> getHashObj(IndexFile index) {
            return effectiveHashFormat(index).fromString(hash);
        }

        public HashFormat getHashFormat(IndexFile index) {
            return effectiveHashFormat(index);
        }

        /**
         * 读取关联的 mod 元数据文件（本地 .pw.toml）。
         * 仅当 metafile=true 时有效。
         */
        public void downloadMeta(IndexFile index, ClientHolder clientHolder) throws Exception {
            if (!metafile) return;
            InputStream input = file.source(clientHolder);
            try (input) {
                // 验证元数据文件的 hash
                HashFormat hf = effectiveHashFormat(index);
                Hash.HashingInputStream hashing = hf.createSource(input);
                ModFile modFile;
                try (hashing) {
                    modFile = ModFile.fromToml(hashing, file.parent());
                }
                // hash 校验
                Hash<?> expectedHash = getHashObj(index);
                Hash<?> actualHash = new Hash<>(hashing.getDigest(), hf.encoding());
                if (!expectedHash.equals(actualHash)) {
                    throw new Exception("元数据文件 hash 不匹配: " + file);
                }
                this.linkedFile = modFile;
            }
        }

        /**
         * 从本地读取关联的 mod 元数据文件。
         * 适用于 git+packwiz 工作流，元数据在本地。
         *
         * @param index 索引文件
         * @param packFolder pack 根目录（用于解析 filename 等相对路径）
         */
        public void readLocalMeta(IndexFile index, PackwizPath<?> packFolder) throws Exception {
            if (!metafile) return;
            InputStream input = file.source(null);
            try (input) {
                // packwiz 的 filename 是相对于 pack 根目录的，不是相对于 .pw.toml 的位置
                ModFile modFile = ModFile.fromToml(input, packFolder);
                this.linkedFile = modFile;
            }
        }

        /**
         * Get the download source for this file.
         */
        public InputStream getSource(ClientHolder clientHolder) throws Exception {
            if (linkedFile != null) {
                return linkedFile.getSource(clientHolder);
            }
            return file.source(clientHolder);
        }

        /** 获取目标文件的名称 */
        public String getName() {
            if (metafile && linkedFile != null && linkedFile.name != null) {
                return linkedFile.name;
            }
            return file.filename();
        }

        /** 获取目标文件路径（考虑 alias 和 metafile） */
        public PackwizPath<?> getDestURI() {
            if (alias != null) return alias;
            if (metafile && linkedFile != null && linkedFile.filename != null) {
                return linkedFile.filename;
            }
            return file;
        }
    }

    public static IndexFile fromToml(InputStream input, PackwizPath<?> basePath) {
        Toml toml = new Toml().read(input);
        IndexFile indexFile = new IndexFile();

        // 全局 hash-format
        String globalHashFormatStr = toml.getString("hash-format");
        if (globalHashFormatStr != null) {
            indexFile.hashFormat = HashFormat.fromName(globalHashFormatStr);
        }

        List<Toml> filesList = toml.getTables("files");
        if (filesList != null) {
            for (Toml fileTable : filesList) {
                String fileStr = fileTable.getString("file");
                if (fileStr == null) continue;

                PackwizPath<?> filePath = basePath.resolve(fileStr);
                String hashFormatStr = fileTable.getString("hash-format");
                HashFormat hf = hashFormatStr != null ? HashFormat.fromName(hashFormatStr) : null;
                String hash = fileTable.getString("hash");

                FileEntry entry = new FileEntry(filePath, hf, hash);

                Boolean metafile = fileTable.getBoolean("metafile");
                if (metafile != null) entry.metafile = metafile;

                Boolean preserve = fileTable.getBoolean("preserve");
                if (preserve != null) entry.preserve = preserve;

                Boolean optional = fileTable.getBoolean("optional");
                if (optional != null) entry.optional = optional;

                String aliasStr = fileTable.getString("alias");
                if (aliasStr != null) {
                    entry.alias = basePath.resolve(aliasStr);
                }

                indexFile.files.add(entry);
            }
        }

        return indexFile;
    }

    /** 检查 index 中是否包含指定路径的文件 */
    public boolean containsPath(PackwizPath<?> destPath, PackwizFilePath packFolder) {
        for (FileEntry entry : files) {
            PackwizPath<?> entryDest = entry.getDestURI();
            PackwizPath<?> rebased = entryDest.rebase(packFolder);
            if (rebased.equals(destPath)) return true;
        }
        return false;
    }
}
