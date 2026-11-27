package com.jajua.aura;

import android.graphics.Bitmap;

import java.io.UnsupportedEncodingException;

/**
 * A small QR Code generator (byte mode, error correction M, versions 1-10), following ISO/IEC 18004 and the structure
 * of Nayuki's public-domain reference implementation. Enough for a t.me link with a short pre-written message; Aura
 * has no libraries, so it carries its own.
 */
final class QrCode {
    // Error correction level M. Index = version (0 unused).
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] NUM_BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final int ECL_FORMAT_BITS = 0; // M

    final int size;
    private final boolean[][] modules;
    private final boolean[][] isFunction;

    private QrCode(int version, byte[] dataCodewords) {
        size = version * 4 + 17;
        modules = new boolean[size][size];
        isFunction = new boolean[size][size];
        drawFunctionPatterns(version);
        byte[] all = addEccAndInterleave(version, dataCodewords);
        drawCodewords(all);
        int best = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            applyMask(m);
            drawFormatBits(m);
            long p = penalty();
            if (p < bestPenalty) {
                best = m;
                bestPenalty = p;
            }
            applyMask(m); // XOR again undoes it
        }
        applyMask(best);
        drawFormatBits(best);
    }

    /** The smallest QR (version 1-10, level M) holding {@code text} as UTF-8, or null if it does not fit. */
    static QrCode encode(String text) {
        byte[] data;
        try {
            data = text.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            return null;
        }
        for (int ver = 1; ver <= 10; ver++) {
            int capacityBits = dataCodewords(ver) * 8;
            int countBits = ver <= 9 ? 8 : 16;
            int used = 4 + countBits + data.length * 8;
            if (used > capacityBits) continue;
            BitBuffer bb = new BitBuffer();
            bb.append(0x4, 4); // byte mode
            bb.append(data.length, countBits);
            for (byte b : data) bb.append(b & 0xFF, 8);
            bb.append(0, Math.min(4, capacityBits - bb.length));
            bb.append(0, (8 - bb.length % 8) % 8);
            for (int pad = 0xEC; bb.length < capacityBits; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
            byte[] codewords = new byte[capacityBits / 8];
            for (int i = 0; i < bb.length; i++) {
                if (bb.get(i)) codewords[i >>> 3] |= (byte) (1 << (7 - (i & 7)));
            }
            return new QrCode(ver, codewords);
        }
        return null;
    }

    boolean get(int x, int y) {
        return x >= 0 && x < size && y >= 0 && y < size && modules[y][x];
    }

    /** Black on white with a quiet zone of {@code border} modules, {@code scale} pixels per module. */
    Bitmap toBitmap(int scale, int border) {
        int px = (size + border * 2) * scale;
        int[] pixels = new int[px * px];
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                boolean dark = get(x / scale - border, y / scale - border);
                pixels[y * px + x] = dark ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        return Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888);
    }

    // ---------------------------------------------------------------- layout

    private void drawFunctionPatterns(int version) {
        for (int i = 0; i < size; i++) {
            setFunction(6, i, i % 2 == 0);
            setFunction(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);
        int[] align = alignmentPositions(version);
        int n = align.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue;
                drawAlignment(align[i], align[j]);
            }
        }
        drawFormatBits(0); // reserves the area; drawn for real after masking
        drawVersion(version);
    }

    private void drawFormatBits(int mask) {
        int data = ECL_FORMAT_BITS << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) setFunction(8, i, bit(bits, i));
        setFunction(8, 7, bit(bits, 6));
        setFunction(8, 8, bit(bits, 7));
        setFunction(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFunction(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) setFunction(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFunction(8, size - 15 + i, bit(bits, i));
        setFunction(8, size - 8, true);
    }

    /** Format bits for a mask, exposed for the self-test (level M, mask 0 = 101010000010010). */
    static int formatBits(int mask) {
        int data = ECL_FORMAT_BITS << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        return (data << 10 | rem) ^ 0x5412;
    }

    private void drawVersion(int version) {
        if (version < 7) return;
        int rem = version;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        int bits = version << 12 | rem;
        for (int i = 0; i < 18; i++) {
            boolean b = bit(bits, i);
            int a = size - 11 + i % 3, c = i / 3;
            setFunction(a, c, b);
            setFunction(c, a, b);
        }
    }

    private void drawFinder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) setFunction(xx, yy, dist != 2 && dist != 4);
            }
        }
    }

    private void drawAlignment(int x, int y) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) setFunction(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
    }

    private int[] alignmentPositions(int version) {
        if (version == 1) return new int[0];
        int num = version / 7 + 2;
        int step = (version * 4 + num * 2 + 1) / (num * 2 - 2) * 2;
        int[] result = new int[num];
        result[0] = 6;
        for (int i = num - 1, pos = size - 7; i >= 1; i--, pos -= step) result[i] = pos;
        return result;
    }

    private void setFunction(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (invert && !isFunction[y][x]) modules[y][x] = !modules[y][x];
            }
        }
    }

    /** Rules 1 (runs of 5+), 2 (2x2 blocks) and 4 (dark balance): enough to avoid a hard-to-read mask. */
    private long penalty() {
        long result = 0;
        for (int y = 0; y < size; y++) {
            int runX = 1, runY = 1;
            for (int x = 1; x < size; x++) {
                if (modules[y][x] == modules[y][x - 1]) {
                    runX++;
                    if (runX == 5) result += 3;
                    else if (runX > 5) result++;
                } else {
                    runX = 1;
                }
                if (modules[x][y] == modules[x - 1][y]) {
                    runY++;
                    if (runY == 5) result += 3;
                    else if (runY > 5) result++;
                } else {
                    runY = 1;
                }
            }
        }
        int dark = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (modules[y][x]) dark++;
                if (x > 0 && y > 0) {
                    boolean c = modules[y][x];
                    if (c == modules[y][x - 1] && c == modules[y - 1][x] && c == modules[y - 1][x - 1]) result += 3;
                }
            }
        }
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        return result + Math.max(0, k) * 10L;
    }

    // ---------------------------------------------------------------- error correction

    private static int rawDataModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int num = ver / 7 + 2;
            result -= (25 * num - 10) * num - 55;
            if (ver >= 7) result -= 36;
        }
        return result;
    }

    static int dataCodewords(int ver) {
        return rawDataModules(ver) / 8 - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver];
    }

    private static byte[] addEccAndInterleave(int ver, byte[] data) {
        int numBlocks = NUM_BLOCKS[ver], eccLen = ECC_PER_BLOCK[ver];
        int raw = rawDataModules(ver) / 8;
        int numShort = numBlocks - raw % numBlocks;
        int shortLen = raw / numBlocks;
        byte[][] blocks = new byte[numBlocks][];
        byte[] divisor = rsDivisor(eccLen);
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int datLen = shortLen - eccLen + (i < numShort ? 0 : 1);
            byte[] dat = new byte[datLen];
            System.arraycopy(data, k, dat, 0, datLen);
            k += datLen;
            byte[] ecc = rsRemainder(dat, divisor);
            byte[] block = new byte[shortLen + 1];
            // Short blocks get a padding byte at the data/ecc boundary so all blocks line up; it is skipped below.
            System.arraycopy(dat, 0, block, 0, datLen);
            System.arraycopy(ecc, 0, block, shortLen + 1 - eccLen, eccLen);
            blocks[i] = block;
        }
        byte[] result = new byte[raw];
        int n = 0;
        for (int i = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < numBlocks; j++) {
                if (i != shortLen - eccLen || j >= numShort) result[n++] = blocks[j][i];
            }
        }
        return result;
    }

    static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) gfMul(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return result;
    }

    static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) gfMul(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private static final class BitBuffer {
        private boolean[] bits = new boolean[64];
        int length;

        void append(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                if (length == bits.length) {
                    boolean[] grown = new boolean[bits.length * 2];
                    System.arraycopy(bits, 0, grown, 0, length);
                    bits = grown;
                }
                bits[length++] = ((value >>> i) & 1) != 0;
            }
        }

        boolean get(int i) {
            return bits[i];
        }
    }
}
