package link.infra.packwiz.installer.project;

import com.google.gson.Gson;
import link.infra.packwiz.installer.request.RequestException;
import link.infra.packwiz.installer.target.ClientHolder;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public class ModLoaderVersionService implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final String USER_AGENT = "packwiz-installer";

    private final ClientHolder clientHolder = new ClientHolder();

    public record MinecraftVersions(List<String> versions, String latestRelease, String latestSnapshot) {}
    public record LoaderVersions(List<String> versions, String latest) {}

    public MinecraftVersions fetchMinecraftVersions() throws Exception {
        var uri = URI.create("https://launchermeta.mojang.com/mc/game/version_manifest.json");
        try (InputStream body = get(uri, "application/json")) {
            var manifest = GSON.fromJson(new InputStreamReader(body, StandardCharsets.UTF_8), MinecraftManifest.class);
            if (manifest == null || manifest.versions == null || manifest.versions.isEmpty()) {
                throw new IllegalStateException("Minecraft 版本清单为空");
            }

            var versions = new ArrayList<>(manifest.versions.stream()
                .filter(v -> "release".equalsIgnoreCase(v.type))
                .toList());
            versions.sort(Comparator.comparing((MinecraftManifest.Version v) -> parseInstant(v.releaseTime)).reversed());
            return new MinecraftVersions(
                versions.stream().map(v -> v.id).filter(s -> s != null && !s.isBlank()).toList(),
                manifest.latest != null ? manifest.latest.release : "",
                manifest.latest != null ? manifest.latest.snapshot : ""
            );
        }
    }

    public LoaderVersions fetchLoaderVersions(String loader, String minecraftVersion) throws Exception {
        String normalizedLoader = loader == null ? "" : loader.trim().toLowerCase(Locale.ROOT);
        String normalizedMinecraft = minecraftVersion == null ? "" : minecraftVersion.trim();
        if (normalizedLoader.isBlank() || "none".equals(normalizedLoader)) {
            return new LoaderVersions(List.of(""), "");
        }
        if (normalizedMinecraft.isBlank()) {
            throw new IllegalArgumentException("Minecraft 版本不能为空");
        }

        return switch (normalizedLoader) {
            case "fabric" -> fetchVersionsFromMaven(
                URI.create("https://maven.fabricmc.net/net/fabricmc/fabric-loader/maven-metadata.xml"),
                Function.identity()
            );
            case "quilt" -> fetchVersionsFromMaven(
                URI.create("https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-loader/maven-metadata.xml"),
                Function.identity()
            );
            case "forge" -> fetchForgeStyle(
                URI.create("https://files.minecraftforge.net/maven/net/minecraftforge/forge/maven-metadata.xml"),
                normalizedMinecraft
            );
            case "neoforge" -> fetchNeoForge(normalizedMinecraft);
            case "liteloader" -> fetchLiteloaderStyle(
                URI.create("https://repo.mumfrey.com/content/repositories/snapshots/com/mumfrey/liteloader/maven-metadata.xml"),
                normalizedMinecraft
            );
            default -> throw new IllegalArgumentException("不支持的 Loader: " + loader);
        };
    }

    private LoaderVersions fetchNeoForge(String minecraftVersion) throws Exception {
        if ("1.20.1".equals(minecraftVersion)) {
            return fetchForgeStyle(
                URI.create("https://maven.neoforged.net/releases/net/neoforged/forge/maven-metadata.xml"),
                minecraftVersion
            );
        }
        URI uri = URI.create("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml");
        if (minecraftVersion.startsWith("1.")) {
            String[] parts = minecraftVersion.split("\\.");
            if (parts.length < 2) {
                throw new IllegalArgumentException("无法识别该 Minecraft 版本对应的 NeoForge 版本: " + minecraftVersion);
            }
            String minor = parts.length > 2 ? parts[2] : "0";
            String requiredPrefix = parts[1] + "." + minor + ".";
            return fetchVersionsFromMaven(uri, version -> version.startsWith(requiredPrefix) ? version : null);
        }

        String[] parts = minecraftVersion.split("\\.", 3);
        if (parts.length < 2) {
            throw new IllegalArgumentException("无法识别该 Minecraft 版本对应的 NeoForge 版本: " + minecraftVersion);
        }
        String year = parts[0];
        String major = parts[1];
        String minor = "0";
        String prerelease = "";
        if (parts.length == 3) {
            String[] minorSplit = parts[2].split("-", 2);
            minor = minorSplit[0];
            prerelease = minorSplit.length > 1 ? minorSplit[1] : "";
        } else {
            String[] majorSplit = parts[1].split("-", 2);
            major = majorSplit[0];
            prerelease = majorSplit.length > 1 ? majorSplit[1] : "";
        }
        String requiredPrefix = year + "." + major + "." + minor;
        String requiredSuffix = prerelease;
        return fetchVersionsFromMaven(uri, version ->
            version.startsWith(requiredPrefix) && version.endsWith(requiredSuffix) ? version : null
        );
    }

    private LoaderVersions fetchForgeStyle(URI uri, String minecraftVersion) throws Exception {
        return fetchVersionsFromMaven(uri, version -> {
            int dash = version.indexOf('-');
            if (dash < 0) return null;
            if (!version.substring(0, dash).equals(minecraftVersion)) return null;
            return version.substring(dash + 1);
        });
    }

    private LoaderVersions fetchLiteloaderStyle(URI uri, String minecraftVersion) throws Exception {
        return fetchVersionsFromMaven(uri, version -> {
            int dash = version.indexOf('-');
            if (dash < 0) return null;
            return version.substring(0, dash).equals(minecraftVersion) ? version : null;
        });
    }

    private LoaderVersions fetchVersionsFromMaven(URI uri, Function<String, String> filterMap) throws Exception {
        try (InputStream body = get(uri, "application/xml")) {
            var metadata = parseMavenMetadata(body);
            var versions = new ArrayList<String>();
            for (String version : metadata.versions) {
                String mapped = filterMap.apply(version);
                if (mapped != null && !mapped.isBlank()) versions.add(mapped);
            }
            if (versions.isEmpty()) {
                throw new IllegalStateException("没有找到兼容的 Loader 版本");
            }

            String latest = mapIfPresent(metadata.release, filterMap);
            if (latest == null) latest = mapIfPresent(metadata.latest, filterMap);
            if (latest == null) latest = versions.getLast();
            return new LoaderVersions(List.copyOf(versions), latest);
        }
    }

    private MavenMetadata parseMavenMetadata(InputStream body) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        var document = factory.newDocumentBuilder().parse(body);
        var versioning = document.getElementsByTagName("versioning").item(0);
        if (versioning == null) throw new IllegalStateException("Maven metadata 缺少 versioning");

        var versions = new ArrayList<String>();
        var nodes = document.getElementsByTagName("version");
        for (int i = 0; i < nodes.getLength(); i++) {
            String text = nodes.item(i).getTextContent();
            if (text != null && !text.isBlank()) versions.add(text.trim());
        }
        return new MavenMetadata(textOf(document, "release"), textOf(document, "latest"), versions);
    }

    private InputStream get(URI uri, String accept) {
        var request = HttpRequest.newBuilder(uri)
            .header("Accept", accept)
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
        var response = clientHolder.httpGet(request);
        if (response.code() < 200 || response.code() >= 300) {
            throw new RequestException.Response.HTTP.ErrorCode(uri, response.code());
        }
        return response.body();
    }

    private String mapIfPresent(String version, Function<String, String> filterMap) {
        if (version == null || version.isBlank()) return null;
        String mapped = filterMap.apply(version);
        return mapped == null || mapped.isBlank() ? null : mapped;
    }

    private static String textOf(org.w3c.dom.Document document, String tagName) {
        var nodes = document.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) return "";
        String text = nodes.item(0).getTextContent();
        return text == null ? "" : text.trim();
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }

    @Override
    public void close() {
        clientHolder.close();
    }

    private record MavenMetadata(String release, String latest, List<String> versions) {}

    private static class MinecraftManifest {
        Latest latest;
        List<Version> versions;

        private static class Latest {
            String release;
            String snapshot;
        }

        private static class Version {
            String id;
            String type;
            String releaseTime;
        }
    }
}
