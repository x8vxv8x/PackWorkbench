package link.infra.packwiz.installer.project;

import link.infra.packwiz.installer.metadata.curseforge.CurseForgeSourcer;
import link.infra.packwiz.installer.metadata.hash.Hash;
import link.infra.packwiz.installer.metadata.hash.HashFormat;
import link.infra.packwiz.installer.target.ClientHolder;

import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Locale;
import java.util.regex.Pattern;

public class LinkMetadataResolver {
    private static final Pattern CURSEFORGE_FILE_URL = Pattern.compile(
        "^https?://(?:www\\.|legacy\\.|beta\\.)?curseforge\\.com/(?:minecraft|mc-mods|[^/]+)/(?:mc-mods|mods|modpacks|texture-packs|shaders|[^/]+)/([^/]+)/(?:files|download)/(\\d+).*$",
        Pattern.CASE_INSENSITIVE
    );

    public ResolvedLink resolve(String url, String preferredCategory, SourcePreference preference) throws Exception {
        String trimmed = url == null ? "" : url.trim();
        if (trimmed.isBlank()) throw new IllegalArgumentException("链接为空");
        var cfMatcher = CURSEFORGE_FILE_URL.matcher(trimmed);
        if (preference == SourcePreference.CURSEFORGE || (preference == SourcePreference.AUTO && cfMatcher.matches())) {
            if (!cfMatcher.matches()) {
                throw new IllegalArgumentException("不是可识别的 CurseForge 文件链接，请使用包含 /files/<fileId> 或 /download/<fileId> 的链接");
            }
            int fileId = Integer.parseInt(cfMatcher.group(2));
            try (var holder = new CloseableClientHolder()) {
                var meta = CurseForgeSourcer.getFileMetadata(fileId, holder.client());
                return new ResolvedLink(
                    SourceType.CURSEFORGE,
                    preferredCategory,
                    meta.name(),
                    meta.filename(),
                    trimmed,
                    HashFormat.SHA1,
                    meta.sha1(),
                    meta.projectId(),
                    meta.fileId()
                );
            }
        }
        if (preference == SourcePreference.AUTO && trimmed.toLowerCase(Locale.ROOT).contains("curseforge.com")) {
            throw new IllegalArgumentException("这是 CurseForge 链接，但不是文件链接。请打开具体文件版本页面后复制 /files/<fileId> 链接");
        }
        String filename = MetadataWriter.filenameFromUrl(trimmed);
        String name = filename.replaceAll("\\.(jar|zip)$", "");
        String hash;
        try (var holder = new CloseableClientHolder()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(trimmed))
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "packwiz-installer")
                .GET()
                .build();
            var result = holder.client().httpGet(request);
            if (result.code() < 200 || result.code() >= 300) {
                try { result.body().close(); } catch (Exception ignored) {}
                throw new IllegalArgumentException("下载链接返回 HTTP " + result.code());
            }
            try (var body = result.body();
                 Hash.HashingInputStream hashing = HashFormat.SHA256.createSource(body)) {
                hashing.transferTo(OutputStream.nullOutputStream());
                hash = new Hash<>(hashing.getDigest(), HashFormat.SHA256.encoding()).toString();
            }
        }
        return new ResolvedLink(
            SourceType.URL,
            preferredCategory,
            name,
            filename,
            trimmed,
            HashFormat.SHA256,
            hash,
            0,
            0
        );
    }

    public static String inferCategory(String url, String fallback) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lower.contains("resourcepack") || lower.contains("texture-pack")) return "resourcepacks";
        if (lower.contains("shader")) return "shaderpacks";
        return fallback == null || fallback.isBlank() ? "mods" : fallback;
    }

    public enum SourceType {
        CURSEFORGE,
        URL
    }

    public enum SourcePreference {
        AUTO,
        CURSEFORGE,
        URL
    }

    public record ResolvedLink(
        SourceType type,
        String category,
        String name,
        String filename,
        String url,
        HashFormat hashFormat,
        String hash,
        int curseForgeProjectId,
        int curseForgeFileId
    ) {}

    private static class CloseableClientHolder implements AutoCloseable {
        private final ClientHolder client = new ClientHolder();

        ClientHolder client() {
            return client;
        }

        @Override
        public void close() {
            client.close();
        }
    }
}
