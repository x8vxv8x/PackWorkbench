package link.infra.packwiz.installer.metadata;

import com.google.gson.annotations.JsonAdapter;
import link.infra.packwiz.installer.metadata.hash.Hash;
import link.infra.packwiz.installer.target.Side;
import link.infra.packwiz.installer.target.path.PackwizFilePath;

import java.util.HashMap;
import java.util.Map;

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

        @JsonAdapter(EfficientBooleanAdapter.class)
        public boolean isOptional = false;
        public boolean optionValue = true;

        @JsonAdapter(EfficientBooleanAdapter.class)
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
    }
}
