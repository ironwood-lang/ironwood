// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import java.io.IOException;
import java.util.List;

final class BridgeLinuxPayloadTests {
    static final String NAME = "Java Bridge Linux payload audit rejects unpaired dependencies and raised libc requirements";
    private BridgeLinuxPayloadTests() {}

    static void metadata() throws Exception {
        String valid = """
                0x0000000000000001 (NEEDED) Shared library: [libgcc_s.so.1]
                0x0000000000000001 (NEEDED) Shared library: [libc.so.6]
                0x000000000000001d (RUNPATH) Library runpath: [$ORIGIN/private/lib]
                0x000000000000001e (FLAGS) BIND_NOW
                Version needs section '.gnu.version_r' contains 1 entries:
                Name: GLIBC_2.17
                Name: GLIBC_2.2.5
                """;
        for (String target : List.of("linux-arm64", "linux-x86_64")) {
            BridgeLinuxPayload.auditDynamic(valid, target, "$ORIGIN/private/lib", true);
            BridgeLinuxPayload.auditDynamic(valid.replace("BIND_NOW", "NOW"), target, "$ORIGIN/private/lib", true);
            for (String invalid : List.of(valid.replace("BIND_NOW", ""), valid.replace("$ORIGIN/private/lib", "/producer/lib"),
                    valid.replace("$ORIGIN/private/lib", "$ORIGIN/private/lib:/system/lib"),
                    valid.replace("libgcc_s.so.1", "libunpackaged.so.1"), valid.replace("GLIBC_2.17", "GLIBC_2.18"),
                    valid.replace("GLIBC_2.17", "GLIBC_2.17.1"), valid.replace("GLIBC_2.17", "GLIBC_PRIVATE"))) {
                try {
                    BridgeLinuxPayload.auditDynamic(invalid, target, "$ORIGIN/private/lib", true);
                    throw new AssertionError("unsafe Linux payload metadata admitted");
                } catch (IOException expected) {
                    if (!expected.getMessage().contains("Linux bridge")) throw expected;
                }
            }
        }
    }
}
