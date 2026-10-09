// SPDX-License-Identifier: MIT OR Apache-2.0

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Java 21 reference for integration-tests/cases/compiler_sha256.iron: the
 * same inputs through MessageDigest SHA-256 and HexFormat, with text encoded
 * by getBytes(StandardCharsets.UTF_8).
 */
public final class Sha256Reference {
    private static MessageDigest digest;

    private static void line(String name) {
        System.out.println(name + " " + HexFormat.of().formatHex(digest.digest()));
    }

    private static byte[] pattern(int length) {
        byte[] data = new byte[length];
        for (int index = 0; index < length; index++) data[index] = (byte) (index * 31 + 7);
        return data;
    }

    public static void main(String[] args) throws Exception {
        digest = MessageDigest.getInstance("SHA-256");
        line("empty");
        digest.update("abc".getBytes(StandardCharsets.UTF_8));
        line("abc");
        digest.update("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".getBytes(StandardCharsets.UTF_8));
        line("448");
        digest.update(("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqr"
                + "lmnopqrsmnopqrstnopqrstu").getBytes(StandardCharsets.UTF_8));
        line("896");
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
        String[] texts = {"", "ascii", "café", "中文", "😀", "\ud800", "\udc00",
            "a\ud800", "\ud800𐀀", "􏿿", "x\udc00\ud800y", "\u007f\u0080߿ࠀ￿"};
        for (int index = 0; index < texts.length; index++) {
            digest.update(texts[index].getBytes(StandardCharsets.UTF_8));
            line("text " + index);
        }
        byte[] buffer = new byte[65536];
        for (int chunk = 0; chunk < 1024; chunk++) {
            for (int index = 0; index < buffer.length; index++) buffer[index] = (byte) (chunk + index);
            digest.update(buffer, 0, buffer.length);
        }
        line("streamed");
    }
}
