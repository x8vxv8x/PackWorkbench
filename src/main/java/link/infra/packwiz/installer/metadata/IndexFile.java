package link.infra.packwiz.installer.metadata;

import link.infra.packwiz.installer.metadata.hash.Hash;
import link.infra.packwiz.installer.metadata.hash.HashFormat;
import link.infra.packwiz.installer.target.ClientHolder;
import link.infra.packwiz.installer.target.path.PackwizPath;

import com.moandjiezana.toml.Toml;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents an index.toml file - the list of files in a packwiz modpack.
 */
public class IndexFile {
    public List<FileEntry> files = new ArrayList<>();

    public static class FileEntry {
        public PackwizPath<?> file;
        public HashFormat hashFormat;
        public String hash;
        public boolean preserve = false;
        public boolean optional = false;

        // Resolved after metadata download
        public transient ModFile linkedFile = null;

        public FileEntry(PackwizPath<?> file, HashFormat hashFormat, String hash) {
            this.file = file;
            this.hashFormat = hashFormat;
            this.hash = hash;
        }

        public Hash<?> getHashObj(IndexFile index) {
            return hashFormat.fromString(hash);
        }

        public HashFormat getHashFormat(IndexFile index) {
            return hashFormat;
        }

        /**
         * Download the linked mod metadata file.
         */
        public void downloadMeta(IndexFile index, ClientHolder clientHolder) throws Exception {
            InputStream input = file.source(clientHolder);
            ModFile modFile = ModFile.fromToml(input, file.parent());
            input.close();
            this.linkedFile = modFile;
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

        public PackwizPath<?> getDestURI() {
            if (linkedFile != null && linkedFile.filename != null) {
                return linkedFile.filename;
            }
            return file;
        }
    }

    public static IndexFile fromToml(InputStream input, PackwizPath<?> basePath) {
        Toml toml = new Toml().read(input);
        IndexFile indexFile = new IndexFile();

        List<Toml> filesList = toml.getTables("files");
        if (filesList != null) {
            for (Toml fileTable : filesList) {
                String fileStr = fileTable.getString("file");
                if (fileStr == null) continue;

                PackwizPath<?> filePath = basePath.resolve(fileStr);
                String hashFormatStr = fileTable.getString("hash-format");
                HashFormat hf = hashFormatStr != null ? HashFormat.fromName(hashFormatStr) : HashFormat.SHA256;
                String hash = fileTable.getString("hash");

                FileEntry entry = new FileEntry(filePath, hf, hash);
                Boolean preserve = fileTable.getBoolean("preserve");
                if (preserve != null) entry.preserve = preserve;
                Boolean optional = fileTable.getBoolean("optional");
                if (optional != null) entry.optional = optional;

                indexFile.files.add(entry);
            }
        }

        return indexFile;
    }
}
