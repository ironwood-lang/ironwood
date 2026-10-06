// SPDX-License-Identifier: MIT OR Apache-2.0

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Java reference for integration-tests/cases/compiler_md5.iron. Run with the
 * bootstrap classes on the class path: digests come from MessageDigest, and
 * each GUID from the baseline's own OptimizedTraceMetadata.linkageGuid and
 * LlvmEmitter's TracePlan.linkageGuid, called by reflection, which must agree.
 */
public final class Md5Reference {
    private static long seed = 271828L;
    private static final StringBuilder OUT = new StringBuilder();
    private static MessageDigest digest;
    private static Method trace;
    private static Method emitter;

    static final String[] SPELLED = {"", "ascii", "caf\u00e9", "\u4e2d\u6587", "\ud83d\ude00", "\ud800", "\udc00",
        "a\ud800", "\ud800\ud800\udc00", "\udbff\udfff", "x\udc00\ud800y", "\u007f\u0080\u07ff\u0800\uffff"};
    static final char[] ALPHABET = {'a', 'b', 'Z', '0', '9', '.', '$', '_', '<', '>', '-', '\u00e9', '\u4e2d',
        '\ud83d', '\ude00', '\ud800', '\udc00', ' ', '"', '\\'};

    private Md5Reference() { }

    static int next() {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return (int) (seed >>> 33);
    }

    static void line(String name) {
        OUT.append(name).append(' ').append(HexFormat.of().formatHex(digest.digest())).append('\n');
    }

    static void text(String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] pattern(int length) {
        byte[] data = new byte[length];
        for (int index = 0; index < length; index++) data[index] = (byte) (index * 31 + 7);
        return data;
    }

    static void guid(int index, String name) throws Exception {
        long fromTrace = (long) trace.invoke(null, name);
        long fromEmitter = (long) emitter.invoke(null, name);
        if (fromTrace != fromEmitter) throw new AssertionError("GUID implementations disagree for " + index);
        OUT.append("guid ").append(index).append(' ').append(fromTrace).append('\n');
    }

    public static void main(String[] args) throws Exception {
        digest = MessageDigest.getInstance("MD5");
        trace = Class.forName("ironwood.compiler.backend.OptimizedTraceMetadata")
                .getDeclaredMethod("linkageGuid", String.class);
        trace.setAccessible(true);
        emitter = Class.forName("ironwood.compiler.backend.LlvmEmitter$TracePlan")
                .getDeclaredMethod("linkageGuid", String.class);
        emitter.setAccessible(true);
        line("empty");
        text("a");
        line("a");
        text("abc");
        line("abc");
        text("message digest");
        line("message");
        text("abcdefghijklmnopqrstuvwxyz");
        line("alphabet");
        text("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789");
        line("alphanumeric");
        for (int index = 0; index < 8; index++) text("1234567890");
        line("digits");
        for (int index = 0; index < 1000000; index++) digest.update((byte) 'a');
        line("million");
        byte[] data = pattern(300);
        for (int length = 0; length <= 300; length++) {
            digest.update(data, 0, length);
            line("length " + length);
        }
        for (int value = 0; value < 256; value++) {
            digest.update((byte) value);
            line("byte " + value);
        }
        for (int index = 0; index < SPELLED.length; index++) {
            text(SPELLED[index]);
            line("text " + index);
        }
        byte[] buffer = new byte[65536];
        for (int chunk = 0; chunk < 1024; chunk++) {
            for (int index = 0; index < buffer.length; index++) buffer[index] = (byte) (chunk + index);
            digest.update(buffer, 0, buffer.length);
        }
        line("streamed");
        String[] fixed = {"", "main", "_main", "ironwood.lang.Object.<init>", "Main.main([Lironwood/lang/String;)I",
            "ironwood_trace_register_current"};
        for (int index = 0; index < fixed.length; index++) guid(index, fixed[index]);
        for (int index = 0; index < SPELLED.length; index++) guid(6 + index, SPELLED[index]);
        for (int index = 0; index < 2000; index++) {
            StringBuilder name = new StringBuilder();
            int length = next() % 40;
            for (int unit = 0; unit < length; unit++) name.append(ALPHABET[next() % ALPHABET.length]);
            guid(6 + SPELLED.length + index, name.toString());
        }
        System.out.print(OUT);
    }
}
