// SPDX-License-Identifier: MIT OR Apache-2.0

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Java 21 reference for integration-tests/cases/compiler_inflate.iron. It
 * writes a deterministic raw DEFLATE corpus to the directory named by its
 * argument (Java's Deflater over eight inputs at six levels, three strategies
 * and two flush modes, and hand-built stored, fixed and dynamic blocks that
 * cover every defect zlib rejects) and prints, per stream, Java's Inflater
 * verdict: `name ok <length> <sha256 prefix> <consumed>` or `name error`.
 */
public final class InflateCorpus {
    private static final List<String> LINES = new ArrayList<>();
    private static Path root;

    /** Writes bits least significant first, as DEFLATE packs everything but Huffman codes. */
    static final class Bits {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer;
        int count;

        Bits put(int value, int length) {
            for (int bit = 0; bit < length; bit++) {
                buffer |= ((value >>> bit) & 1) << count;
                if (++count == 8) {
                    out.write(buffer);
                    buffer = 0;
                    count = 0;
                }
            }
            return this;
        }

        // Huffman codes go most significant bit first.
        Bits code(int code, int length) {
            for (int bit = length - 1; bit >= 0; bit--) put((code >>> bit) & 1, 1);
            return this;
        }

        Bits align() {
            if (count > 0) put(0, 8 - count);
            return this;
        }

        Bits bytes(byte[] data) {
            align();
            out.writeBytes(data);
            return this;
        }

        byte[] done() {
            align();
            return out.toByteArray();
        }
    }

    /** Canonical codes for the given lengths (RFC 1951 3.2.2). */
    static int[] canonical(int[] lengths) {
        int[] count = new int[16];
        for (int length : lengths) count[length]++;
        count[0] = 0;
        int[] next = new int[16];
        int code = 0;
        for (int bits = 1; bits < 16; bits++) {
            code = (code + count[bits - 1]) << 1;
            next[bits] = code;
        }
        int[] codes = new int[lengths.length];
        for (int symbol = 0; symbol < lengths.length; symbol++) {
            if (lengths[symbol] != 0) codes[symbol] = next[lengths[symbol]]++;
        }
        return codes;
    }

    static final int[] ORDER = {16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15};

    /**
     * A dynamic block header. The code-length code gives symbols 0-12 and
     * 16-18 length 4 (complete) unless codeLengthLengths overrides it; the
     * literal/length and distance lengths are sent one symbol at a time.
     */
    static int[] header(Bits bits, boolean last, int[] literalLengths, int[] distanceLengths, int literalField,
                        int distanceField, int[] codeLengthLengths) {
        int[] clLengths = codeLengthLengths;
        if (clLengths == null) {
            clLengths = new int[19];
            for (int symbol = 0; symbol <= 12; symbol++) clLengths[symbol] = 4;
            clLengths[16] = clLengths[17] = clLengths[18] = 4;
        }
        int[] clCodes = canonical(clLengths);
        bits.put(last ? 1 : 0, 1).put(2, 2);
        bits.put(literalField, 5).put(distanceField, 5).put(19 - 4, 4);
        for (int symbol : ORDER) bits.put(clLengths[symbol], 3);
        for (int length : literalLengths) bits.code(clCodes[length], clLengths[length]);
        for (int length : distanceLengths) bits.code(clCodes[length], clLengths[length]);
        return clCodes;
    }

    static void fixedLiteral(Bits bits, int symbol) {
        if (symbol < 144) bits.code(0x30 + symbol, 8);
        else if (symbol < 256) bits.code(0x190 + symbol - 144, 9);
        else if (symbol < 280) bits.code(symbol - 256, 7);
        else bits.code(0xC0 + symbol - 280, 8);
    }

    static final int[] LENGTH_BASE = {3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83,
        99, 115, 131, 163, 195, 227, 258};
    static final int[] LENGTH_EXTRA = {0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5,
        5, 5, 0};
    static final int[] DISTANCE_BASE = {1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513,
        769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577};
    static final int[] DISTANCE_EXTRA = {0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10,
        11, 11, 12, 12, 13, 13};

    // A match with the fixed codes.
    static void fixedMatch(Bits bits, int length, int distance) {
        int symbol = 28;
        while (LENGTH_BASE[symbol] > length) symbol--;
        fixedLiteral(bits, 257 + symbol);
        bits.put(length - LENGTH_BASE[symbol], LENGTH_EXTRA[symbol]);
        int code = 29;
        while (DISTANCE_BASE[code] > distance) code--;
        bits.code(code, 5);
        bits.put(distance - DISTANCE_BASE[code], DISTANCE_EXTRA[code]);
    }

