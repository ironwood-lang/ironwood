// SPDX-License-Identifier: MIT OR Apache-2.0

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Java 21 reference for integration-tests/cases/stdlib_crc32.iron: the same
 * inputs, calls and subclasses through java.util.zip.CRC32.
 */
public final class Crc32Reference {
    static final class CountingCrc extends CRC32 {
        int calls;
        int lastOffset;
        int lastLength;

        @Override
        public void update(byte[] b, int off, int len) {
            calls++;
            lastOffset = off;
            lastLength = len;
            super.update(b, off, len);
        }
    }

    static final class WholeMarkerCrc extends CRC32 {
        @Override
        public void update(byte[] b) {
            update(0x5A);
        }
    }

    static final class ByteCountingCrc extends CRC32 {
        int calls;

        @Override
        public void update(int b) {
            calls++;
            super.update(b);
        }
    }

    private static byte[] pattern(int length) {
        byte[] data = new byte[length];
        for (int index = 0; index < length; index++) data[index] = (byte) (index * 31 + 7);
        return data;
    }

    private static void range(CRC32 crc, byte[] data, int off, int len) {
        try {
            crc.update(data, off, len);
            System.out.println("accepted " + off + " " + len);
        } catch (ArrayIndexOutOfBoundsException failure) {
            System.out.println("rejected " + failure.getMessage());
        }
    }

    public static void main(String[] args) {
        CRC32 crc = new CRC32();
        System.out.println("empty " + crc.getValue());
        crc.update("123456789".getBytes(StandardCharsets.UTF_8));
        System.out.println("check " + crc.getValue());
        crc.reset();
        System.out.println("reset " + crc.getValue());
        byte[] data = pattern(300);
        for (int length = 0; length <= 300; length++) {
            crc.reset();
            crc.update(data, 0, length);
            System.out.println("length " + length + " " + crc.getValue());
        }
        crc.reset();
        for (int value = 0; value < 256; value++) {
            crc.update(value);
            System.out.println("byte " + value + " " + crc.getValue());
        }

        int[] values = {-1, -128, 127, 128, 255, 256, 511, 0x12345678, Integer.MIN_VALUE, Integer.MAX_VALUE};
        for (int value : values) {
            crc.reset();
            crc.update(value);
            System.out.println("int " + value + " " + crc.getValue());
        }
        byte negative = (byte) -1;
        short shortValue = (short) -129;
        char unit = 'é';
        crc.reset();
        crc.update(negative);
        crc.update(shortValue);
        crc.update(unit);
        crc.update('z');
        System.out.println("widened " + crc.getValue());

        int[] offsets = {0, 1, 7, 8, 9, 63, 64, 65, 199};
        int[] lengths = {0, 1, 7, 8, 9, 15, 16, 17, 100};
        for (int offset : offsets) {
            for (int length : lengths) {
                crc.reset();
                crc.update(data, offset, length);
                System.out.println("range " + offset + " " + length + " " + crc.getValue());
            }
        }
        crc.reset();
        crc.update(data, 0, 300);
        long whole = crc.getValue();
        boolean same = true;
        for (int split = 0; split <= 300; split++) {
            crc.reset();
            crc.update(data, 0, split);
            crc.update(data, split, 300 - split);
            if (crc.getValue() != whole) same = false;
        }
        System.out.println("splits " + whole + " " + same);
        crc.reset();
        crc.update(data);
        System.out.println("whole " + (crc.getValue() == whole));

        crc.reset();
        byte[] abc = "abc".getBytes(StandardCharsets.UTF_8);
        crc.update(abc);
        long first = crc.getValue();
        long second = crc.getValue();
        crc.update(abc);
        System.out.println("repeat " + first + " " + second + " " + crc.getValue());
        crc.reset();
        crc.update(abc, 1, 2);
        System.out.println("tail " + crc.getValue());

        crc.reset();
        byte[] buffer = new byte[65536];
        for (int chunk = 0; chunk < 1024; chunk++) {
            for (int index = 0; index < buffer.length; index++) buffer[index] = (byte) (chunk + index);
            crc.update(buffer, 0, buffer.length);
        }
        System.out.println("streamed " + crc.getValue());

        crc.reset();
        byte[] small = pattern(4);
        crc.update(small, 0, 2);
        byte[] missing = null;
        try {
            crc.update(missing, 0, 0);
        } catch (NullPointerException failure) {
            System.out.println("null range NullPointerException");
        }
        try {
            crc.update(missing);
        } catch (NullPointerException failure) {
            System.out.println("null whole NullPointerException");
        }
        range(crc, small, -1, 1);
        range(crc, small, 0, -1);
        range(crc, small, 3, 2);
        range(crc, small, 1, Integer.MAX_VALUE);
        range(crc, small, Integer.MAX_VALUE, 1);
        range(crc, small, Integer.MIN_VALUE, 0);
        range(crc, small, 5, 0);
        range(crc, small, 4, 0);
        range(crc, small, 0, 4);
        System.out.println("after failures " + crc.getValue());

        byte[] ten = pattern(10);
        CountingCrc counting = new CountingCrc();
        counting.update(ten);
        System.out.println("counting " + counting.calls + " " + counting.lastOffset + " " + counting.lastLength
                + " " + counting.getValue());
        CRC32 general = counting;
        general.update(ten);
        System.out.println("counting through CRC32 " + counting.calls + " " + counting.getValue());
        WholeMarkerCrc marker = new WholeMarkerCrc();
        marker.update(ten);
        System.out.println("whole marker " + marker.getValue());
        marker.update(ten, 0, 10);
        System.out.println("marker range " + marker.getValue());
        ByteCountingCrc bytes = new ByteCountingCrc();
        bytes.update(ten, 0, 10);
        bytes.update(ten);
        System.out.println("byte calls after ranges " + bytes.calls);
        bytes.update(7);
        System.out.println("byte calls " + bytes.calls + " " + bytes.getValue());
    }
}
