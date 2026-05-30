package link.infra.packwiz.installer.metadata.hash;

import java.io.InputStream;

/**
 * Supported hash formats. Each format knows how to create a hashing stream
 * and how to parse/encode hash values.
 */
public enum HashFormat {
    SHA1("SHA-1", Hash.Encoding.HEX),
    SHA256("SHA-256", Hash.Encoding.HEX),
    SHA512("SHA-512", Hash.Encoding.HEX),
    MD5("MD5", Hash.Encoding.HEX),
    MURMUR2("murmur2", Hash.Encoding.UINT) {
        @Override
        public Hash.HashingInputStream createSource(InputStream upstream) {
            return new Murmur2HasherSource(upstream);
        }

        @Override
        public Hash<?> fromString(String hashStr) {
            long parsed = Long.parseUnsignedLong(hashStr);
            return new Hash<>(Hash.Encoding.UINT.decode(parsed), Hash.Encoding.UINT);
        }
    };

    private final String algorithm;
    private final Hash.Encoding<?> encoding;

    HashFormat(String algorithm, Hash.Encoding<?> encoding) {
        this.algorithm = algorithm;
        this.encoding = encoding;
    }

    /**
     * Create a hashing input stream for this format.
     */
    public Hash.HashingInputStream createSource(InputStream upstream) {
        return new Hash.HashingInputStream(upstream, algorithm);
    }

    /**
     * Parse a hash string into a Hash object.
     */
    @SuppressWarnings("unchecked")
    public Hash<?> fromString(String hashStr) {
        try {
            byte[] decoded = ((Hash.Encoding<Object>) encoding).decode(hashStr);
            return new Hash<>(decoded, encoding);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法 " + name().toLowerCase() + " hash: " + hashStr, e);
        }
    }

    /**
     * Get the hash format from a packwiz hash-format string.
     */
    public static HashFormat fromName(String name) {
        if (name == null) return SHA256; // default
        return switch (name.toLowerCase()) {
            case "sha1", "sha-1" -> SHA1;
            case "sha256", "sha-256" -> SHA256;
            case "sha512", "sha-512" -> SHA512;
            case "md5" -> MD5;
            case "murmur2" -> MURMUR2;
            default -> throw new IllegalArgumentException("Unknown hash format: " + name);
        };
    }

    public String algorithm() { return algorithm; }
    public Hash.Encoding<?> encoding() { return encoding; }
}
