// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Deployment facts from the final thin ARM64 Mach-O image, never from the producer host version. */
public record BridgeMacPayload(String minimumOs, String sdk, List<String> dependencies) {
    public BridgeMacPayload { dependencies = List.copyOf(dependencies); }

    public static BridgeMacPayload inspect(byte[] image) throws IOException {
        var data = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        if (image.length < 32 || data.getInt(0) != 0xfeedfacf || data.getInt(4) != 0x0100000c
                || data.getInt(8) != 0 || data.getInt(12) != 6) {
            throw invalid("expected a thin little-endian baseline ARM64 dylib");
        }
        int commands = data.getInt(16), bytes = data.getInt(20);
        if (commands < 1 || bytes < 8 || bytes > image.length - 32 || commands > bytes / 8) {
            throw invalid("invalid load-command extent");
        }
        int offset = 32, end = 32 + bytes;
        String minimum = null, sdk = null;
        var dependencies = new ArrayList<String>();
        for (int index = 0; index < commands; index++) {
            if (end - offset < 8) throw invalid("truncated load command");
            int command = data.getInt(offset) & 0x7fffffff, size = data.getInt(offset + 4);
            if (size < 8 || (size & 7) != 0 || size > end - offset) throw invalid("invalid load-command size");
            if (command == 0x32 || command == 0x24) {
                if (minimum != null) throw invalid("duplicate deployment target");
                if (command == 0x32) {
                    if (size < 24 || data.getInt(offset + 8) != 1) throw invalid("expected macOS build platform");
                    long tools = Integer.toUnsignedLong(data.getInt(offset + 20));
                    if (24L + tools * 8 != size) throw invalid("invalid build-tool extent");
                    minimum = version(data.getInt(offset + 12)); sdk = version(data.getInt(offset + 16));
                } else {
                    if (size != 16) throw invalid("invalid legacy deployment command");
                    minimum = version(data.getInt(offset + 8)); sdk = version(data.getInt(offset + 12));
                }
            } else if (command == 0xc || command == 0x18 || command == 0x1f || command == 0x20 || command == 0x23) {
                if (size < 24) throw invalid("truncated dependency command");
                int name = data.getInt(offset + 8);
                if (name < 24 || name >= size) throw invalid("invalid dependency name offset");
                int limit = name;
                while (limit < size && image[offset + limit] != 0) limit++;
                if (limit == size || limit == name) throw invalid("unterminated or empty dependency name");
                String dependency = new String(image, offset + name, limit - name, StandardCharsets.UTF_8);
                if (!java.util.Arrays.equals(dependency.getBytes(StandardCharsets.UTF_8),
                        java.util.Arrays.copyOfRange(image, offset + name, offset + limit))) throw invalid("invalid UTF-8 dependency name");
                dependencies.add(dependency);
            }
            offset += size;
        }
        if (offset != end || minimum == null) throw invalid("missing deployment target or unused load-command bytes");
        return new BridgeMacPayload(minimum, sdk, dependencies.stream().distinct().sorted().toList());
    }

    private static String version(int value) throws IOException {
        if (value >>> 16 == 0) throw invalid("missing deployment/SDK version");
        return (value >>> 16) + "." + (value >> 8 & 255) + ((value & 255) == 0 ? "" : "." + (value & 255));
    }

    private static IOException invalid(String reason) { return new IOException("invalid Java Bridge macOS payload: " + reason); }
}
