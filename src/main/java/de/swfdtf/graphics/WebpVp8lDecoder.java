package de.swfdtf.graphics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/**
 * Pure-Java decoder for lossless {@code RIFF/WEBP/VP8L} WebP images producing straight RGBA8 pixels.
 *
 * <p>Validates container framing, {@code VP8L} bitstream headers, prefix trees (both 1/2-symbol
 * simple codes and canonical Huffman tables), LZ77 back-references, and optional color caches
 * without any dependency on {@code java.awt} or {@code javax.imageio}.</p>
 */
public final class WebpVp8lDecoder {

    private static final int VP8L_SIGNATURE = 0x2F;
    private static final int MAX_VP8L_DIMENSION = 16_384;
    private static final int NUM_LENGTH_CODES = 24;
    private static final int NUM_DISTANCE_CODES = 40;
    private static final int CODE_LENGTH_CODES = 19;
    private static final int[] CODE_LENGTH_ORDER = {
        17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    };
    private static final int[] DISTANCE_MAP_X = {
        0, 1, 1, -1, 0, 2, 1, -1, 2, -2, 2, -2, 0, 3, 1, -1,
        3, -3, 2, -2, 3, -3, 0, 4, 1, -1, 4, -4, 3, -3, 2, -2,
        4, -4, 0, 5, 1, -1, 5, -5, 3, -3, 4, -4, 2, -2, 5, -5,
        0, 6, 1, -1, 6, -6, 3, -3, 5, -5, 4, -4, 2, -2, 6, -6,
        0, 7, 1, -1, 7, -7, 3, -3, 6, -6, 4, -4, 5, -5, 2, -2,
        7, -7, 0, 8, 1, -1, 8, -8, 3, -3, 7, -7, 4, -4, 6, -6,
        5, -5, 2, -2, 8, -8, 0, 9, 1, -1, 9, -9, 3, -3, 8, -8,
        4, -4, 7, -7, 5, -5, 6, -6
    };
    private static final int[] DISTANCE_MAP_Y = {
        1, 0, 1, 1, 2, 0, 2, 2, 1, 1, 2, 2, 3, 0, 3, 3,
        1, 1, 3, 3, 2, 2, 4, 0, 4, 4, 1, 1, 3, 3, 4, 4,
        2, 2, 5, 0, 5, 5, 1, 1, 4, 4, 3, 3, 5, 5, 2, 2,
        6, 0, 6, 6, 1, 1, 5, 5, 3, 3, 4, 4, 6, 6, 2, 2,
        7, 0, 7, 7, 1, 1, 6, 6, 3, 3, 5, 5, 4, 4, 7, 7,
        2, 2, 8, 0, 8, 8, 1, 1, 7, 7, 3, 3, 6, 6, 4, 4,
        5, 5, 8, 8, 2, 2, 9, 0, 9, 9, 1, 1, 8, 8, 3, 3,
        7, 7, 4, 4, 6, 6, 5, 5
    };
    private static final int COLOR_CACHE_HASH_MULT = 0x1e35a7bd;

    /**
     * Decoded WebP image result containing pixel dimensions and tightly packed straight RGBA8 bytes.
     *
     * @param width        image width in pixels ({@code > 0})
     * @param height       image height in pixels ({@code > 0})
     * @param straightRgba tightly packed top-to-bottom straight RGBA8 byte array
     */
    public record DecodedRgba(int width, int height, byte[] straightRgba) {
        /**
         * Validates dimensions and copies the RGBA byte buffer defensively.
         */
        public DecodedRgba {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Invalid WebP dimensions: " + width + "x" + height);
            }
            Objects.requireNonNull(straightRgba, "straightRgba must not be null");
            straightRgba = straightRgba.clone();
        }