    static byte[] deflate(byte[] data, int level, int strategy, int flush, int every) {
        Deflater deflater = new Deflater(level, true);
        deflater.setStrategy(strategy);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int position = 0;
        while (position < data.length || position == 0) {
            int chunk = Math.min(every, data.length - position);
            deflater.setInput(data, position, chunk);
            position += chunk;
            if (position == data.length) deflater.finish();
            int mode = position == data.length ? Deflater.NO_FLUSH : flush;
            while (true) {
                int written = deflater.deflate(buffer, 0, buffer.length, mode);
                out.write(buffer, 0, written);
                if (deflater.finished() || written == 0 && deflater.needsInput()) break;
            }
            if (deflater.finished()) break;
        }
        deflater.end();
        return out.toByteArray();
    }

    static void stream(String name, byte[] bytes) throws Exception {
        Path file = root.resolve(String.format("%03d-%s.deflate", LINES.size(), name));
        Files.write(file, bytes);
        Inflater inflater = new Inflater(true);
        inflater.setInput(bytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        String verdict;
        try {
            while (!inflater.finished()) {
                int produced = inflater.inflate(buffer);
                out.write(buffer, 0, produced);
                if (produced == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
            }
            if (inflater.finished()) {
                byte[] data = out.toByteArray();
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).substring(0, 16);
                verdict = "ok " + data.length + " " + digest + " " + (bytes.length - inflater.getRemaining());
            } else {
                verdict = "error";
            }
        } catch (DataFormatException malformed) {
            verdict = "error";
        } finally {
            inflater.end();
        }
        LINES.add(file.getFileName() + " " + verdict);
    }

    static byte[] text(int length, long seed) {
        String[] words = {"class", "free", "defer", "return", "ironwood", "public", "static", "int", "String",
            "archive", "entry", "café", "中文", "{", "}", ";", "(", ")", "\n", "    "};
        Random random = new Random(seed);
        StringBuilder text = new StringBuilder();
        while (text.length() < length) text.append(words[random.nextInt(words.length)]).append(' ');
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]);
        Files.createDirectories(root);
        byte[] random = new byte[70000];
        new Random(42).nextBytes(random);
        byte[] cycle = new byte[256000];
        for (int index = 0; index < cycle.length; index++) cycle[index] = (byte) index;
        byte[] runs = new byte[100000];
        java.util.Arrays.fill(runs, (byte) 'a');
        byte[] window = new byte[40000];
        new Random(7).nextBytes(window);
        byte[][] inputs = {new byte[0], "a".getBytes(StandardCharsets.UTF_8), "abcabcabc".getBytes(StandardCharsets.UTF_8),
            runs, cycle, random, text(300000, 1), concat(window, text(5000, 2), window)};
        String[] names = {"empty", "one", "short", "runs", "cycle", "random", "text", "window"};
        int[] levels = {0, 1, 2, 4, 6, 9};
        int[] strategies = {Deflater.DEFAULT_STRATEGY, Deflater.FILTERED, Deflater.HUFFMAN_ONLY};
        String[] strategyNames = {"default", "filtered", "huffman"};
        for (int input = 0; input < inputs.length; input++) {
            for (int level : levels) {
                for (int strategy = 0; strategy < strategies.length; strategy++) {
                    stream(names[input] + "-l" + level + "-" + strategyNames[strategy],
                            deflate(inputs[input], level, strategies[strategy], Deflater.NO_FLUSH, Integer.MAX_VALUE));
                }
            }
            stream(names[input] + "-sync", deflate(inputs[input], 6, Deflater.DEFAULT_STRATEGY, Deflater.SYNC_FLUSH, 1000));
            stream(names[input] + "-full", deflate(inputs[input], 6, Deflater.DEFAULT_STRATEGY, Deflater.FULL_FLUSH, 5000));
        }
        byte[] text = deflate(inputs[6], 6, Deflater.DEFAULT_STRATEGY, Deflater.NO_FLUSH, Integer.MAX_VALUE);
        stream("trailing-garbage", concat(text, "trailing bytes".getBytes(StandardCharsets.UTF_8)));
        stream("truncated-text", java.util.Arrays.copyOf(text, text.length - 1));
        stream("truncated-half", java.util.Arrays.copyOf(text, text.length / 2));
        stream("no-input", new byte[0]);

