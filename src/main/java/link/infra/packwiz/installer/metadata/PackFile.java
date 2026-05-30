package link.infra.packwiz.installer.metadata;

import link.infra.packwiz.installer.metadata.hash.HashFormat;
import link.infra.packwiz.installer.target.path.PackwizPath;

import com.moandjiezana.toml.Toml;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents a pack.toml file - the entry point for a packwiz modpack.
 */
public class PackFile {
    public String name;
    public String author;
    public String version;
    public PackFormat packFormat = PackFormat.DEFAULT;
    public Map<String, String> versions = Map.of();
    public Map<String, Map<String, Object>> export = Map.of();
    public Map<String, Object> options = Map.of();
    public IndexFileLoc index;

    public record IndexFileLoc(PackwizPath<?> file, HashFormat hashFormat, String hash) {
        public static IndexFileLoc fromToml(Toml toml, PackwizPath<?> basePath) {
            String file = toml.getString("file");
            String hashFormatStr = toml.getString("hash-format");
            String hash = toml.getString("hash");
            HashFormat hf = hashFormatStr != null ? HashFormat.fromName(hashFormatStr) : HashFormat.SHA256;
            return new IndexFileLoc(basePath.resolve(file), hf, hash);
        }
    }

    public static PackFile fromToml(InputStream input, PackwizPath<?> basePath) {
        Toml toml = new Toml().read(input);
        PackFile pf = new PackFile();
        pf.name = toml.getString("name");
        pf.author = toml.getString("author");
        pf.version = toml.getString("version");

        // pack-format 可以是数字 (如 1) 或字符串 (如 "packwiz:1.1.0")
        String formatStr = toml.getString("pack-format");
        if (formatStr != null) {
            String version = formatStr.contains(":") ? formatStr.split(":")[1] : formatStr;
            pf.packFormat = new PackFormat(parseVersion(version));
        } else {
            Long formatLong = toml.getLong("pack-format");
            if (formatLong != null) pf.packFormat = new PackFormat(formatLong.intValue());
        }

        Toml versionsTable = toml.getTable("versions");
        if (versionsTable != null) {
            var map = new java.util.HashMap<String, String>();
            for (Map.Entry<String, Object> entry : versionsTable.entrySet()) {
                map.put(entry.getKey(), entry.getValue().toString());
            }
            pf.versions = map;
        }

        Toml indexTable = toml.getTable("index");
        if (indexTable != null) {
            pf.index = IndexFileLoc.fromToml(indexTable, basePath);
        }

        Toml optionsTable = toml.getTable("options");
        if (optionsTable != null) {
            pf.options = new java.util.LinkedHashMap<>(optionsTable.toMap());
        }

        Toml exportTable = toml.getTable("export");
        if (exportTable != null) {
            var exportMap = new java.util.LinkedHashMap<String, Map<String, Object>>();
            for (Map.Entry<String, Object> entry : exportTable.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Toml child) {
                    exportMap.put(entry.getKey(), new java.util.LinkedHashMap<>(child.toMap()));
                } else if (value instanceof Map<?, ?> rawMap) {
                    var childMap = new java.util.LinkedHashMap<String, Object>();
                    for (var childEntry : rawMap.entrySet()) {
                        childMap.put(String.valueOf(childEntry.getKey()), childEntry.getValue());
                    }
                    exportMap.put(entry.getKey(), childMap);
                }
            }
            pf.export = exportMap;
        }

        return pf;
    }

    public List<String> supportedMinecraftVersions() {
        var versions = new ArrayList<String>();
        Object acceptable = options != null ? options.get("acceptable-game-versions") : null;
        if (acceptable instanceof List<?> list) {
            for (Object value : list) {
                if (value != null && !value.toString().isBlank()) versions.add(value.toString());
            }
        }
        String primary = this.versions.get("minecraft");
        if (primary != null && !primary.isBlank() && !versions.contains(primary)) versions.add(primary);
        return versions;
    }

    /** 从版本字符串 (如 "1.1.0") 提取主版本号 */
    private static int parseVersion(String version) {
        try {
            return Integer.parseInt(version.split("\\.")[0]);
        } catch (Exception e) {
            return 1;
        }
    }
}