        /**
         * Returns a defensive copy of the straight RGBA8 byte array.
         *
         * @return cloned RGBA8 byte array
         */
        @Override
        public byte[] straightRgba() {
            return straightRgba.clone();
        }
    }

    private WebpVp8lDecoder() {
    }

    /**
     * Returns {@code true} if {@code data} begins with a {@code RIFF....WEBP} signature.
     *
     * @param data raw encoded image bytes
     * @return {@code true} when {@code data} has a RIFF/WEBP header
     */
    public static boolean isWebp(byte[] data) {
        if (data == null || data.length < 12) {
            return false;
        }
        return data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    /**
     * Decodes a lossless {@code RIFF/WEBP/VP8L} byte stream into straight RGBA8 pixels.
     *
     * @param data encoded WebP container bytes
     * @return decoded width, height, and straight RGBA8 pixels
     * @throws IllegalStateException if the RIFF/WEBP container or VP8L bitstream is malformed or unsupported
     */
    public static DecodedRgba decode(byte[] data) {
        if (!isWebp(data) || data.length < 20) {
            throw new IllegalStateException("Invalid or truncated WebP RIFF header");
        }
        ByteBuffer view = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int riffSizeInt = view.getInt(4);
        long riffSize = riffSizeInt & 0xFFFFFFFFL;
        if (riffSize < 4L || 8L + riffSize > data.length) {
            throw new IllegalStateException("Truncated or invalid WebP RIFF size: " + riffSize);
        }
        int limit = (int) (8L + riffSize);
        int pos = 12;
        byte[] vp8lChunk = null;
        while (pos + 8 <= limit) {
            int chunkSizeInt = view.getInt(pos + 4);
            long chunkSize = chunkSizeInt & 0xFFFFFFFFL;
            if (chunkSize > limit - (pos + 8L)) {
                throw new IllegalStateException("Truncated WebP chunk payload at offset " + pos);
            }
            if (data[pos] == 'V' && data[pos + 1] == 'P' && data[pos + 2] == '8' && data[pos + 3] == 'L') {
                vp8lChunk = Arrays.copyOfRange(data, pos + 8, pos + 8 + (int) chunkSize);
                break;
            }
            pos += 8 + (int) chunkSize + ((int) chunkSize & 1);
        }
        if (vp8lChunk == null) {
            throw new IllegalStateException("WebP container does not contain a supported VP8L lossless stream");
        }
        return decodeVp8lPayload(vp8lChunk);
    }

    private static DecodedRgba decodeVp8lPayload(byte[] payload) {
        if (payload.length < 5 || (payload[0] & 0xFF) != VP8L_SIGNATURE) {
            throw new IllegalStateException("Invalid or truncated VP8L signature");
        }
        ByteBuffer view = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int headerBits = view.getInt(1);
        int width = (headerBits & 0x3FFF) + 1;
        int height = ((headerBits >>> 14) & 0x3FFF) + 1;
        int version = (headerBits >>> 29) & 0x7;
        if (version != 0 || width > MAX_VP8L_DIMENSION || height > MAX_VP8L_DIMENSION) {
            throw new IllegalStateException("Unsupported VP8L header parameters: " + width + "x" + height);
        }

        long pixelCountLong = (long) width * (long) height;
        if (pixelCountLong > 67_108_864L) {
            throw new IllegalStateException("VP8L image dimensions exceed safe memory cap");
        }
        int pixelCount = (int) pixelCountLong;

        BitReader bits = new BitReader(payload, 5);
        int transformPresent = bits.readBits(1);
        if (transformPresent != 0) {
            throw new IllegalStateException("VP8L bitstream transforms are not supported by pure-Java decoder");
        }

        int colorCacheBits = 0;
        int[] colorCache = null;
        int colorCacheSize = 0;
        if (bits.readBits(1) != 0) {
            colorCacheBits = bits.readBits(4);
            if (colorCacheBits < 1 || colorCacheBits > 11) {
                throw new IllegalStateException("Invalid VP8L color cache bits: " + colorCacheBits);
            }
            colorCacheSize = 1 << colorCacheBits;
            colorCache = new int[colorCacheSize];
        }

        int metaHuffmanUsed = bits.readBits(1);
        if (metaHuffmanUsed != 0) {
            throw new IllegalStateException("VP8L meta-Huffman blocks are not supported");
        }

        HuffmanTree greenTree = readPrefixCode(bits, 256 + NUM_LENGTH_CODES + colorCacheSize);
        HuffmanTree redTree = readPrefixCode(bits, 256);
        HuffmanTree blueTree = readPrefixCode(bits, 256);
        HuffmanTree alphaTree = readPrefixCode(bits, 256);
        HuffmanTree distTree = readPrefixCode(bits, NUM_DISTANCE_CODES);

        int[] argb = decodePixelData(
                bits, width, pixelCount, greenTree, redTree, blueTree, alphaTree, distTree, colorCache, colorCacheBits
        );
        byte[] straightRgba = new byte[Math.multiplyExact(pixelCount, 4)];
        for (int i = 0; i < pixelCount; i++) {
            int px = argb[i];
            int outIdx = i * 4;
            straightRgba[outIdx] = (byte) ((px >>> 16) & 0xFF);
            straightRgba[outIdx + 1] = (byte) ((px >>> 8) & 0xFF);
            straightRgba[outIdx + 2] = (byte) (px & 0xFF);
            straightRgba[outIdx + 3] = (byte) ((px >>> 24) & 0xFF);
        }
        return new DecodedRgba(width, height, straightRgba);
    }

    private static int[] decodePixelData(
            BitReader bits,
            int width,
            int pixelCount,
            HuffmanTree greenTree,
            HuffmanTree redTree,
            HuffmanTree blueTree,
            HuffmanTree alphaTree,
            HuffmanTree distTree,
            int[] colorCache,
            int colorCacheBits
    ) {
        int[] argb = new int[pixelCount];
        int idx = 0;
        while (idx < pixelCount) {
            int green = greenTree.readSymbol(bits);
            if (green < 256) {
                int red = redTree.readSymbol(bits);
                int blue = blueTree.readSymbol(bits);
                int alpha = alphaTree.readSymbol(bits);
                int px = (alpha << 24) | (red << 16) | (green << 8) | blue;
                argb[idx++] = px;
                insertColorCache(colorCache, colorCacheBits, px);
            } else if (green < 256 + NUM_LENGTH_CODES) {
                int length = readPrefixExtraValue(bits, green - 256);
                int distSym = distTree.readSymbol(bits);
                int distCode = readPrefixExtraValue(bits, distSym);
                int distance = mapDistanceCode(width, distCode);
                if (distance <= 0 || distance > idx || idx + length > pixelCount) {
                    throw new IllegalStateException("Corrupt VP8L LZ77 back-reference");
                }
                for (int k = 0; k < length; k++) {
                    int px = argb[idx - distance];
                    argb[idx++] = px;
                    insertColorCache(colorCache, colorCacheBits, px);
                }
            } else {
                int cacheIndex = green - (256 + NUM_LENGTH_CODES);
                if (colorCache == null || cacheIndex >= colorCache.length) {
                    throw new IllegalStateException("Corrupt VP8L color cache index: " + cacheIndex);
                }
                argb[idx++] = colorCache[cacheIndex];
            }
        }
        return argb;
    }

    private static void insertColorCache(int[] colorCache, int colorCacheBits, int argb) {
        if (colorCache != null) {
            int key = (COLOR_CACHE_HASH_MULT * argb) >>> (32 - colorCacheBits);
            colorCache[key] = argb;
        }
    }

    private static int readPrefixExtraValue(BitReader bits, int symbol) {
        if (symbol < 4) {
            return symbol + 1;
        }
        int extraBits = (symbol - 2) >>> 1;
        int offset = (2 + (symbol & 1)) << extraBits;
        return offset + bits.readBits(extraBits) + 1;
    }

    private static int mapDistanceCode(int width, int distanceCode) {
        if (distanceCode > DISTANCE_MAP_X.length) {
            return distanceCode - DISTANCE_MAP_X.length;
        }
        int dx = DISTANCE_MAP_X[distanceCode - 1];
        int dy = DISTANCE_MAP_Y[distanceCode - 1];
        int linear = dx + dy * width;
        return Math.max(1, linear);
    }

    private static HuffmanTree readPrefixCode(BitReader bits, int alphabetSize) {
        int simple = bits.readBits(1);
        if (simple == 1) {
            int numSymbolsMinus1 = bits.readBits(1);
            int isFirst8Bits = bits.readBits(1);
            int sym0 = bits.readBits(isFirst8Bits == 1 ? 8 : 1);
            if (sym0 >= alphabetSize) {
                throw new IllegalStateException("VP8L simple symbol out of range: " + sym0);
            }
            if (numSymbolsMinus1 == 0) {
                return HuffmanTree.singleSymbol(sym0);
            }
            int sym1 = bits.readBits(8);
            if (sym1 >= alphabetSize) {
                throw new IllegalStateException("VP8L second simple symbol out of range: " + sym1);
            }
            return HuffmanTree.fromCodeLengths(alphabetSize, new int[]{sym0, sym1}, new int[]{1, 1});
        }
        int numCodeLengths = bits.readBits(4) + 4;
        if (numCodeLengths > CODE_LENGTH_CODES) {
            throw new IllegalStateException("Invalid VP8L code-length count: " + numCodeLengths);
        }
        int[] codeLengthCodeLengths = new int[CODE_LENGTH_CODES];
        for (int i = 0; i < numCodeLengths; i++) {
            codeLengthCodeLengths[CODE_LENGTH_ORDER[i]] = bits.readBits(3);
        }
        HuffmanTree clTree = HuffmanTree.fromLengths(codeLengthCodeLengths);

        int maxSymbol = alphabetSize;
        if (bits.readBits(1) != 0) {
            int lengthNBits = 2 + 2 * bits.readBits(3);
            maxSymbol = 2 + bits.readBits(lengthNBits);
            if (maxSymbol > alphabetSize) {
                throw new IllegalStateException("VP8L maxSymbol exceeds alphabet size");
            }
        }

        int[] codeLengths = new int[alphabetSize];
        int prevNonZero = 8;
        int sym = 0;
        while (sym < maxSymbol) {
            int codeLen = clTree.readSymbol(bits);
            if (codeLen < 16) {
                codeLengths[sym++] = codeLen;
                if (codeLen != 0) {
                    prevNonZero = codeLen;
                }
            } else {
                int repeatBits = switch (codeLen) {
                    case 16 -> 2;
                    case 17 -> 3;
                    default -> 7;
                };
                int baseRepeat = codeLen == 18 ? 11 : 3;
                int repeat = baseRepeat + bits.readBits(repeatBits);
                int fill = codeLen == 16 ? prevNonZero : 0;
                if (sym + repeat > maxSymbol) {
                    throw new IllegalStateException("VP8L code-length RLE overflow");
                }
                for (int r = 0; r < repeat; r++) {
                    codeLengths[sym++] = fill;
                }
            }
        }
        return HuffmanTree.fromLengths(codeLengths);
    }

    private static final class BitReader {

        private final byte[] data;
        private int bytePos;
        private long bitBuffer;
        private int bitCount;

        private BitReader(byte[] data, int startByte) {
            this.data = data;
            this.bytePos = startByte;
        }

        private int readBits(int count) {
            if (count == 0) {
                return 0;
            }
            while (bitCount < count) {
                if (bytePos >= data.length) {
                    throw new IllegalStateException("Unexpected end of VP8L bitstream");
                }
                bitBuffer |= (long) (data[bytePos++] & 0xFF) << bitCount;
                bitCount += 8;
            }
            int result = (int) (bitBuffer & ((1L << count) - 1L));
            bitBuffer >>>= count;
            bitCount -= count;
            return result;
        }
    }

    private static final class HuffmanTree {

        private static final int MAX_BITS = 15;
        private final boolean isSingle;
        private final int singleSymbol;
        private final int[] left;
        private final int[] right;
        private final int[] leaf;
        private int nodeCount = 1;

        private HuffmanTree(boolean single, int singleSymbol, int capacity) {
            this.isSingle = single;
            this.singleSymbol = singleSymbol;
            if (single) {
                this.left = null;
                this.right = null;
                this.leaf = null;
            } else {
                this.left = new int[capacity];
                this.right = new int[capacity];
                this.leaf = new int[capacity];
                Arrays.fill(this.left, -1);
                Arrays.fill(this.right, -1);
                Arrays.fill(this.leaf, -1);
            }
        }

        private static HuffmanTree singleSymbol(int symbol) {
            return new HuffmanTree(true, symbol, 0);
        }

        private static HuffmanTree fromCodeLengths(int alphabetSize, int[] symbols, int[] lengths) {
            int[] allLengths = new int[alphabetSize];
            for (int i = 0; i < symbols.length; i++) {
                allLengths[symbols[i]] = lengths[i];
            }
            return fromLengths(allLengths);
        }

        private static HuffmanTree fromLengths(int[] lengths) {
            int nonZeroCount = 0;
            int lastNonZero = -1;
            int maxLen = 0;
            for (int i = 0; i < lengths.length; i++) {
                if (lengths[i] > 0) {
                    nonZeroCount++;
                    lastNonZero = i;
                    maxLen = Math.max(maxLen, lengths[i]);
                }
            }
            if (nonZeroCount == 0) {
                throw new IllegalStateException("VP8L Huffman tree contains no symbols");
            }
            if (nonZeroCount == 1) {
                return singleSymbol(lastNonZero);
            }
            if (maxLen > MAX_BITS) {
                throw new IllegalStateException("VP8L Huffman code length exceeds maximum: " + maxLen);
            }

            int[] blCount = new int[MAX_BITS + 1];
            for (int len : lengths) {
                if (len > 0) {
                    blCount[len]++;
                }
            }
            int[] nextCode = new int[MAX_BITS + 1];
            int code = 0;
            for (int bits = 1; bits <= MAX_BITS; bits++) {
                code = (code + blCount[bits - 1]) << 1;
                nextCode[bits] = code;
            }

            HuffmanTree tree = new HuffmanTree(false, -1, Math.max(4, nonZeroCount * (MAX_BITS + 1)));
            for (int sym = 0; sym < lengths.length; sym++) {
                int len = lengths[sym];
                if (len > 0) {
                    int symCode = nextCode[len]++;
                    tree.insertCanonical(symCode, len, sym);
                }
            }
            return tree;
        }

        private void insertCanonical(int canonicalCode, int len, int symbol) {
            int curr = 0;
            for (int bitPos = len - 1; bitPos >= 0; bitPos--) {
                int bit = (canonicalCode >>> bitPos) & 1;
                int next = (bit == 0) ? left[curr] : right[curr];
                if (next < 0) {
                    next = nodeCount++;
                    if (bit == 0) {
                        left[curr] = next;
                    } else {
                        right[curr] = next;
                    }
                }
                curr = next;
            }
            leaf[curr] = symbol;
        }

        private int readSymbol(BitReader bits) {
            if (isSingle) {
                return singleSymbol;
            }
            int curr = 0;
            while (curr >= 0) {
                if (leaf[curr] >= 0) {
                    return leaf[curr];
                }
                int bit = bits.readBits(1);
                curr = (bit == 0) ? left[curr] : right[curr];
            }
            throw new IllegalStateException("Invalid VP8L Huffman codeword");
        }
    }
}