        // Stored blocks.
        stream("stored-empty", new Bits().put(1, 1).put(0, 2).align().put(0, 16).put(0xFFFF, 16).done());
        byte[] maximum = new byte[65535];
        new Random(3).nextBytes(maximum);
        stream("stored-maximum", new Bits().put(1, 1).put(0, 2).align().put(65535, 16).put(0, 16).bytes(maximum).done());
        stream("stored-lengths-mismatch", new Bits().put(1, 1).put(0, 2).align().put(5, 16).put(0xFFFF, 16)
                .bytes(new byte[5]).done());
        stream("stored-truncated", new Bits().put(1, 1).put(0, 2).align().put(10, 16).put(0xFFF5, 16)
                .bytes(new byte[5]).done());
        stream("block-type-3", new Bits().put(1, 1).put(3, 2).done());
        stream("nonfinal-only", new Bits().put(0, 1).put(0, 2).align().put(1, 16).put(0xFFFE, 16)
                .bytes(new byte[]{7}).done());

        // Fixed-Huffman blocks.
        Bits overlap = new Bits().put(1, 1).put(1, 2);
        fixedLiteral(overlap, 'x');
        for (int round = 0; round < 4; round++) fixedMatch(overlap, 258, 1);
        fixedLiteral(overlap, 256);
        stream("fixed-overlap", overlap.done());
        Bits every = new Bits().put(1, 1).put(1, 2);
        for (int symbol = 0; symbol < 256; symbol++) fixedLiteral(every, symbol);
        for (int length = 3; length <= 258; length++) fixedMatch(every, length, 1 + length % 256);
        for (int code = 0; code < 30; code++) {
            fixedMatch(every, 3, Math.min(DISTANCE_BASE[code], 255));
        }
        fixedLiteral(every, 256);
        stream("fixed-every-length", every.done());
        byte[] block = new byte[32768];
        new Random(11).nextBytes(block);
        Bits far = new Bits().put(0, 1).put(0, 2).align().put(32768, 16).put(0x7FFF, 16).bytes(block);
        far.put(1, 1).put(1, 2);
        fixedMatch(far, 258, 32768);
        fixedMatch(far, 3, 32768);
        fixedLiteral(far, 256);
        stream("fixed-window-32768", far.done());
        Bits tooFar = new Bits().put(1, 1).put(1, 2);
        fixedLiteral(tooFar, 'y');
        fixedMatch(tooFar, 3, 2);
        fixedLiteral(tooFar, 256);
        stream("fixed-distance-too-far", tooFar.done());
        for (int symbol : new int[]{286, 287}) {
            Bits invalid = new Bits().put(1, 1).put(1, 2);
            fixedLiteral(invalid, symbol);
            stream("fixed-literal-" + symbol, invalid.done());
        }
        for (int code : new int[]{30, 31}) {
            Bits invalid = new Bits().put(1, 1).put(1, 2);
            fixedLiteral(invalid, 'z');
            fixedLiteral(invalid, 257);
            invalid.code(code, 5);
            stream("fixed-distance-" + code, invalid.done());
        }
        Bits blocks = new Bits();
        for (int index = 0; index < 5; index++) {
            blocks.put(index == 4 ? 1 : 0, 1).put(1, 2);
            fixedLiteral(blocks, 'A' + index);
            if (index > 0) fixedMatch(blocks, 4, 2);
            fixedLiteral(blocks, 256);
        }
        stream("fixed-five-blocks", blocks.done());

