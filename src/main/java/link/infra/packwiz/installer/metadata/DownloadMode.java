package link.infra.packwiz.installer.metadata;

public enum DownloadMode {
    URL,
    CURSEFORGE;

    public static DownloadMode fromString(String s) {
        if (s == null) return URL;
        return switch (s.toLowerCase()) {
            case "url" -> URL;
            case "curseforge" -> CURSEFORGE;
            default -> URL;
        };
    }
}
