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
public class Hash<T> {
    private final byte[] value;
    private final transient Encoding<T> encoding;

    public Hash(byte[] value, Encoding<T> encoding) {
        this.value = value;
        this.encoding = encoding;
    }

    public byte[] value() { return value; }
    public Encoding<T> encoding() { return encoding; }

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

    // ===== Gson TypeAdapterFactory for Hash =====

    public static class TypeHandler implements TypeAdapterFactory, JsonSerializer<Hash<?>>, JsonDeserializer<Hash<?>> {
        @Override
        @SuppressWarnings("unchecked")
        public <T> TypeAdapter<T> create(Gson gson, com.google.gson.reflect.TypeToken<T> typeToken) {
            if (typeToken.getRawType() == Hash.class) {
                return (TypeAdapter<T>) new HashTypeAdapter();
            }
            return null;
        }

        @Override
        public JsonElement serialize(Hash<?> hash, java.lang.reflect.Type typeOfT, JsonSerializationContext context) {
            if (hash == null) return JsonNull.INSTANCE;
            return new JsonPrimitive(hash.toString());
        }

        @Override
        @SuppressWarnings("unchecked")
        public Hash<?> deserialize(JsonElement json, java.lang.reflect.Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {
            String val = json.getAsString();
            if (val == null || val.isEmpty()) return null;
            try {
                byte[] decoded = Encoding.HEX.decode(val);
                return new Hash<>(decoded, Encoding.HEX);
            } catch (Exception e) {
                return new Hash<>(val.getBytes(java.nio.charset.StandardCharsets.UTF_8), Encoding.HEX);
            }
        }
    }

    private static class HashTypeAdapter extends TypeAdapter<Hash<?>> {
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
            var token = in.peek();
            if (token == com.google.gson.stream.JsonToken.STRING) {
                // 格式1: "abc123..." (hex 字符串)
                String val = in.nextString();
                if (val.isEmpty()) return null;
                return parseHexString(val);
            } else if (token == com.google.gson.stream.JsonToken.BEGIN_OBJECT) {
                // 格式2/3: {"type":"sha256","value":"abc123..."} 或 {"value":[54,-127,...],"encoding":{}}
                byte[] rawBytes = null;
                String hexValue = null;
                in.beginObject();
                while (in.hasNext()) {
                    String name = in.nextName();
                    if ("value".equals(name)) {
                        if (in.peek() == com.google.gson.stream.JsonToken.STRING) {
                            hexValue = in.nextString();
                        } else if (in.peek() == com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
                            rawBytes = readByteArray(in);
                        } else {
                            in.skipValue();
                        }
                    } else {
                        in.skipValue(); // 跳过 type, encoding 等
                    }
                }
                in.endObject();
                if (rawBytes != null) return new Hash<>(rawBytes, Encoding.HEX);
                if (hexValue != null && !hexValue.isEmpty()) return parseHexString(hexValue);
                return null;
            } else if (token == com.google.gson.stream.JsonToken.NULL) {
                in.nextNull();
                return null;
            } else {
                in.skipValue();
                return null;
            }
        }

        private byte[] readByteArray(JsonReader in) throws IOException {
            var list = new java.util.ArrayList<Byte>();
            in.beginArray();
            while (in.hasNext()) {
                list.add((byte) in.nextInt());
            }
            in.endArray();
            byte[] result = new byte[list.size()];
            for (int i = 0; i < list.size(); i++) result[i] = list.get(i);
            return result;
        }

        private Hash<?> parseHexString(String hex) {
            try {
                return new Hash<>(Encoding.HEX.decode(hex), Encoding.HEX);
            } catch (Exception e) {
                return new Hash<>(hex.getBytes(java.nio.charset.StandardCharsets.UTF_8), Encoding.HEX);
            }
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
