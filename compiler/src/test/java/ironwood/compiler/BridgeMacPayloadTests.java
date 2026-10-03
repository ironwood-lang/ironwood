// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeMacPayload;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

final class BridgeMacPayloadTests {
    static final String NAME = "Java Bridge macOS payload metadata reflects actual deployment requirements";
    private BridgeMacPayloadTests() {}

    static void metadata() throws Exception {
        byte[] image = image();
        var value = BridgeMacPayload.inspect(image);
        check(value.minimumOs().equals("26.0") && value.sdk().equals("26.5.2")
                && value.dependencies().equals(List.of("/usr/lib/libSystem.B.dylib")), "wrong image metadata");
        for (int[] change : List.of(new int[]{0, 0xcafebabe}, new int[]{4, 0x01000007}, new int[]{8, 2}, new int[]{12, 2},
                new int[]{16, -1}, new int[]{16, 0}, new int[]{16, 100}, new int[]{20, Integer.MAX_VALUE},
                new int[]{36, 0}, new int[]{36, 25}, new int[]{40, 2}, new int[]{44, 0},
                new int[]{52, 1}, new int[]{60, 1000}, new int[]{64, 4}, new int[]{64, 56})) {
            byte[] invalid = image.clone(); ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(change[0], change[1]);
            refuse(invalid);
        }
        for (int length : new int[]{0, 12, 31, 32, 55, image.length - 1}) refuse(Arrays.copyOf(image, length));
        byte[] noTerminator = image.clone(); Arrays.fill(noTerminator, 80, noTerminator.length, (byte)'x'); refuse(noTerminator);
        byte[] invalidUtf8 = image.clone(); invalidUtf8[80] = (byte)0xff; refuse(invalidUtf8);
        byte[] missing = image.clone(); ByteBuffer.wrap(missing).order(ByteOrder.LITTLE_ENDIAN).putInt(32, 0); refuse(missing);
        byte[] duplicate = image.clone(); var duplicateData = ByteBuffer.wrap(duplicate).order(ByteOrder.LITTLE_ENDIAN);
        duplicateData.putInt(56, 0x24).putInt(60, 16); refuse(duplicate);
        byte[] legacy = new byte[48]; var old = ByteBuffer.wrap(legacy).order(ByteOrder.LITTLE_ENDIAN);
        old.putInt(0xfeedfacf).putInt(0x0100000c).putInt(0).putInt(6).putInt(1).putInt(16).putInt(0).putInt(0);
        old.putInt(0x24).putInt(16).putInt(0x000b0203).putInt(0x000d0000);
        check(BridgeMacPayload.inspect(legacy).minimumOs().equals("11.2.3"), "legacy deployment version");
    }

    private static byte[] image() {
        byte[] image = new byte[112]; var data = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(0xfeedfacf).putInt(0x0100000c).putInt(0).putInt(6).putInt(2).putInt(80).putInt(0).putInt(0);
        data.putInt(0x32).putInt(24).putInt(1).putInt(0x001a0000).putInt(0x001a0502).putInt(0);
        data.putInt(0xc).putInt(56).putInt(24).putInt(0).putInt(0).putInt(0);
        data.put("/usr/lib/libSystem.B.dylib".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return image;
    }

    private static void refuse(byte[] image) throws Exception {
        try { BridgeMacPayload.inspect(image); throw new AssertionError("invalid payload accepted"); }
        catch (IOException expected) { check(expected.getMessage().startsWith("invalid Java Bridge macOS payload:"), expected.toString()); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
