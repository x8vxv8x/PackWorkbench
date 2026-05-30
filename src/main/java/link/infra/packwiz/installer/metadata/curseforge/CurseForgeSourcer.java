package link.infra.packwiz.installer.metadata.curseforge;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class CurseForgeSourcer {
    private static final String API_SERVER = "https://api.curseforge.com";
    // If you fork/derive from packwiz, I request that you obtain your own API key.
    private static final String API_KEY = new String(Base64.getDecoder().decode(
        "JDJhJDEwJHNBWVhqblU1N0EzSmpzcmJYM3JVdk92UWk2NHBLS3BnQ2VpbGc1TUM1UGNKL0RYTmlGWWxh"
    ), StandardCharsets.UTF_8);

    private static final Gson GSON = new Gson();
    private static final Pattern CF_PROJECT_OR_FILE_URL = Pattern.compile(
        "^https?://(?:www\\.|legacy\\.|beta\\.)?curseforge\\.com/(?<game>[^/]+)/(?<category>[^/]+)/(?<slug>[^/]+)(?:/(?:files|download)/(?<fileId>\\d+))?.*$",
        Pattern.CASE_INSENSITIVE
    );
    private static final int DEPENDENCY_REQUIRED = 3;
    private static final int RELEASE = 1;
    private static final List<String> LOADER_IDS = List.of("", "forge", "cauldron", "liteloader", "fabric", "quilt", "neoforge");
    private static final List<String> LOADER_NAMES = List.of("", "Forge", "Cauldron", "Liteloader", "Fabric", "Quilt", "NeoForge");

    private record GetFilesRequest(List<Integer> fileIds) {}
    private record GetModsRequest(List<Integer> modIds) {}
    private record FingerprintRequest(List<Long> fingerprints) {}
    public record ResolveFailure(IndexFile.FileEntry entry, String name, Exception exception, String url, boolean manualOnly) {}

    private static class GetFilesResponse {
        List<CfFile> data = new ArrayList<>();
        static class CfFile {
            int id;
            int modId;
            String downloadUrl;
            String fileName;
            String displayName;
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

    public record CurseForgeFileMetadata(
        int projectId,
        int fileId,
        String name,
        String filename,
        String sha1,
        List<Integer> requiredDependencies
    ) {}

    public record FingerprintMatch(
        long fingerprint,
        int projectId,
        int fileId,
        String projectName,
        String slug,
        String filename,
        String sha1,
        String category,
        List<Integer> requiredDependencies
    ) {}

    public record FingerprintReport(
        List<FingerprintMatch> matches,
        List<Long> unmatched,
        List<Long> partial
    ) {}

    public record CurseForgeProjectFile(
        int projectId,
        int fileId,
        String name,
        String slug,
        String filename,
        String sha1,
        String category,
        List<Integer> requiredDependencies
    ) {}

    public static CurseForgeFileMetadata getFileMetadata(int fileId, ClientHolder clientHolder) throws Exception {
        var reqBody = GSON.toJson(new GetFilesRequest(List.of(fileId)));
        var req = buildCfApiRequest("/v1/mods/files", reqBody);
        HttpResponse<InputStream> res = clientHolder.httpRequest(req);
        if (res.statusCode() < 200 || res.statusCode() >= 300 || res.body() == null) {
            try { if (res.body() != null) res.body().close(); } catch (IOException ignored) {}
            throw new IOException("CurseForge 文件查询失败: HTTP " + res.statusCode());
        }
        SearchFilesResponse resData;
        try (var body = res.body()) {
            resData = GSON.fromJson(new InputStreamReader(body, StandardCharsets.UTF_8), SearchFilesResponse.class);
        }
        if (resData == null || resData.data == null || resData.data.isEmpty()) {
            throw new IOException("CurseForge 未返回文件数据: " + fileId);
        }
        var file = resData.data.getFirst();
        String sha1 = sha1(file);
        String filename = file.fileName != null && !file.fileName.isBlank()
            ? file.fileName : "curseforge-" + file.id + ".jar";
        String name = file.displayName != null && !file.displayName.isBlank()
            ? file.displayName : filename;
        return new CurseForgeFileMetadata(file.modId, file.id, name, filename, sha1, requiredDependencies(file));
    }

    public static CurseForgeProjectFile resolveProject(String input, String minecraftVersion, List<String> loaders,
                                                       ClientHolder clientHolder) throws Exception {
        return resolveProject(input, List.of(minecraftVersion), loaders, clientHolder);
    }

    public static CurseForgeProjectFile resolveProject(String input, List<String> minecraftVersions, List<String> loaders,
                                                       ClientHolder clientHolder) throws Exception {
        ParsedProject parsed = parseProjectInput(input);
        CfMod mod = parsed.projectId() > 0
            ? getMod(parsed.projectId(), clientHolder)
            : searchOne(parsed.slug(), parsed.category(), minecraftVersions.size() == 1 ? curseforgeVersion(minecraftVersions.getFirst()) : "", searchLoaderType(loaders), clientHolder);
        CfFile file = parsed.fileId() > 0
            ? getFile(mod.id, parsed.fileId(), clientHolder)
            : latestCompatibleFile(mod, minecraftVersions, loaders, clientHolder);
        String sha1 = sha1(file);
        return new CurseForgeProjectFile(
            mod.id,
            file.id,
            mod.name != null ? mod.name : file.displayName,
            mod.slug,
            file.fileName != null ? file.fileName : "curseforge-" + file.id + ".jar",
            sha1,
            parsed.category() != null && !parsed.category().isBlank() ? parsed.category() : "mc-mods",
            requiredDependencies(file)
        );
    }

    public static CurseForgeProjectFile checkLatest(int projectId, int currentFileId, String minecraftVersion,
                                                    List<String> loaders, ClientHolder clientHolder) throws Exception {
        return checkLatest(projectId, currentFileId, List.of(minecraftVersion), loaders, clientHolder);
    }

    public static CurseForgeProjectFile checkLatest(int projectId, int currentFileId, List<String> minecraftVersions,
                                                    List<String> loaders, ClientHolder clientHolder) throws Exception {
        CfMod mod = getMod(projectId, clientHolder);
        CfFile latest = latestCompatibleFile(mod, minecraftVersions, loaders, clientHolder);
        if (latest.id == currentFileId) return null;
        return new CurseForgeProjectFile(
            mod.id,
            latest.id,
            mod.name,
            mod.slug,
            latest.fileName,
            sha1(latest),
            "mc-mods",
            requiredDependencies(latest)
        );
    }

    public static FingerprintReport matchFingerprints(Map<Long, Path> fingerprints, ClientHolder clientHolder) throws Exception {
        if (fingerprints.isEmpty()) return new FingerprintReport(List.of(), List.of(), List.of());
        var req = buildCfApiRequest("/v1/fingerprints", GSON.toJson(new FingerprintRequest(new ArrayList<>(fingerprints.keySet()))));
        HttpResponse<InputStream> res = clientHolder.httpRequest(req);
        if (res.statusCode() < 200 || res.statusCode() >= 300 || res.body() == null) {
            try { if (res.body() != null) res.body().close(); } catch (IOException ignored) {}
            throw new IOException("CurseForge 指纹查询失败: HTTP " + res.statusCode());
        }
        FingerprintResponse response;
        try (var body = res.body()) {
            response = GSON.fromJson(new InputStreamReader(body, StandardCharsets.UTF_8), FingerprintResponse.class);
        }
        var matches = new ArrayList<FingerprintMatch>();
        if (response != null && response.data != null && response.data.exactMatches != null) {
            for (CfFingerprintMatch match : response.data.exactMatches) {
                CfMod mod = match.mod != null ? match.mod : getMod(match.id, clientHolder);
                CfFile file = match.file;
                matches.add(new FingerprintMatch(
                    file.fingerprint,
                    mod.id,
                    file.id,
                    mod.name,
                    mod.slug,
                    file.fileName,
                    sha1(file),
                    categoryFromClass(mod.classId, mod.primaryCategoryId),
                    requiredDependencies(file)
                ));
            }
        }
        List<Long> unmatched = response != null && response.data != null && response.data.unmatchedFingerprints != null
            ? response.data.unmatchedFingerprints : List.of();
        List<Long> partial = response != null && response.data != null && response.data.partialMatches != null
            ? response.data.partialMatches : List.of();
        return new FingerprintReport(matches, unmatched, partial);
    }

    public static long fingerprint(Path file) throws IOException {
        byte[] input = Files.readAllBytes(file);
        var normalized = new ByteArrayOutputStream(input.length);
        for (byte b : input) {
            if (b != 9 && b != 10 && b != 13 && b != 32) normalized.write(b);
        }
        byte[] bytes = normalized.toByteArray();
        final int m = 0x5bd1e995;
        final int r = 24;
        int h = 1 ^ bytes.length;
        int len = bytes.length;
        int i = 0;
        while (len >= 4) {
            int k = (bytes[i] & 0xff)
                | ((bytes[i + 1] & 0xff) << 8)
                | ((bytes[i + 2] & 0xff) << 16)
                | ((bytes[i + 3] & 0xff) << 24);
            k *= m;
            k ^= k >>> r;
            k *= m;
            h *= m;
            h ^= k;
            i += 4;
            len -= 4;
        }
        switch (len) {
            case 3 -> h ^= (bytes[i + 2] & 0xff) << 16;
            case 2 -> h ^= (bytes[i + 1] & 0xff) << 8;
            case 1 -> {
                h ^= bytes[i] & 0xff;
                h *= m;
            }
            default -> {}
        }
        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;
        return Integer.toUnsignedLong(h);
    }

    private static CfMod getMod(int projectId, ClientHolder clientHolder) throws Exception {
        HttpRequest req = buildCfApiGetRequest("/v1/mods/" + projectId);
        HttpResponse<InputStream> res = clientHolder.httpRequest(req);
        if (res.statusCode() < 200 || res.statusCode() >= 300 || res.body() == null) {
            try { if (res.body() != null) res.body().close(); } catch (IOException ignored) {}
            throw new IOException("CurseForge 项目查询失败: HTTP " + res.statusCode());
        }
        try (var body = res.body()) {
            var data = GSON.fromJson(new InputStreamReader(body, StandardCharsets.UTF_8), GetModResponse.class);
            return data.data;
        }
    }

    private static CfFile getFile(int projectId, int fileId, ClientHolder clientHolder) throws Exception {
        HttpRequest req = buildCfApiGetRequest("/v1/mods/" + projectId + "/files/" + fileId);
        HttpResponse<InputStream> res = clientHolder.httpRequest(req);
        if (res.statusCode() < 200 || res.statusCode() >= 300 || res.body() == null) {
            try { if (res.body() != null) res.body().close(); } catch (IOException ignored) {}
            throw new IOException("CurseForge 文件查询失败: HTTP " + res.statusCode());
        }
        try (var body = res.body()) {
            var data = GSON.fromJson(new InputStreamReader(body, StandardCharsets.UTF_8), GetFileResponse.class);
            return data.data;
        }
    }

    private static CfMod searchOne(String slugOrSearch, String category, String minecraftVersion, int loader,
                                   ClientHolder clientHolder) throws Exception {
        StringBuilder endpoint = new StringBuilder("/v1/mods/search?gameId=432&pageSize=10");
        if (category != null && !category.isBlank()) {
            int classId = switch (category) {
                case "mc-mods" -> 6;
                case "texture-packs" -> 12;
                default -> 0;
            };
            if (classId > 0) endpoint.append("&classId=").append(classId);
        }
        if (slugOrSearch != null && slugOrSearch.matches("[a-z][\\da-z\\-_]{0,127}")) {
            endpoint.append("&slug=").append(encode(slugOrSearch));
        } else if (slugOrSearch != null && !slugOrSearch.isBlank()) {
            endpoint.append("&searchFilter=").append(encode(slugOrSearch));
        }
        if (minecraftVersion != null && !minecraftVersion.isBlank()) {
            endpoint.append("&gameVersion=").append(encode(minecraftVersion));
        }
        if (loader > 0) endpoint.append("&modLoaderType=").append(loader);
        HttpResponse<InputStream> res = clientHolder.httpRequest(buildCfApiGetRequest(endpoint.toString()));
        if (res.statusCode() < 200 || res.statusCode() >= 300 || res.body() == null) {
            try { if (res.body() != null) res.body().close(); } catch (IOException ignored) {}
            throw new IOException("CurseForge 搜索失败: HTTP " + res.statusCode());
        }
        try (var body = res.body()) {
            var data = GSON.fromJson(new InputStreamReader(body, StandardCharsets.UTF_8), SearchResponse.class);
            if (data == null || data.data == null || data.data.isEmpty()) {
                throw new IOException("CurseForge 未找到项目: " + slugOrSearch);
            }
            return data.data.getFirst();
        }
    }

    private static CfFile latestCompatibleFile(CfMod mod, List<String> minecraftVersions, List<String> loaders,
                                               ClientHolder clientHolder) throws Exception {
        CfFile best = null;
        int bestMc = -1;
        int bestLoader = 0;
        for (CfFile file : mod.latestFiles != null ? mod.latestFiles : List.<CfFile>of()) {
            int mc = highestMinecraftIndex(minecraftVersions, file.gameVersions);
            int loader = loaderIndexForFile(loaders, file);
            if (mc < 0 || loader < 0) continue;
            int compare = compareCandidate(mc, loader, file.id, bestMc, bestLoader, best != null ? best.id : 0);
            if (compare > 0) {
                best = file;
                bestMc = mc;
                bestLoader = loader;
            }
        }
        if (best != null) return best;
        if (mod.latestFilesIndexes != null) {
            for (CfLatestFileIndex index : mod.latestFilesIndexes) {
                int mc = curseforgeMinecraftIndex(minecraftVersions, index.gameVersion);
                int loader = filterLoaderTypeIndex(loaders, index.modLoader);
                if (mc < 0 || loader < 0) continue;
                int compare = compareCandidate(mc, loader, index.fileId, bestMc, bestLoader, best != null ? best.id : 0);
                if (compare <= 0) continue;
                best = getFile(mod.id, index.fileId, clientHolder);
                bestMc = mc;
                bestLoader = loader;
            }
        }
        if (best == null) {
            throw new IOException("未找到兼容当前 Minecraft/Loader 的 CurseForge 文件: " + mod.name);
        }
        return best;
    }

    private static int compareCandidate(int mc, int loader, int fileId, int bestMc, int bestLoader, int bestFileId) {
        int compare = Integer.compare(mc, bestMc);
        if (compare == 0) {
            compare = bestLoader == 0 || loader == 0 ? 0 : Integer.compare(loader, bestLoader);
        }
        if (compare == 0) compare = Integer.compare(fileId, bestFileId);
        return compare;
    }

    private static int highestMinecraftIndex(List<String> minecraftVersions, List<String> fileVersions) {
        if (minecraftVersions == null || minecraftVersions.isEmpty()) return 0;
        if (fileVersions == null || fileVersions.isEmpty()) return -1;
        for (int i = minecraftVersions.size() - 1; i >= 0; i--) {
            if (fileVersions.contains(minecraftVersions.get(i)) || fileVersions.contains(curseforgeVersion(minecraftVersions.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    private static int curseforgeMinecraftIndex(List<String> minecraftVersions, String cfVersion) {
        if (minecraftVersions == null || minecraftVersions.isEmpty()) return 0;
        for (int i = minecraftVersions.size() - 1; i >= 0; i--) {
            if (Objects.equals(cfVersion, minecraftVersions.get(i)) || Objects.equals(cfVersion, curseforgeVersion(minecraftVersions.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    private static int loaderIndexForFile(List<String> loaders, CfFile file) {
        if (loaders == null || loaders.isEmpty()) return 0;
        int best = -1;
        for (int i = 1; i < LOADER_NAMES.size(); i++) {
            String loaderName = LOADER_NAMES.get(i);
            if (loaders.contains(LOADER_IDS.get(i))
                && file.gameVersions != null
                && file.gameVersions.stream().anyMatch(v -> v.equalsIgnoreCase(loaderName))) {
                best = Math.max(best, i);
            }
        }
        return best;
    }

    private static int filterLoaderTypeIndex(List<String> loaders, int modLoader) {
        if (loaders == null || loaders.isEmpty() || modLoader <= 0) return 0;
        if (modLoader < LOADER_IDS.size() && loaders.contains(LOADER_IDS.get(modLoader))) return modLoader;
        return -1;
    }

    private static int searchLoaderType(List<String> loaders) {
        if (loaders == null) return 0;
        boolean fabric = loaders.contains("fabric");
        boolean quilt = loaders.contains("quilt");
        boolean forge = loaders.contains("forge");
        boolean neoforge = loaders.contains("neoforge");
        if (fabric && !quilt && !forge && !neoforge) return 4;
        if (forge && !neoforge && !fabric && !quilt) return 1;
        return 0;
    }

    public static int mapDependencyOverride(int depId, boolean quilt, String minecraftVersion) {
        if (quilt && depId == 306612) return 634179;
        if (quilt && depId == 308769 && compareMinecraftVersion(minecraftVersion, "1.19.1") > 0 && compareMinecraftVersion(minecraftVersion, "2.0.0") < 0) {
            return 720410;
        }
        return depId;
    }

    private static int bestLoader(List<String> loaders) {
        if (loaders == null) return 0;
        for (String loader : loaders) {
            String lower = loader.toLowerCase(Locale.ROOT);
            if ("neoforge".equals(lower)) return 6;
            if ("quilt".equals(lower)) return 5;
            if ("fabric".equals(lower)) return 4;
            if ("forge".equals(lower)) return 1;
        }
        return 0;
    }

    private static String sha1(CfFile file) {
        return file.hashes == null ? "" : file.hashes.stream()
            .filter(hash -> hash.algo == 1)
            .map(hash -> hash.value)
            .filter(Objects::nonNull)
            .filter(value -> value.length() == 40)
            .findFirst()
            .orElse("");
    }

    private static List<Integer> requiredDependencies(CfFile file) {
        if (file.dependencies == null) return List.of();
        return file.dependencies.stream()
            .filter(dep -> dep.relationType == 3)
            .map(dep -> dep.modId)
            .distinct()
            .toList();
    }

    private static String categoryFromClass(int classId, int categoryId) {
        if (classId == 12 || categoryId == 12) return "resourcepacks";
        if (classId == 6552 || categoryId == 6552) return "shaderpacks";
        return "mods";
    }

    private static String curseforgeVersion(String version) {
        if (version == null) return "";
        for (String marker : List.of("-pre", " Pre-Release ", " Pre-release ", "-rc")) {
            int index = version.indexOf(marker);
            if (index > -1) return version.substring(0, index) + "-Snapshot";
        }
        var matcher = Pattern.compile("(?:Snapshot )?(\\d+)w0?(0|[1-9]\\d*)([a-z])").matcher(version);
        if (!matcher.find()) return version;
        int year = Integer.parseInt(matcher.group(1));
        int week = Integer.parseInt(matcher.group(2));
        if (year >= 22 && week >= 11) return "1.19-Snapshot";
        if ((year == 21 && week >= 37) || year >= 22) return "1.18-Snapshot";
        if ((year == 20 && week >= 45) || (year == 21 && week <= 20)) return "1.17-Snapshot";
        if (year == 20 && week >= 6) return "1.16-Snapshot";
        if (year == 19 && week >= 34) return "1.15-Snapshot";
        if ((year == 18 && week >= 43) || (year == 19 && week <= 14)) return "1.14-Snapshot";
        if (year == 18 && week >= 30 && week <= 33) return "1.13.1-Snapshot";
        if ((year == 17 && week >= 43) || (year == 18 && week <= 22)) return "1.13-Snapshot";
        if (year == 17 && week == 31) return "1.12.1-Snapshot";
        if (year == 17 && week >= 6 && week <= 18) return "1.12-Snapshot";
        if (year == 16 && week == 50) return "1.11.1-Snapshot";
        if (year == 16 && week >= 32 && week <= 44) return "1.11-Snapshot";
        if (year == 16 && week >= 20 && week <= 21) return "1.10-Snapshot";
        if (year == 16 && week >= 14 && week <= 15) return "1.9.3-Snapshot";
        if ((year == 15 && week >= 31) || (year == 16 && week <= 7)) return "1.9-Snapshot";
        if (year == 14 && week >= 2 && week <= 34) return "1.8-Snapshot";
        if (year == 13 && week >= 47 && week <= 49) return "1.7.4-Snapshot";
        if (year == 13 && week >= 36 && week <= 43) return "1.7.2-Snapshot";
        if (year == 13 && week >= 16 && week <= 26) return "1.6-Snapshot";
        if (year == 13 && week >= 11 && week <= 12) return "1.5.1-Snapshot";
        if (year == 13 && week >= 1 && week <= 10) return "1.5-Snapshot";
        if (year == 12 && week >= 49 && week <= 50) return "1.4.6-Snapshot";
        if (year == 12 && week >= 32 && week <= 42) return "1.4.2-Snapshot";
        if (year == 12 && week >= 15 && week <= 30) return "1.3.1-Snapshot";
        if (year == 12 && week >= 3 && week <= 8) return "1.2.1-Snapshot";
        if (year == 11 && week >= 47 || year == 12 && week <= 1) return "1.1-Snapshot";
        return version;
    }

    private static int compareMinecraftVersion(String left, String right) {
        String[] a = left == null ? new String[0] : left.split("[.-]");
        String[] b = right == null ? new String[0] : right.split("[.-]");
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int ai = i < a.length ? parseVersionPart(a[i]) : 0;
            int bi = i < b.length ? parseVersionPart(b[i]) : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    private static int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part.replaceAll("\\D.*", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private static ParsedProject parseProjectInput(String input) {
        String trimmed = input == null ? "" : input.trim();
        var matcher = CF_PROJECT_OR_FILE_URL.matcher(trimmed);
        if (matcher.matches()) {
            String file = matcher.group("fileId");
            return new ParsedProject(
                0,
                file != null && !file.isBlank() ? Integer.parseInt(file) : 0,
                matcher.group("slug"),
                matcher.group("category")
            );
        }
        if (trimmed.matches("\\d+")) return new ParsedProject(Integer.parseInt(trimmed), 0, "", "mc-mods");
        return new ParsedProject(0, 0, trimmed, "mc-mods");
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record ParsedProject(int projectId, int fileId, String slug, String category) {}

    private static class GetModResponse {
        CfMod data;
    }

    private static class GetFileResponse {
        CfFile data;
    }

    private static class SearchResponse {
        List<CfMod> data = new ArrayList<>();
    }

    private static class SearchFilesResponse {
        List<CfFile> data = new ArrayList<>();
    }

    private static class CfMod {
        int id;
        String name;
        String slug;
        int classId;
        int primaryCategoryId;
        List<CfFile> latestFiles = new ArrayList<>();
        List<CfLatestFileIndex> latestFilesIndexes = new ArrayList<>();
    }

    private static class CfLatestFileIndex {
        int fileId;
        String filename;
        String gameVersion;
        int modLoader;
    }

    private static class CfFile {
        int id;
        int modId;
        String fileName;
        String displayName;
        int releaseType = RELEASE;
        @SerializedName("fileFingerprint")
        long fingerprint;
        List<String> gameVersions = new ArrayList<>();
        List<CfHash> hashes = new ArrayList<>();
        List<CfDependency> dependencies = new ArrayList<>();
    }

    private static class CfHash {
        int algo;
        String value;
    }

    private static class CfDependency {
        int modId;
        int relationType;
    }

    private static class FingerprintResponse {
        FingerprintData data;
    }

    private static class FingerprintData {
        List<CfFingerprintMatch> exactMatches = new ArrayList<>();
        List<Long> exactFingerprints = new ArrayList<>();
        List<Long> partialMatches = new ArrayList<>();
        List<Long> unmatchedFingerprints = new ArrayList<>();
    }

    private static class CfFingerprintMatch {
        int id;
        CfFile file;
        CfMod mod;
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

    private static HttpRequest buildCfApiGetRequest(String endpoint) {
        return HttpRequest.newBuilder(URI.create(API_SERVER + endpoint))
            .header("Accept", "application/json")
            .header("User-Agent", "packwiz-installer")
            .header("X-API-Key", API_KEY)
            .GET()
            .build();
    }
}
