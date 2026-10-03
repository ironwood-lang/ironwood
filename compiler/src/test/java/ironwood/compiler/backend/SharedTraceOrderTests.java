// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public final class SharedTraceOrderTests {
    private SharedTraceOrderTests() {}

    public static void ordering() throws Exception {
        byte[] high = group(-1, true), low = group(1, false), middle = group(7, true);
        byte[] expected = object(join(low, middle, high));
        for (var groups : List.of(List.of(high, low, middle), List.of(middle, high, low), List.of(low, middle, high))) {
            byte[] original = object(join(groups.toArray(byte[][]::new)));
            byte[] result = SharedTraceOrder.canonicalize(original);
            check(Arrays.equals(expected, result), "root sorting changed a nested record or non-probe bytes");
            check(Arrays.equals(result, SharedTraceOrder.canonicalize(result)), "ordering not idempotent");
            check(Arrays.equals(elf(join(low, middle, high)), SharedTraceOrder.canonicalize(elf(join(groups.toArray(byte[][]::new))))), "ELF ordering changed records");
        }
        byte[] noProbes = new byte[32]; ByteBuffer.wrap(noProbes).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0xfeedfacf).putInt(12, 1);
        check(Arrays.equals(noProbes, SharedTraceOrder.canonicalize(noProbes)), "object without probes changed");
        for (int i = 1; i < high.length; i++) refuse(object(Arrays.copyOf(high, i)));
        for (int[] change : List.of(new int[]{0, 0}, new int[]{12, 6}, new int[]{16, 2}, new int[]{20, 9999},
                new int[]{36, 1}, new int[]{96, 2}, new int[]{152, 4}, new int[]{148, 0x7fffffff}, new int[]{164, 1})) {
            byte[] changed = expected.clone(); ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN).putInt(change[0], change[1]); refuse(changed);
        }
        byte[] absolute = low.clone(); absolute[11] = 0; refuse(object(absolute));
        byte[] overflow = low.clone(); Arrays.fill(overflow, 8, overflow.length, (byte)0xff); refuse(object(overflow));
        byte[] elf = elf(join(low, middle, high));
        for (int[] change : List.of(new int[]{16, 3}, new int[]{40, 0}, new int[]{58, 0}, new int[]{62, 999},
                new int[]{256, 999}, new int[]{288, -1}, new int[]{352, 8})) {
            byte[] changed = elf.clone(); var buffer = ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN);
            if (change[0] == 352) { buffer.putInt(324, 4).putLong(352, 8).putInt(364, 2); }
            else buffer.putInt(change[0], change[1]);
            refuse(changed);
        }
        for (int length : List.of(0, 3, 31, 63, 127, elf.length - 5)) refuse(Arrays.copyOf(elf, length));
    }

    private static byte[] group(long guid, boolean child) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(guid).array());
        out.write(1); out.write(child ? 1 : 0);
        out.write(1); out.write(128); out.write(0x7e); // Signed address delta -2.
        if (child) {
            out.write(5); // Inline site; preserve this nested group byte-for-byte.
            out.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(17).array());
            out.write(1); out.write(0); out.write(0); out.write(32);
            out.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(42).array());
        }
        return out.toByteArray();
    }

    private static byte[] join(byte[]... parts) {
        var out = new ByteArrayOutputStream(); for (byte[] part : parts) out.writeBytes(part); return out.toByteArray();
    }

    private static byte[] object(byte[] probes) {
        byte[] bytes = new byte[184 + probes.length + 4]; var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(0, 0xfeedfacf).putInt(12, 1).putInt(16, 1).putInt(20, 152);
        data.putInt(32, 0x19).putInt(36, 152).putInt(96, 1);
        System.arraycopy("__probes".getBytes(StandardCharsets.US_ASCII), 0, bytes, 104, 8);
        System.arraycopy("__PSEUDO_PROBE".getBytes(StandardCharsets.US_ASCII), 0, bytes, 120, 14);
        data.putLong(144, probes.length).putInt(152, 184);
        System.arraycopy(probes, 0, bytes, 184, probes.length);
        data.putInt(bytes.length - 4, 0x12345678); return bytes;
    }

    private static byte[] elf(byte[] probes) {
        byte[] bytes = new byte[388 + probes.length]; var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(0, 0x464c457f).put(4, (byte)2).put(5, (byte)1).putShort(16, (short)1).putLong(40, 128);
        data.putShort(58, (short)64).putShort(60, (short)4).putShort(62, (short)1);
        byte[] names = "\0.shstrtab\0.pseudo_probe\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(names, 0, bytes, 64, names.length);
        data.putInt(192, 1).putInt(196, 3).putLong(216, 64).putLong(224, names.length);
        data.putInt(256, 11).putInt(260, 1).putLong(280, 384).putLong(288, probes.length);
        System.arraycopy(probes, 0, bytes, 384, probes.length);
        data.putInt(bytes.length - 4, 0x12345678); return bytes;
    }

    private static void refuse(byte[] bytes) throws Exception {
        try { SharedTraceOrder.canonicalize(bytes); throw new AssertionError("invalid trace metadata accepted"); }
        catch (IOException expected) { check(expected.getMessage().startsWith("cannot canonicalize"), expected.toString()); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