        // Dynamic-Huffman blocks.
        int[] literals = new int[258];
        literals['a'] = literals['b'] = literals[256] = literals[257] = 2;
        int[] distances = {1};
        Bits basic = new Bits();
        header(basic, true, literals, distances, 1, 0, null);
        int[] literalCodes = canonical(literals);
        basic.code(literalCodes['a'], 2).code(literalCodes['b'], 2).code(literalCodes[257], 2).code(0, 1)
                .code(literalCodes[256], 2);
        stream("dynamic-basic", basic.done());
        int[] incomplete = new int[257];
        incomplete['a'] = incomplete[256] = 2;
        Bits partial = new Bits();
        header(partial, true, incomplete, new int[]{0}, 0, 0, null);
        stream("dynamic-incomplete-literals", partial.code(0, 2).code(1, 2).done());
        int[] single = new int[257];
        single[256] = 1;
        Bits onlyEnd = new Bits();
        header(onlyEnd, true, single, new int[]{0}, 0, 0, null);
        stream("dynamic-single-end-code", onlyEnd.code(0, 1).done());
        Bits unusedHalf = new Bits();
        header(unusedHalf, true, single, new int[]{0}, 0, 0, null);
        stream("dynamic-single-code-unused-half", unusedHalf.code(1, 1).done());
        int[] over = new int[257];
        over['a'] = over['b'] = over[256] = 1;
        Bits oversubscribed = new Bits();
        header(oversubscribed, true, over, new int[]{0}, 0, 0, null);
        stream("dynamic-oversubscribed", oversubscribed.done());
        int[] noEnd = new int[257];
        noEnd['a'] = noEnd['b'] = 1;
        Bits missing = new Bits();
        header(missing, true, noEnd, new int[]{0}, 0, 0, null);
        stream("dynamic-missing-end", missing.done());
        int[] two = new int[257];
        two['q'] = two[256] = 1;
        Bits noDistances = new Bits();
        header(noDistances, true, two, new int[]{0}, 0, 0, null);
        noDistances.code(0, 1).code(0, 1).code(1, 1);
        stream("dynamic-empty-distances", noDistances.done());
        int[] withMatch = new int[258];
        withMatch['q'] = withMatch[256] = 2;
        withMatch[257] = 1;
        int[] matchCodes = canonical(withMatch);
        Bits emptyUsed = new Bits();
        header(emptyUsed, true, withMatch, new int[]{0}, 1, 0, null);
        emptyUsed.code(matchCodes['q'], 2).code(matchCodes[257], 1);
        stream("dynamic-empty-distances-used", emptyUsed.done());
        Bits tooManyLiterals = new Bits().put(1, 1).put(2, 2).put(30, 5).put(0, 5).put(15, 4);
        stream("dynamic-287-literals", tooManyLiterals.done());
        Bits tooManyDistances = new Bits().put(1, 1).put(2, 2).put(0, 5).put(30, 5).put(15, 4);
        stream("dynamic-31-distances", tooManyDistances.done());
        int[] clLengths = new int[19];
        for (int symbol = 0; symbol <= 12; symbol++) clLengths[symbol] = 4;
        clLengths[16] = clLengths[17] = clLengths[18] = 4;
        int[] clCodes = canonical(clLengths);
        Bits repeatFirst = new Bits().put(1, 1).put(2, 2).put(0, 5).put(0, 5).put(15, 4);
        for (int symbol : ORDER) repeatFirst.put(clLengths[symbol], 3);
        repeatFirst.code(clCodes[16], 4).put(0, 2);
        stream("dynamic-repeat-first", repeatFirst.done());
        Bits repeatOver = new Bits().put(1, 1).put(2, 2).put(0, 5).put(0, 5).put(15, 4);
        for (int symbol : ORDER) repeatOver.put(clLengths[symbol], 3);
        repeatOver.code(clCodes[18], 4).put(127, 7).code(clCodes[18], 4).put(127, 7).code(clCodes[18], 4).put(127, 7);
        stream("dynamic-repeat-overflow", repeatOver.done());
        int[] clIncomplete = new int[19];
        clIncomplete[0] = clIncomplete[1] = 2;
        Bits codeIncomplete = new Bits();
        header(codeIncomplete, true, new int[257], new int[]{0}, 0, 0, clIncomplete);
        stream("dynamic-code-lengths-incomplete", codeIncomplete.done());
        int[] longCodes = new int[257];
        for (int symbol = 0; symbol < 14; symbol++) longCodes[symbol] = symbol + 1;
        longCodes[14] = 15;
        longCodes[256] = 15;
        int[] longCl = new int[19];
        for (int symbol = 0; symbol <= 15; symbol++) longCl[symbol] = 4;
        int[] longValues = canonical(longCodes);
        Bits slow = new Bits();
        header(slow, true, longCodes, new int[]{0}, 0, 0, longCl);
        for (int symbol = 0; symbol < 15; symbol++) slow.code(longValues[symbol], longCodes[symbol]);
        slow.code(longValues[13], 14).code(longValues[14], 15).code(longValues[256], 15);
        stream("dynamic-fifteen-bit-codes", slow.done());
        Bits truncatedHeader = new Bits();
        header(truncatedHeader, true, literals, distances, 1, 0, null);
        byte[] cut = truncatedHeader.done();
        stream("dynamic-truncated-header", java.util.Arrays.copyOf(cut, cut.length - 3));

        for (String line : LINES) System.out.println(line);
    }
}
