package link.infra.packwiz.installer.project;

import link.infra.packwiz.installer.metadata.IndexFile;
import link.infra.packwiz.installer.metadata.hash.HashFormat;
import link.infra.packwiz.installer.target.path.PackwizFilePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class IndexRefresher {
    private final PackRepository repository;
    private final HashFormat hashFormat;

    public IndexRefresher(PackRepository repository) {
        this(repository, HashFormat.SHA256);
    }

    public IndexRefresher(PackRepository repository, HashFormat hashFormat) {
        this.repository = repository;
        this.hashFormat = hashFormat;
    }

    public IndexFile refresh() throws IOException {
        IndexFile index = new IndexFile();
        index.hashFormat = hashFormat;
        PackwizFilePath base = repository.rootPath();
        List<Path> files;
        try (var stream = Files.walk(repository.root())) {
            files = stream
                .filter(Files::isRegularFile)
                .filter(this::shouldIndex)
                .sorted(Comparator.comparing(repository::relativize))
                .toList();
        }
        for (Path file : files) {
            String rel = repository.relativize(file);
            String hash = repository.hashFile(file, hashFormat);
            var entry = new IndexFile.FileEntry(base.resolve(rel), null, hash);
            if (isMetaFile(rel)) {
                entry.metafile = true;
                String dest = destinationForMeta(file, rel);
                if (dest != null) {
                    entry.alias = base.resolve(dest);
                }
            }
            index.files.add(entry);
        }
        return index;
    }

    public int refreshAndWrite() throws IOException {
        IndexFile index = refresh();
        repository.writeIndex(index, hashFormat);
        return index.files.size();
    }

    private boolean shouldIndex(Path path) {
        String rel = repository.relativize(path);
        String lower = rel.toLowerCase(Locale.ROOT);
        if (lower.startsWith("mods/.index/") && lower.endsWith(".pw.toml")) return true;
        if (lower.startsWith("resourcepacks/") && !lower.startsWith("resourcepacks/.index/")) return isTopLevelPackZip(lower, "resourcepacks/");
        return lower.startsWith("shaderpacks/") && !lower.startsWith("shaderpacks/.index/") && isTopLevelPackZip(lower, "shaderpacks/");
    }

    private boolean isTopLevelPackZip(String lower, String folder) {
        String rest = lower.substring(folder.length());
        return !rest.isBlank() && !rest.contains("/") && rest.endsWith(".zip");
    }

    private boolean isMetaFile(String rel) {
        return rel.endsWith(".pw.toml") && rel.contains("/.index/");
    }

    private String destinationForMeta(Path file, String rel) {
        try (var in = Files.newInputStream(file)) {
            var mod = link.infra.packwiz.installer.metadata.ModFile.fromToml(
                in,
                repository.rootPath().resolve(rel).parent()
            );
            if (mod.filename == null) return null;
            return mod.filename.rebase(repository.rootPath()).path();
        } catch (Exception e) {
            return null;
        }
    }
}
