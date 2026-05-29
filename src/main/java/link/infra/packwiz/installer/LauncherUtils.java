package link.infra.packwiz.installer;

import com.google.gson.*;
import link.infra.packwiz.installer.metadata.PackFile;
import link.infra.packwiz.installer.ui.IUserInterface;
import link.infra.packwiz.installer.util.Log;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

public class LauncherUtils {
    private final UpdateManager.Options opts;
    private final IUserInterface ui;

    public LauncherUtils(UpdateManager.Options opts, IUserInterface ui) {
        this.opts = opts;
        this.ui = ui;
    }

    public enum LauncherStatus {
        SUCCESSFUL, NO_CHANGES, CANCELLED, NOT_FOUND
    }

    private static final Map<String, String> MOD_LOADERS = Map.of(
        "net.minecraft", "minecraft",
        "net.minecraftforge", "forge",
        "net.neoforged", "neoforge",
        "net.fabricmc.fabric-loader", "fabric",
        "org.quiltmc.quilt-loader", "quilt",
        "com.mumfrey.liteloader", "liteloader"
    );

    private static final Map<String, Integer> COMPONENT_ORDERS = Map.of(
        "net.minecraft", -2,
        "org.lwjgl", -1,
        "org.lwjgl3", -1,
        "net.minecraftforge", 5,
        "net.neoforged", 5,
        "net.fabricmc.fabric-loader", 10,
        "org.quiltmc.quilt-loader", 10,
        "com.mumfrey.liteloader", 10,
        "net.fabricmc.intermediary", 11
    );

    public LauncherStatus handleMultiMC(PackFile pf, Gson gson) throws Exception {
        var manifestPath = opts.multimcFolder().resolve("mmc-pack.json");

        if (!Files.exists(manifestPath.nioPath())) {
            return LauncherStatus.NOT_FOUND;
        }

        JsonObject multimcManifest;
        try (var reader = Files.newBufferedReader(manifestPath.nioPath(), StandardCharsets.UTF_8)) {
            multimcManifest = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            throw new Exception("无法读取 MultiMC 整合包文件", e);
        }

        Log.info("Loaded MultiMC config");

        if (multimcManifest.get("formatVersion") == null || multimcManifest.get("formatVersion").getAsInt() != 1) {
            throw new Exception("不支持的 MultiMC 格式版本 " + multimcManifest.get("formatVersion"));
        }

        boolean manifestModified = false;
        var modLoadersClasses = new HashMap<String, String>();
        for (var entry : MOD_LOADERS.entrySet()) {
            modLoadersClasses.put(entry.getValue(), entry.getKey());
        }

        var loaderVersionsFound = new HashMap<String, String>();
        var outdatedLoaders = new HashSet<String>();
        JsonArray components = multimcManifest.getAsJsonArray("components");
        if (components == null) throw new Exception("mmc-pack.json 无效：缺少 components 键");

        var toRemove = new ArrayList<JsonElement>();
        for (var element : components) {
            var component = element.getAsJsonObject();
            String uid = component.has("uid") ? component.get("uid").getAsString() : null;
            String version = component.has("version") ? component.get("version").getAsString() : null;

            if (uid != null && MOD_LOADERS.containsKey(uid)) {
                String modLoader = MOD_LOADERS.get(uid);
                loaderVersionsFound.put(modLoader, version);
                String expected = pf.versions.get(modLoader);
                if (!Objects.equals(version, expected)) {
                    outdatedLoaders.add(modLoader);
                    toRemove.add(element);
                }
            }
        }
        for (var el : toRemove) components.remove(el);

        for (var entry : MOD_LOADERS.entrySet()) {
            String loader = entry.getValue();
            if ((!loaderVersionsFound.containsKey(loader) || outdatedLoaders.contains(loader))
                && pf.versions.containsKey(loader)) {
                manifestModified = true;
                var obj = new JsonObject();
                obj.addProperty("uid", modLoadersClasses.get(loader));
                obj.addProperty("version", pf.versions.get(loader));
                components.add(obj);
            }
        }

        // Fix inconsistent Intermediary mappings
        for (var it = components.iterator(); it.hasNext(); ) {
            var el = it.next();
            if (el.isJsonObject()) {
                var obj = el.getAsJsonObject();
                var uidEl = obj.get("uid");
                var verEl = obj.get("version");
                String uid = uidEl != null ? uidEl.getAsString() : null;
                String version = verEl != null ? verEl.getAsString() : null;
                if ("net.fabricmc.intermediary".equals(uid) && !Objects.equals(version, pf.versions.get("minecraft"))) {
                    it.remove();
                    manifestModified = true;
                }
            }
        }

        if (manifestModified) {
            // Sort by component order
            var sorted = new ArrayList<>(components.asList());
            sorted.sort(Comparator.comparingInt(el -> {
                if (el.isJsonObject()) {
                    String uid = el.getAsJsonObject().has("uid") ? el.getAsJsonObject().get("uid").getAsString() : null;
                    return COMPONENT_ORDERS.getOrDefault(uid, 100);
                }
                return 100;
            }));
            while (components.size() > 0) components.remove(0);
            sorted.forEach(components::add);

            // Ask user
            var oldVers = loaderVersionsFound.entrySet().stream()
                .map(e -> new String[]{e.getKey(), e.getValue()}).toList();
            var newVers = pf.versions.entrySet().stream()
                .map(e -> new String[]{e.getKey(), e.getValue()}).toList();

            switch (ui.showUpdateConfirmationDialog(oldVers, newVers)) {
                case CANCELLED -> { return LauncherStatus.CANCELLED; }
                case CONTINUE -> { return LauncherStatus.SUCCESSFUL; }
                default -> {}
            }

            Files.write(manifestPath.nioPath(), gson.toJson(multimcManifest).getBytes(StandardCharsets.UTF_8));
            Log.info("Successfully updated mmc-pack.json based on version metadata");
            return LauncherStatus.SUCCESSFUL;
        }

        return LauncherStatus.NO_CHANGES;
    }
}
