package link.infra.packwiz.installer.metadata.hash;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Represents a hash value with its encoding (hex/uint) and algorithm.
 * Used throughout the project to compare file integrity.
 */
public record Hash<T>(byte[] value, Encoding<T> encoding) {

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Hash<?> other)) return false;
        return java.util.Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return String.valueOf(encoding.encode(value));
    }

    // ===== Encoding: how hash bytes are represented as a value =====

    public interface Encoding<T> {
        T encode(byte[] hash);
        byte[] decode(T encoded);

        Encoding<String> HEX = new Encoding<>() {
            @Override public String encode(byte[] hash) { return HexFormat.of().formatHex(hash); }
            @Override public byte[] decode(String encoded) { return HexFormat.of().parseHex(encoded); }
        };

        Encoding<Long> UINT = new Encoding<>() {
            @Override public Long encode(byte[] hash) {
                long result = 0;
                for (int i = 0; i < Math.min(8, hash.length); i++) {
                    result |= ((long) (hash[i] & 0xFF)) << (i * 8);
                }
                return result;
            }
            @Override public byte[] decode(Long encoded) {
                byte[] result = new byte[8];
                for (int i = 0; i < 8; i++) {
                    result[i] = (byte) (encoded >> (i * 8));
                }
                return result;
            }
        };
    }

    // ===== SourceProvider: creates a HashingInputStream for a given algorithm =====

    @FunctionalInterface
    public interface SourceProvider<T> {
        HashingInputStream createSource(InputStream upstream);

        static <T> SourceProvider<T> fromAlgorithm(String algorithm, Encoding<T> encoding) {
            return upstream -> new HashingInputStream(upstream, algorithm);
        }
    }

    // ===== Gson TypeAdapter for serializing/deserializing Hash =====

    public static class TypeHandler extends TypeAdapter<Hash<?>> {
        @Override
        public void write(JsonWriter out, Hash<?> hash) throws IOException {
            if (hash == null) {
                out.nullValue();
            } else {
                out.value(hash.toString());
            }
        }

        @Override
        public Hash<?> read(JsonReader in) throws IOException {
            // This is a simplified reader - actual deserialization is done via HashFormat.fromString()
            // because we need to know the encoding to parse correctly
            String value = in.nextString();
            return new Hash<>(value.getBytes(), Encoding.HEX);
        }
    }

    /**
     * InputStream wrapper that computes a hash digest while reading.
     */
    public static class HashingInputStream extends InputStream {
        private final InputStream upstream;
        private final MessageDigest digest;

        public HashingInputStream(InputStream upstream, String algorithm) {
            this.upstream = upstream;
            try {
                this.digest = MessageDigest.getInstance(algorithm);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public int read() throws IOException {
            int b = upstream.read();
            if (b >= 0) digest.update((byte) b);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = upstream.read(b, off, len);
            if (n > 0) digest.update(b, off, n);
            return n;
        }

        protected InputStream getUpstream() { return upstream; }

        @Override
        public void close() throws IOException {
            upstream.close();
        }

        public byte[] getDigest() {
            return digest.digest();
        }
    }
}
