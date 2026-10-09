// SPDX-License-Identifier: MIT OR Apache-2.0

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Java 21 reference for integration-tests/cases/compiler_binary_helpers.iron:
 * Byte/Short.toUnsignedInt, Integer.toUnsignedLong, Long.compareUnsigned,
 * Arrays.compareUnsigned and copyOfRange, US-ASCII decoding with equals,
 * little-endian ByteBuffer reads, and SharedTraceOrder.ordered's comparator
 * (unsigned GUID, then unsigned payload) under the stable List.sort.
 */
public final class BinaryReference {
    private static long seed = 161803L;
    private static final StringBuilder OUT = new StringBuilder();
    static final long[] LONGS = {0L, 1L, 2L, 0x7fL, 0x80L, 0xffL, 0x7fffffffL, 0x80000000L, 0xffffffffL,
        0x7fffffffffffffffL, 0x8000000000000000L, 0x8000000000000001L, -2L, -1L};
    static final byte[] VALUES = {0, 1, 0x7f, (byte) 0x80, (byte) 0xfe, (byte) 0xff};

    private BinaryReference() { }

    record Group(long guid, byte[] bytes, int index) { }

    static int next() {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return (int) (seed >>> 33);
    }

    static long nextLong() {
        return ((long) next() << 33) ^ ((long) next() << 2) ^ next();
    }

    static byte[] seeded(int maximum) {
        byte[] bytes = new byte[next() % (maximum + 1)];
        for (int index = 0; index < bytes.length; index++) bytes[index] = VALUES[next() % VALUES.length];
        return bytes;
    }

    static void println(String line) {
        OUT.append(line).append('\n');
    }

    public static void main(String[] args) {
        for (int value = -128; value < 128; value++) {
            println("byte " + value + " " + Byte.toUnsignedInt((byte) value));
        }
        for (long value : LONGS) {
            short narrow = (short) value;
            int word = (int) value;
            println("short " + narrow + " " + Short.toUnsignedInt(narrow));
            println("int " + word + " " + Integer.toUnsignedLong(word));
        }
        for (int index = 0; index < 1000; index++) {
            short narrow = (short) next();
            int word = next() ^ (next() << 16);
            println("short " + narrow + " " + Short.toUnsignedInt(narrow));
            println("int " + word + " " + Integer.toUnsignedLong(word));
        }
        for (int first = 0; first < LONGS.length; first++) {
            for (int second = 0; second < LONGS.length; second++) {
                println("long " + first + " " + second + " " + Long.compareUnsigned(LONGS[first], LONGS[second]));
            }
        }
        for (int index = 0; index < 1000; index++) {
            long first = nextLong();
            long second = index % 3 == 0 ? first : nextLong();
            println("long seeded " + Long.compareUnsigned(first, second));
        }
        byte[][] fixed = {null, {}, {0}, {0x7f}, {(byte) 0x80}, {(byte) 0xff}, {0, 0}, {0x7f, (byte) 0x80},
            {(byte) 0x80, 0x7f}, {1, 2, 3}, {1, 2}, {1, 2, 4}, {(byte) 0xff, (byte) 0xff}};
        for (int first = 0; first < fixed.length; first++) {
            for (int second = 0; second < fixed.length; second++) {
                println("bytes " + first + " " + second + " " + Arrays.compareUnsigned(fixed[first], fixed[second]));
            }
        }
        for (int index = 0; index < 2000; index++) {
            byte[] first = seeded(6);
            byte[] second = seeded(6);
            println("bytes seeded " + Arrays.compareUnsigned(first, second));
        }
        byte[] source = new byte[12];
        for (int index = 0; index < source.length; index++) source[index] = (byte) (index * 37 - 100);
        for (int from = 0; from <= source.length; from = from + 3) {
            for (int to = from; to <= source.length; to = to + 4) {
                StringBuilder text = new StringBuilder("slice " + from + " " + to);
                for (byte value : Arrays.copyOfRange(source, from, to)) text.append(' ').append(value);
                println(text.toString());
            }
        }
        byte[] probe = {'.', 'p', 's', 'e', 'u', 'd', 'o', '_', 'p', 'r', 'o', 'b', 'e', (byte) 0x80, (byte) 0xff,
            '_', '_', 'p', 'r', 'o', 'b', 'e', 's', 0};
        for (int from = 0; from <= probe.length; from++) {
            for (int to = from; to <= probe.length; to++) {
                String decoded = new String(probe, from, to - from, StandardCharsets.US_ASCII);
                println("ascii " + from + " " + to + " " + decoded.equals(".pseudo_probe") + " "
                        + decoded.equals("__probes") + " " + decoded.equals("") + " " + decoded.equals("\ufffd")
                        + " " + decoded.equals("e\ufffd\ufffd_"));
            }
        }
        byte[] object = new byte[40];
        for (int index = 0; index < object.length; index++) object[index] = (byte) (index * 53 + 0x91);
        ByteBuffer data = ByteBuffer.wrap(object).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer big = ByteBuffer.wrap(object);
        for (int index = 0; index + 2 <= object.length; index++) {
            println("short " + index + " " + data.getShort(index) + " " + data.getShort(index));
        }
        for (int index = 0; index + 4 <= object.length; index++) {
            println("int " + index + " " + data.getInt(index) + " " + data.getInt(index));
        }
        for (int index = 0; index + 8 <= object.length; index++) {
            println("long " + index + " " + data.getLong(index) + " " + data.getLong(index));
        }
        for (int index = 0; index + 8 <= object.length; index = index + 8) {
            println("big " + index + " " + big.getShort(index) + " " + big.getInt(index) + " " + big.getLong(index));
        }
        List<Group> groups = new ArrayList<>();
        long[] guids = {0L, 1L, 0x7fffffffffffffffL, 0x8000000000000000L, -1L, 42L};
        for (int index = 0; index < 600; index++) {
            long guid = next() % 4 == 0 ? nextLong() : guids[next() % guids.length];
            groups.add(new Group(guid, seeded(4), index));
        }
        groups.sort((a, b) -> {
            int order = Long.compareUnsigned(a.guid(), b.guid());
            return order != 0 ? order : Arrays.compareUnsigned(a.bytes(), b.bytes());
        });
        StringBuilder text = new StringBuilder("order");
        for (Group group : groups) text.append(' ').append(group.index());
        println(text.toString());
        System.out.print(OUT);
    }
}
