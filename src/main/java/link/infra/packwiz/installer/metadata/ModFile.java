package link.infra.packwiz.installer.metadata;

import link.infra.packwiz.installer.metadata.curseforge.CurseForgeUpdateData;
import link.infra.packwiz.installer.metadata.curseforge.UpdateData;
import link.infra.packwiz.installer.metadata.hash.Hash;
import link.infra.packwiz.installer.metadata.hash.HashFormat;
import link.infra.packwiz.installer.target.ClientHolder;
import link.infra.packwiz.installer.target.Side;
import link.infra.packwiz.installer.target.path.HttpUrlPath;
import link.infra.packwiz.installer.target.path.PackwizPath;

import com.moandjiezana.toml.Toml;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a mod .toml file in a packwiz index.
 */
public class ModFile {
    public String name;
    public PackwizPath<?> filename;
    public Side side = Side.BOTH;
    public boolean pin = false;
    public Download download;
    public Map<String, UpdateData> update = Map.of();
    public Option option = new Option(false, "", false);

    public final Map<String, PackwizPath<?>> resolvedUpdateData = new HashMap<>();

    public record Download(PackwizPath<?> url, HashFormat hashFormat, String hash, DownloadMode mode) {
        public static Download fromToml(Toml toml, PackwizPath<?> basePath) {
            String urlStr = toml.getString("url");
            PackwizPath<?> url = urlStr != null ? basePath.resolve(urlStr) : null;
            String hashFormatStr = toml.getString("hash-format");
            HashFormat hf = hashFormatStr != null ? HashFormat.fromName(hashFormatStr) : HashFormat.SHA256;
            String hash = toml.getString("hash");
            String modeStr = toml.getString("mode");
            DownloadMode mode = DownloadMode.fromString(modeStr);
            return new Download(url, hf, hash, mode);
        }
    }

    public record Option(boolean optional, String description, boolean defaultValue) {
        public static Option fromToml(Toml toml) {
            if (toml == null) return new Option(false, "", false);
            Boolean opt = toml.getBoolean("optional");
            String desc = toml.getString("description");
            Boolean def = toml.getBoolean("default");
            return new Option(
                opt != null && opt,
                desc != null ? desc : "",
                def != null && def
            );
        }
    }

    public Hash<?> getHash() {
        return download.hashFormat.fromString(download.hash);
    }

    public InputStream getSource(ClientHolder clientHolder) throws Exception {
        if (download.mode() == DownloadMode.URL) {
            if (download.url() == null) throw new Exception("No download URL provided");
            return download.url().source(clientHolder);
        } else if (download.mode() == DownloadMode.CURSEFORGE) {
            PackwizPath<?> resolved = resolvedUpdateData.get("curseforge");
            if (resolved == null) throw new Exception("Metadata file specifies CurseForge mode, but is missing metadata");
            return resolved.source(clientHolder);
        }
        throw new Exception("Unknown download mode: " + download.mode());
    }

    public static ModFile fromToml(InputStream input, PackwizPath<?> basePath) {
        Toml toml = new Toml().read(input);
        ModFile mf = new ModFile();
        mf.name = toml.getString("name");

        String filenameStr = toml.getString("filename");
        mf.filename = filenameStr != null ? basePath.resolve(filenameStr) : null;

        String sideStr = toml.getString("side");
        mf.side = sideStr == null || sideStr.isBlank() ? Side.BOTH : Side.from(sideStr);
        Boolean pin = toml.getBoolean("pin");
        mf.pin = pin != null && pin;

        Toml downloadTable = toml.getTable("download");
        if (downloadTable != null) {
            mf.download = Download.fromToml(downloadTable, basePath);
        }

        // Parse update data
        Toml updateTable = toml.getTable("update");
        if (updateTable != null) {
            Map<String, UpdateData> updateMap = new HashMap<>();
            Toml cfTable = updateTable.getTable("curseforge");
            if (cfTable != null) {
                Long fileId = cfTable.getLong("file-id");
                Long projectId = cfTable.getLong("project-id");
                if (fileId != null && projectId != null) {
                    updateMap.put("curseforge", new CurseForgeUpdateData(fileId.intValue(), projectId.intValue()));
                }
            }
            mf.update = updateMap;
        }

        Toml optionTable = toml.getTable("option");
        mf.option = Option.fromToml(optionTable);

        return mf;
    }
}
