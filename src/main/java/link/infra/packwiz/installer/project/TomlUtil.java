package link.infra.packwiz.installer.project;

public final class TomlUtil {
    private TomlUtil() {}

    public static String quote(String value) {
        if (value == null) return "\"\"";
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\"";
    }

    public static String hashFormatName(link.infra.packwiz.installer.metadata.hash.HashFormat format) {
        return switch (format) {
            case SHA1 -> "sha1";
            case SHA256 -> "sha256";
            case SHA512 -> "sha512";
            case MD5 -> "md5";
            case MURMUR2 -> "murmur2";
        };
    }
}
