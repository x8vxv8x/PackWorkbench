package link.infra.packwiz.installer.metadata.hash;

import java.io.IOException;
import java.io.InputStream;

/**
 * HashingInputStream implementation for Murmur2 32-bit hash.
 * Computes the hash incrementally as data is read.
 */
public class Murmur2HasherSource extends Hash.HashingInputStream {
    private static final int M = 0x5bd1e995;
    private static final int R = 24;

    private int h;
    private int length;
    private final byte[] buffer = new byte[4];
    private int bufferPos = 0;
    private byte[] digest = null;

    public Murmur2HasherSource(InputStream upstream) {
        super(upstream, "SHA-1"); // dummy algorithm, we override digest
        this.h = 0; // seed 0
        this.length = 0;
    }

    @Override
    public int read() throws IOException {
        int b = getUpstream().read();
        if (b >= 0) {
            processByte((byte) b);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = getUpstream().read(b, off, len);
        if (n > 0) {
            for (int i = 0; i < n; i++) {
                processByte(b[off + i]);
            }
        }
        return n;
    }

    private void processByte(byte b) {
        buffer[bufferPos++] = b;
        length++;

        if (bufferPos == 4) {
            int k = (buffer[0] & 0xff)
                  | ((buffer[1] & 0xff) << 8)
                  | ((buffer[2] & 0xff) << 16)
                  | ((buffer[3] & 0xff) << 24);
            k *= M;
            k ^= k >>> R;
            k *= M;
            h *= M;
            h ^= k;
            bufferPos = 0;
        }
    }

    @Override
    public byte[] getDigest() {
        if (digest != null) return digest.clone();

        // Process remaining bytes (tail)
        if (bufferPos != 0) {
            if (bufferPos >= 3) h ^= (int) (buffer[2] & 0xff) << 16;
            if (bufferPos >= 2) h ^= (int) (buffer[1] & 0xff) << 8;
            if (bufferPos >= 1) h ^= buffer[0] & 0xff;
            h *= M;
            bufferPos = 0;
        }

        // Finalization
        h ^= h >>> 13;
        h *= M;
        h ^= h >>> 15;

        // Return as 4-byte little-endian
        digest = new byte[] {
            (byte) h, (byte) (h >>> 8), (byte) (h >>> 16), (byte) (h >>> 24)
        };
        return digest.clone();
    }
}
