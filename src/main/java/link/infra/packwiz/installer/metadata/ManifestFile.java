package link.infra.packwiz.installer.metadata;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import link.infra.packwiz.installer.metadata.hash.Hash;
import link.infra.packwiz.installer.metadata.hash.HashFormat;
import link.infra.packwiz.installer.target.Side;
import link.infra.packwiz.installer.target.path.PackwizFilePath;
import link.infra.packwiz.installer.target.path.PackwizPath;
import link.infra.packwiz.installer.util.Log;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 本地安装清单 (packwiz.json)。
 * 跟踪所有已安装文件的状态，用于增量同步、清理和回滚。
 */
public class ManifestFile {
    public Hash<?> packFileHash = null;
    public Hash<?> indexFileHash = null;
    public Map<PackwizFilePath, File> cachedFiles = new HashMap<>();
    public Side cachedSide = Side.CLIENT;

    public static class File {
        public transient File revert = null;

        public Hash<?> hash = null;
        public Hash<?> linkedFileHash = null;
        public PackwizFilePath cachedLocation = null;

        public boolean isOptional = false;
        public boolean optionValue = true;
        public boolean onlyOtherSide = false;

        public void backup() {
            revert = new File();
            revert.hash = hash;
            revert.linkedFileHash = linkedFileHash;
            revert.cachedLocation = cachedLocation;
            revert.isOptional = isOptional;
            revert.optionValue = optionValue;
            revert.onlyOtherSide = onlyOtherSide;
        }

        public void restoreFromBackup() {
            if (revert != null) {
                hash = revert.hash;
                linkedFileHash = revert.linkedFileHash;
                cachedLocation = revert.cachedLocation;
                isOptional = revert.isOptional;
                optionValue = revert.optionValue;
                onlyOtherSide = revert.onlyOtherSide;
                revert = null;
            }
        }
    }

    // ===== Gson 序列化 =====

    private static Gson createGson(PackwizFilePath packFolder) {
        return new GsonBuilder()
            .registerTypeAdapterFactory(new Hash.TypeHandler())
            .registerTypeAdapter(PackwizFilePath.class, new PackwizFilePathAdapter(packFolder))
            .enableComplexMapKeySerialization()
            .setPrettyPrinting()
            .create();
    }

    /**
     * PackwizFilePath 的 Gson 适配器。
     * 序列化时输出相对路径字符串，反序列化时基于 packFolder 解析。
     */
    public static class PackwizFilePathAdapter extends TypeAdapter<PackwizFilePath> {
        private final PackwizFilePath packFolder;

        public PackwizFilePathAdapter(PackwizFilePath packFolder) {
            this.packFolder = packFolder;
        }

        @Override
        public void write(JsonWriter out, PackwizFilePath value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                // 输出相对路径
                out.value(value.path() != null ? value.path() : "");
            }
        }

        @Override
        public PackwizFilePath read(JsonReader in) throws IOException {
            String pathStr = in.nextString();
            if (pathStr == null || pathStr.isEmpty()) return packFolder;
            return packFolder.resolve(pathStr);
        }
    }

    // ===== 加载和保存 =====

    public static ManifestFile load(Path manifestPath, PackwizFilePath packFolder) {
        if (!Files.exists(manifestPath)) {
            return new ManifestFile();
        }
        try {
            String json = Files.readString(manifestPath, StandardCharsets.UTF_8);
            Gson gson = createGson(packFolder);
            ManifestFile manifest = gson.fromJson(json, ManifestFile.class);
            if (manifest == null) return new ManifestFile();
            if (manifest.cachedFiles == null) manifest.cachedFiles = new HashMap<>();
            return manifest;
        } catch (Exception e) {
            Log.warn("读取清单文件失败，使用空清单: " + e.getMessage());
            return new ManifestFile();
        }
    }

    public void save(Path manifestPath, PackwizFilePath packFolder) {
        try {
            Gson gson = createGson(packFolder);
            Files.writeString(manifestPath, gson.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("保存清单文件失败: " + e.getMessage());
        }
    }
}
