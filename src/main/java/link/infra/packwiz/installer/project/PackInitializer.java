package link.infra.packwiz.installer.project;

import link.infra.packwiz.installer.metadata.hash.HashFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class PackInitializer {
    public static void initialize(Path root, String name, String author, String version,
                                  String minecraftVersion, String loader, String loaderVersion,
                                  boolean overwrite) throws IOException {
        Files.createDirectories(root);
        Path pack = root.resolve("pack.toml");
        Path index = root.resolve("index.toml");
        if (Files.exists(pack) && !overwrite) {
            throw new IOException("pack.toml 已存在");
        }
        if (!Files.exists(index)) {
            Files.writeString(index, "hash-format = \"sha256\"\n", StandardCharsets.UTF_8);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("name = ").append(TomlUtil.quote(name)).append('\n');
        sb.append("author = ").append(TomlUtil.quote(author)).append('\n');
        sb.append("version = ").append(TomlUtil.quote(version)).append('\n');
        sb.append("pack-format = \"packwiz:1.1.0\"\n\n");
        sb.append("[versions]\n");
        sb.append("minecraft = ").append(TomlUtil.quote(minecraftVersion)).append('\n');
        if (loader != null && !loader.isBlank() && !"none".equalsIgnoreCase(loader)) {
            sb.append(loader.toLowerCase()).append(" = ").append(TomlUtil.quote(loaderVersion)).append('\n');
        }
        sb.append("\n[index]\n");
        sb.append("file = \"index.toml\"\n");
        sb.append("hash-format = \"sha256\"\n");
        Files.writeString(pack, sb.toString(), StandardCharsets.UTF_8);
        new PackRepository(root).updatePackIndexHash(index, HashFormat.SHA256);
        Files.createDirectories(root.resolve("mods").resolve(".index"));
        Files.createDirectories(root.resolve("resourcepacks"));
        Files.createDirectories(root.resolve("shaderpacks"));
    }
}
