package link.infra.packwiz.installer.metadata.curseforge;

import com.google.gson.Gson;
import link.infra.packwiz.installer.metadata.IndexFile;
import link.infra.packwiz.installer.metadata.ModFile;
import link.infra.packwiz.installer.metadata.hash.HashFormat;
import link.infra.packwiz.installer.target.ClientHolder;
import link.infra.packwiz.installer.target.path.HttpUrlPath;
import link.infra.packwiz.installer.target.path.PackwizFilePath;

import java.io.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class CurseForgeSourcer {
    private static final String API_SERVER = "https://api.curseforge.com";
    // If you fork/derive from packwiz, I request that you obtain your own API key.
    private static final String API_KEY = new String(Base64.getDecoder().decode(
        "JDJhJDEwJHNBWVhqblU1N0EzSmpzcmJYM3JVdk92UWk2NHBLS3BnQ2VpbGc1TUM1UGNKL0RYTmlGWWxh"
    ), StandardCharsets.UTF_8);

    private static final Gson GSON = new Gson();

    private record GetFilesRequest(List<Integer> fileIds) {}
    private record GetModsRequest(List<Integer> modIds) {}
    public record ResolveFailure(IndexFile.FileEntry entry, String name, Exception exception, String url, boolean manualOnly) {}

    private static class GetFilesResponse {
        List<CfFile> data = new ArrayList<>();
        static class CfFile {
            int id;
            int modId;
            String downloadUrl;
            List<CfHash> hashes = new ArrayList<>();
        }
        static class CfHash {
            int algo;
            String value;
        }
    }

    private static class GetModsResponse {
        List<CfMod> data = new ArrayList<>();
        static class CfMod {
            int id;
            String name;
            CfLinks links;
        }
        static class CfLinks {
            String websiteUrl;
        }
    }

    public static List<ResolveFailure> resolveCfMetadata(
        List<IndexFile.FileEntry> mods,
        PackwizFilePath packFolder,
        ClientHolder clientHolder
    ) {
        var failures = new ArrayList<ResolveFailure>();
        var fileIdMap = new HashMap<Integer, List<IndexFile.FileEntry>>();

        for (var mod : mods) {
            if (mod.linkedFile == null || !mod.linkedFile.update.containsKey("curseforge")) {
                failures.add(new ResolveFailure(
                    mod,
                    mod.linkedFile != null ? mod.linkedFile.name : "unknown",
                    new Exception("解析 CurseForge 元数据失败：没有 CurseForge 更新部分"),
                    null,
                    false
                ));
                continue;
            }
            int fileId = ((CurseForgeUpdateData) mod.linkedFile.update.get("curseforge")).fileId();
            fileIdMap.computeIfAbsent(fileId, k -> new ArrayList<>()).add(mod);
        }

        // Fetch file metadata
        var reqBody = GSON.toJson(new GetFilesRequest(new ArrayList<>(fileIdMap.keySet())));
        var req = buildCfApiRequest("/v1/mods/files", reqBody);
        HttpResponse<InputStream> res;
        try {
            res = clientHolder.httpRequest(req);
        } catch (Exception e) {
            failures.add(new ResolveFailure(null, "其他", new Exception("解析 CurseForge 文件数据元数据失败：" + e.getMessage()), null, false));
            return failures;
        }
        if (res.statusCode() < 200 || res.statusCode() >= 300 || res.body() == null) {
            try { if (res.body() != null) res.body().close(); } catch (IOException ignored) {}
            failures.add(new ResolveFailure(null, "其他", new Exception("解析 CurseForge 文件数据元数据失败：错误代码 " + res.statusCode()), null, false));
            return failures;
        }

        GetFilesResponse resData;
        try (var body = res.body()) {
            resData = GSON.fromJson(new InputStreamReader(body, StandardCharsets.UTF_8), GetFilesResponse.class);
        } catch (IOException e) {
            failures.add(new ResolveFailure(null, "其他", new Exception("读取 CurseForge 响应失败：" + e.getMessage()), null, false));
            return failures;
        }

        var manualDownloadMods = new HashMap<Integer, List<Integer>>();
        for (var file : resData.data) {
            if (!fileIdMap.containsKey(file.id)) {
                failures.add(new ResolveFailure(null, String.valueOf(file.id),
                    new Exception("从结果中找不到文件：ID " + file.id + "，项目 ID " + file.modId), null, false));
                continue;
            }
            applyCurseForgeSha1(file, fileIdMap.get(file.id));
            if (file.downloadUrl == null) {
                manualDownloadMods.computeIfAbsent(file.modId, k -> new ArrayList<>()).add(file.id);
                continue;
            }
            try {
                for (var indexFile : fileIdMap.get(file.id)) {
                    indexFile.linkedFile.resolvedUpdateData.put("curseforge", new HttpUrlPath(URI.create(file.downloadUrl)));
                }
            } catch (IllegalArgumentException e) {
                failures.add(new ResolveFailure(null, String.valueOf(file.id),
                    new Exception("解析 URL 失败：" + file.downloadUrl + "，ID " + file.id + "，项目 ID " + file.modId, e), null, false));
            }
        }

        // Add unresolved files to manualDownloadMods
        for (var entry : fileIdMap.entrySet()) {
            for (var file : entry.getValue()) {
                if (file.linkedFile != null && !file.linkedFile.resolvedUpdateData.containsKey("curseforge")) {
                    int projectId = ((CurseForgeUpdateData) file.linkedFile.update.get("curseforge")).projectId();
                    manualDownloadMods.computeIfAbsent(projectId, k -> new ArrayList<>()).add(entry.getKey());
                }
            }
        }

        // Fetch mod metadata for manual-download mods
        if (!manualDownloadMods.isEmpty()) {
            var reqModsBody = GSON.toJson(new GetModsRequest(new ArrayList<>(manualDownloadMods.keySet())));
            var reqMods = buildCfApiRequest("/v1/mods", reqModsBody);
            HttpResponse<InputStream> resMods;
            try {
                resMods = clientHolder.httpRequest(reqMods);
            } catch (Exception e) {
                failures.add(new ResolveFailure(null, "其他", new Exception("解析 CurseForge 模组数据元数据失败：" + e.getMessage()), null, false));
                return failures;
            }
            if (resMods.statusCode() < 200 || resMods.statusCode() >= 300 || resMods.body() == null) {
                try { if (resMods.body() != null) resMods.body().close(); } catch (IOException ignored) {}
                failures.add(new ResolveFailure(null, "其他", new Exception("解析 CurseForge 模组数据元数据失败：错误代码 " + resMods.statusCode()), null, false));
                return failures;
            }

            GetModsResponse resModsData;
            try (var body = resMods.body()) {
                resModsData = GSON.fromJson(new InputStreamReader(body, StandardCharsets.UTF_8), GetModsResponse.class);
            } catch (IOException e) {
                failures.add(new ResolveFailure(null, "其他", new Exception("读取 CurseForge 模组响应失败：" + e.getMessage()), null, false));
                return failures;
            }

            for (var mod : resModsData.data) {
                if (!manualDownloadMods.containsKey(mod.id)) {
                    failures.add(new ResolveFailure(null, mod.name, new Exception("从结果中找不到项目：ID " + mod.id), null, false));
                    continue;
                }
                for (int fileId : manualDownloadMods.get(mod.id)) {
                    if (!fileIdMap.containsKey(fileId)) {
                        failures.add(new ResolveFailure(null, mod.name, new Exception("从结果中找不到文件：文件 ID " + fileId), null, false));
                        continue;
                    }
                    for (var indexFile : fileIdMap.get(fileId)) {
                        String modUrl = (mod.links != null ? mod.links.websiteUrl : "") + "/files/" + fileId;
                        failures.add(new ResolveFailure(indexFile, indexFile.linkedFile.name,
                            new Exception("此模组已从 CurseForge API 中排除，必须手动下载。\n请前往 " + modUrl + " 并将此文件保存到 " + indexFile.getDestURI().rebase(packFolder)),
                            modUrl, true));
                    }
                }
            }
        }

        return failures;
    }

    private static void applyCurseForgeSha1(GetFilesResponse.CfFile file, List<IndexFile.FileEntry> entries) {
        String sha1 = file.hashes == null ? null : file.hashes.stream()
            .filter(hash -> hash.algo == 1)
            .map(hash -> hash.value)
            .filter(Objects::nonNull)
            .filter(value -> value.length() == 40)
            .findFirst()
            .orElse(null);
        if (sha1 == null) return;

        for (var entry : entries) {
            if (entry.linkedFile == null || entry.linkedFile.download == null) continue;
            var old = entry.linkedFile.download;
            entry.linkedFile.download = new ModFile.Download(old.url(), HashFormat.SHA1, sha1, old.mode());
        }
    }

    private static HttpRequest buildCfApiRequest(String endpoint, String jsonBody) {
        return HttpRequest.newBuilder(URI.create(API_SERVER + endpoint))
            .header("Accept", "application/json")
            .header("User-Agent", "packwiz-installer")
            .header("X-API-Key", API_KEY)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
    }
}
