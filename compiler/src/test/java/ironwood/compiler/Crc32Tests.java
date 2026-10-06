// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * M5.1 public {@code ironwood.util.zip.CRC32} (D274): Java 21 differential
 * transcript across class and archive links, ownership pairs including
 * retaining overrides, omitted members, and allocation failure.
 */
final class Crc32Tests {
    private static final String EVIDENCE = "docs/self-hosting/m5/crc32-evidence/";

    private Crc32Tests() { }

    static void javaDifferential() throws Exception {
        String expected = PortFixtures.reference(EVIDENCE + "Crc32Reference.java", List.of(), List.of());
        Path root = Files.createTempDirectory("ironwood-stdlib-crc32-");
        try {
            for (Path executable : PortFixtures.links(root, "stdlib_crc32", List.of())) {
                PortFixtures.execute(List.of(executable.toString()), null, 42, expected);
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    /**
     * Updates borrow their array only for the call, so the caller may free it
     * afterwards, even in a program that also has a retaining subclass; an
     * override that keeps the array makes that free unsafe whether it is
     * reached directly, through whole-array delegation or through a CRC32
     * reference.
     */
    static void ownership() {
        String keeping = """
                class Keeping extends CRC32 {

                    byte[] kept;

                    @Override
                    public void update(byte[] b, int off, int len) {

                        this.kept = b;
                        super.update(b, off, len);
                    }
                }
                """;
        String prefix = "import ironwood.util.zip.CRC32;\n";
        String main = "class Main { public static int main(String[] args) { ";
        String data = "byte[] data = new byte[4]; ";
        for (UnfreedMode mode : UnfreedMode.values()) {
            PortFixtures.require(mode, List.of(), prefix + main + data + "CRC32 crc = new CRC32(); crc.update(data);"
                    + " crc.update(data, 0, 4); crc.update(data[0]); free data; long value = crc.getValue();"
                    + " free crc; return (int) value; }}", null);
            PortFixtures.require(mode, List.of(), prefix + keeping + main + data + "CRC32 crc = new CRC32();"
                    + " crc.update(data); crc.update(data, 0, 4); free data; free crc; Keeping other = new Keeping();"
                    + " free other; return 0; }}", null);
            PortFixtures.require(mode, List.of(), prefix + keeping + main + data + "Keeping crc = new Keeping();"
                    + " crc.update(data, 0, 4); free data; free crc; return 0; }}",
                    "allocation escapes through argument 1 of method 'update'");
            PortFixtures.require(mode, List.of(), prefix + keeping + main + data + "Keeping crc = new Keeping();"
                    + " crc.update(data); free data; free crc; return 0; }}",
                    "allocation escapes through argument 1 of method 'update'");
            PortFixtures.require(mode, List.of(), prefix + keeping + main + data + "CRC32 crc = new Keeping();"
                    + " crc.update(data); free data; free crc; return 0; }}",
                    "allocation escapes through argument 1 of method 'update'");
            PortFixtures.require(mode, List.of(), prefix + main + "CRC32 crc = new CRC32(); free crc; crc.update(1);"
                    + " return 0; }}", "after its allocation was freed");
            PortFixtures.require(mode, List.of(), prefix + main + "CRC32 crc = new CRC32(); free crc; free crc;"
                    + " return 0; }}", "allocation was already freed");
        }
    }

    /** The Checksum interface and the ByteBuffer update are absent; Java-invalid calls stay invalid. */
    static void omissions() {
        List<SourceFile> none = List.of();
        String prefix = "import ironwood.util.zip.CRC32;\nimport ironwood.nio.ByteBuffer;\n";
        String main = "class Main { public static int main(String[] args) { ";
        PortFixtures.require(UnfreedMode.OFF, none, "import ironwood.util.zip.Checksum;\n" + main + "return 0; }}",
                "imported type 'ironwood.util.zip.Checksum' does not exist");
        PortFixtures.require(UnfreedMode.OFF, none, prefix + main
                + "ironwood.util.zip.Checksum crc = new CRC32(); return 0; }}",
                "unknown class type 'ironwood.util.zip.Checksum'");
        PortFixtures.require(UnfreedMode.OFF, none, prefix + main + "CRC32 crc = new CRC32();"
                + " ByteBuffer buffer = ByteBuffer.allocate(4); crc.update(buffer); return 0; }}",
                "no applicable method 'update' for argument types (ironwood.nio.ByteBuffer)");
        PortFixtures.require(UnfreedMode.OFF, none, prefix + main + "CRC32 crc = new CRC32(); crc.update(1L);"
                + " return 0; }}", "no applicable method 'update' for argument types (long)");
        PortFixtures.require(UnfreedMode.OFF, none, prefix + main + "CRC32 crc = new CRC32();"
                + " int value = crc.getValue(); return value; }}", "cannot initialize int variable 'value' with long");
    }

    /** Every allocation failure unwinds; only the process-lived tables may remain. */
    static void failures() throws Exception {
        Path root = Files.createTempDirectory("ironwood-stdlib-crc32-failure-");
        try {
            for (Path executable : PortFixtures.links(root, "stdlib_crc32_failure", List.of())) {
                PortFixtures.sweep(executable, List.of(), 3);
            }
        } finally {
            PortFixtures.delete(root);
        }
    }
}
