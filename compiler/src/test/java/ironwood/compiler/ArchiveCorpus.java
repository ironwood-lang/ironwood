// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.Deflater;

/**
 * The M5.1 archive corpus: byte-exact container and profile variants of the
 * IronClass profile (read by ZipInputStream) and the IronJar profile (read by
 * ZipFile), and the transcript of the Java baseline's verdicts, frozen in
 * docs/self-hosting/m5/archive-evidence/java-verdicts.txt. Container-level
 * messages come from java.util.zip; profile-level messages are the
 * compiler's own. Every variant is written under the corpus root so native
 * readers can be compared with the same files.
 */
final class ArchiveCorpus {
    static final String SOURCE_A = "package p;\n\n// café 中 😀\npublic class A {\n\n"
            + "    public static int main(String[] args) {\n\n        return 0;\n    }\n}\n\ninterface B {\n\n}\n";
    static final String SOURCE_C = "package q;\n\nclass C {\n\n}\n";

    static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    static ZipBytes ironClass(boolean deflated, String manifest, String index, String entryPoint, String sourceName,
                              String source) {
        ZipBytes zip = new ZipBytes();
        add(zip, deflated, "META-INF/IRONWOOD.MF", utf8(manifest));
        add(zip, deflated, "META-INF/types.tsv", utf8(index));
        if (entryPoint != null) add(zip, deflated, "META-INF/entry-point", utf8(entryPoint));
        if (sourceName != null) add(zip, deflated, sourceName, utf8(source));
        return zip;
    }

    static ZipBytes.Entry add(ZipBytes zip, boolean deflated, String name, byte[] data) {
        return deflated ? zip.deflated(name, data) : zip.stored(name, data);
    }

    static ZipBytes classA() {
        return ironClass(true, "Ironwood-Class-Format: 1\n", "p.A\tclass\np.B\tinterface\n", "p.A\n", "source/A.iron",
                SOURCE_A);
    }

    static ZipBytes classC() {
        return ironClass(true, "Ironwood-Class-Format: 1\n", "q.C\tclass\n", null, "source/C.iron", SOURCE_C);
    }

    static ZipBytes ironJar(Map<String, byte[]> entries, boolean deflated) {
        ZipBytes zip = new ZipBytes();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) add(zip, deflated, entry.getKey(), entry.getValue());
        return zip;
    }

    static Map<String, byte[]> jarEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/IRONWOOD.MF", utf8("Ironwood-Jar-Format: 1\n"));
        entries.put("META-INF/LICENSES/LICENSE", utf8("license text\n"));
        entries.put("META-INF/types.tsv", utf8("p.A\tp/A.ironclass\nq.C\tq/C.ironclass\n"));
        entries.put("p/A.ironclass", classA().build());
        entries.put("q/C.ironclass", classC().build());
        return entries;
    }

    record Variant(String name, byte[] bytes) { }

    static final List<Variant> CLASSES = new ArrayList<>();
    static final List<Variant> JARS = new ArrayList<>();

    static void klass(String name, Consumer<ZipBytes> mutation) {
        ZipBytes zip = classA();
        mutation.accept(zip);
        CLASSES.add(new Variant(name, zip.build()));
    }

    static void klass(String name, ZipBytes zip) {
        CLASSES.add(new Variant(name, zip.build()));
    }

    static void jar(String name, Consumer<ZipBytes> mutation) {
        ZipBytes zip = ironJar(jarEntries(), false);
        mutation.accept(zip);
        JARS.add(new Variant(name, zip.build()));
    }

    static void jarEntries(String name, Consumer<Map<String, byte[]>> mutation) {
        Map<String, byte[]> entries = jarEntries();
        mutation.accept(entries);
        JARS.add(new Variant(name, ironJar(entries, false).build()));
    }

    static ZipBytes.Entry named(ZipBytes zip, String name) {
        for (ZipBytes.Entry entry : zip.entries) {
            if (new String(entry.name, StandardCharsets.UTF_8).equals(name)) return entry;
        }
        throw new AssertionError(name);
    }

    static void recompress(ZipBytes.Entry entry, int level, int strategy) {
        entry.payload = ZipBytes.deflate(entry.data, level, strategy);
        entry.csize = entry.payload.length;
    }

    static void noDescriptor(ZipBytes zip) {
        for (ZipBytes.Entry entry : zip.entries) {
            entry.descriptor = false;
            entry.flags &= ~8;
        }
    }

    static void define() {
        // Container variants of the IronClass profile (ZipInputStream model).
        klass("class.java", zip -> { });
        klass("class.stored", ironClass(false, "Ironwood-Class-Format: 1\n", "p.A\tclass\np.B\tinterface\n", "p.A\n",
                "source/A.iron", SOURCE_A));
        klass("class.deflated-sizes", ArchiveCorpus::noDescriptor);
        klass("class.descriptor-unsigned", zip -> zip.entries.forEach(entry -> entry.descriptorSignature = false));
        klass("class.mixed", zip -> {
            ZipBytes.Entry index = named(zip, "META-INF/types.tsv");
            index.payload = index.data;
            index.method = 0;
            index.csize = index.data.length;
            index.descriptor = false;
            index.flags = 0x800;
            index.version = 10;
        });
        for (int level : new int[]{0, 1, 9}) {
            final int chosen = level;
            klass("class.level-" + level, zip -> recompress(named(zip, "source/A.iron"), chosen, Deflater.DEFAULT_STRATEGY));
        }
        klass("class.huffman-only", zip -> recompress(named(zip, "source/A.iron"), 6, Deflater.HUFFMAN_ONLY));
        klass("class.filtered", zip -> recompress(named(zip, "source/A.iron"), 6, Deflater.FILTERED));
        StringBuilder large = new StringBuilder(SOURCE_A);
        for (int line = 0; line < 9000; line++) large.append("// line ").append(line * 7919 % 10007).append(" é\n");
        klass("class.large-source", ironClass(true, "Ironwood-Class-Format: 1\n", "p.A\tclass\np.B\tinterface\n",
                "p.A\n", "source/A.iron", large.toString()));
        klass("class.empty-entries", zip -> {
            zip.stored("META-INF/empty-stored", new byte[0]);
            zip.deflated("META-INF/empty-deflated", new byte[0]);
        });
        klass("class.comments", zip -> {
            zip.entries.getFirst().comment = utf8("entry comment");
            zip.archiveComment = utf8("archive comment");
        });
        klass("class.extra-fields", zip -> zip.entries.forEach(entry -> {
            entry.localExtra = ZipBytes.concat(entry.localExtra, new byte[]{(byte) 0xFE, (byte) 0xCA, 4, 0, 1, 2, 3, 4});
            entry.centralExtra = ZipBytes.concat(new byte[]{0x75, 0x78, 3, 0, 1, 0, 0}, entry.centralExtra);
        }));
        klass("class.attributes", zip -> zip.entries.forEach(entry -> {
            entry.madeBy = 0x031E;
            entry.externalAttributes = 0100644L << 16;
            entry.time = 0x6B5A;
            entry.date = 0x5946;
        }));
        klass("class.no-utf8-flag", zip -> zip.entries.forEach(entry -> entry.flags &= ~0x800));
        klass("class.utf8-name", zip -> zip.stored("META-INF/café.txt", utf8("x")));
        klass("class.utf8-name-no-flag", zip -> zip.stored("META-INF/café.txt", utf8("x")).flags = 0);
        klass("class.directory", zip -> zip.entries.add(0, directory("META-INF/")));
        klass("class.duplicate", zip -> zip.stored("META-INF/types.tsv", utf8("p.A\tclass\n")));
        klass("class.zip64-end", zip -> zip.zip64End = true);
        klass("class.zip64-local", zip -> {
            noDescriptor(zip);
            zip.entries.forEach(entry -> entry.zip64Local = true);
        });
        klass("class.zip64-descriptor", zip -> zip.entries.forEach(entry -> {
            entry.zip64Local = true;
            entry.zip64Descriptor = true;
        }));
        klass("class.stored-descriptor", zip -> {
            ZipBytes.Entry entry = zip.stored("META-INF/extra", utf8("x"));
            entry.flags = 0x808;
            entry.descriptor = true;
        });
        klass("class.method-12", zip -> named(zip, "source/A.iron").method = 12);
        klass("class.method-12-sizes", zip -> {
            noDescriptor(zip);
            named(zip, "source/A.iron").method = 12;
        });
        klass("class.encrypted", zip -> named(zip, "source/A.iron").flags |= 1);
        klass("class.crc-mismatch", zip -> named(zip, "source/A.iron").crc ^= 1);
        klass("class.size-mismatch", zip -> named(zip, "source/A.iron").usize += 1);
        klass("class.local-crc-mismatch", zip -> {
            noDescriptor(zip);
            named(zip, "source/A.iron").localCrc = named(zip, "source/A.iron").crc ^ 1;
        });
        klass("class.no-central", zip -> zip.omitCentral = true);
        klass("class.prefix", zip -> zip.prefix = new byte[16]);
        klass("class.trailer", zip -> zip.trailer = utf8("trailing garbage"));
        klass("class.invalid-block-type", zip -> {
            ZipBytes.Entry entry = named(zip, "source/A.iron");
            entry.payload = entry.payload.clone();
            entry.payload[0] = (byte) (entry.payload[0] | 6);
        });
        klass("class.deflate-trailing", zip -> {
            noDescriptor(zip);
            ZipBytes.Entry entry = named(zip, "source/A.iron");
            entry.payload = ZipBytes.concat(entry.payload, new byte[]{1, 2, 3});
            entry.csize = entry.payload.length;
        });
        klass("class.malformed-name", zip -> zip.stored("META-INF/x", utf8("x")).name = new byte[]{'M', (byte) 0xFF});
        ZipBytes truncated = classA();
        byte[] whole = truncated.build();
        CLASSES.add(new Variant("class.truncated", java.util.Arrays.copyOf(whole, whole.length / 2)));
        CLASSES.add(new Variant("class.empty-archive", new ZipBytes().build()));
        CLASSES.add(new Variant("class.empty-file", new byte[0]));

        // Profile variants of the IronClass profile.
        String index = "p.A\tclass\np.B\tinterface\n";
        String format = "Ironwood-Class-Format: 1\n";
        klass("class.manifest-missing", zip -> zip.entries.removeFirst());
        klass("class.manifest-format-2", ironClass(true, "Ironwood-Class-Format: 2\n", index, "p.A\n", "source/A.iron", SOURCE_A));
        klass("class.manifest-crlf", ironClass(true, "Ironwood-Class-Format: 1\r\n", index, "p.A\n", "source/A.iron", SOURCE_A));
        klass("class.manifest-malformed", zip -> {
            ZipBytes.Entry entry = named(zip, "META-INF/IRONWOOD.MF");
            byte[] data = ZipBytes.concat(utf8("Ironwood-Class-Format: 1"), new byte[]{(byte) 0xC3, '\n'});
            replaceData(entry, data);
        });
        klass("class.index-missing", zip -> zip.entries.remove(1));
        klass("class.index-blank-lines", ironClass(true, format, "\n  \np.A\tclass\n\t \n", "p.A\n", "source/A.iron", SOURCE_A));
        klass("class.index-crlf", ironClass(true, format, "p.A\tclass\r\np.B\tinterface\r\n", "p.A\n", "source/A.iron", SOURCE_A));
        klass("class.index-cr", ironClass(true, format, "p.A\tclass\rp.B\tinterface", "p.A\n", "source/A.iron", SOURCE_A));
        klass("class.index-three-fields", ironClass(true, format, "p.A\tclass\tx\n", "p.A\n", "source/A.iron", SOURCE_A));
        klass("class.index-blank-field", ironClass(true, format, "p.A\t \n", "p.A\n", "source/A.iron", SOURCE_A));
        klass("class.index-kind", ironClass(true, format, "p.A\tenum\n", "p.A\n", "source/A.iron", SOURCE_A));
        klass("class.index-duplicate", ironClass(true, format, "p.A\tclass\np.A\tinterface\n", "p.A\n", "source/A.iron", SOURCE_A));
        klass("class.index-empty", ironClass(true, format, "", null, "source/A.iron", SOURCE_A));
        klass("class.index-unicode", ironClass(true, format, "p.Été\tclass\n", null, "source/A.iron", SOURCE_A));
        klass("class.entry-missing", ironClass(true, format, index, null, "source/A.iron", SOURCE_A));
        klass("class.entry-whitespace", ironClass(true, format, index, " \t p.A \n\n", "source/A.iron", SOURCE_A));
        klass("class.entry-blank", ironClass(true, format, index, " \n", "source/A.iron", SOURCE_A));
        klass("class.entry-undeclared", ironClass(true, format, index, "p.Z\n", "source/A.iron", SOURCE_A));
        klass("class.entry-interface", ironClass(true, format, index, "p.B\n", "source/A.iron", SOURCE_A));
        klass("class.source-missing", ironClass(true, format, index, "p.A\n", null, null));
        klass("class.source-two", zip -> zip.deflated("source/B.iron", utf8("package p;\n")));
        klass("class.source-extension", ironClass(true, format, index, "p.A\n", "source/A.txt", SOURCE_A));
        klass("class.source-outside", ironClass(true, format, index, "p.A\n", "A.iron", SOURCE_A));
        klass("class.source-nested", ironClass(true, format, index, "p.A\n", "source/p/A.iron", SOURCE_A));
        klass("class.source-directory-name", zip -> zip.entries.add(directory("source/x.iron/")));
        klass("class.source-malformed", zip -> replaceData(named(zip, "source/A.iron"),
                ZipBytes.concat(utf8("package p; // "), new byte[]{(byte) 0xE4, (byte) 0xB8, '\n', (byte) 0xFF, '\n',
                        (byte) 0xED, (byte) 0xA0, (byte) 0x80, '\n'})));

        // Container variants of the IronJar profile (ZipFile model).
        jar("jar.java", zip -> { });
        JARS.add(new Variant("jar.deflated", ironJar(jarEntries(), true).build()));
        jar("jar.deflated-sizes", zip -> {
            for (ZipBytes.Entry entry : zip.entries) {
                recompress(entry, Deflater.DEFAULT_COMPRESSION, Deflater.DEFAULT_STRATEGY);
                entry.method = 8;
                entry.version = 20;
                entry.madeBy = 20;
            }
        });
        jar("jar.descriptor-unsigned", zip -> zip.entries.forEach(entry -> {
            recompress(entry, Deflater.DEFAULT_COMPRESSION, Deflater.DEFAULT_STRATEGY);
            entry.method = 8;
            entry.flags = 0x808;
            entry.descriptor = true;
            entry.descriptorSignature = false;
        }));
        jar("jar.empty-license", zip -> {
            zip.entries.add(2, zip.entries.removeLast());
            zip.entries.removeLast();
        });
        jarEntries("jar.empty-licenses", entries -> {
            Map<String, byte[]> copy = new LinkedHashMap<>(entries);
            entries.clear();
            copy.forEach((key, value) -> {
                entries.put(key, value);
                if (key.equals("META-INF/IRONWOOD.MF")) {
                    entries.put("META-INF/LICENSES/EMPTY", new byte[0]);
                }
            });
        });
        jar("jar.empty-deflated-license", zip -> {
            ZipBytes.Entry entry = named(zip, "META-INF/LICENSES/LICENSE");
            replaceData(entry, new byte[0]);
            recompress(entry, Deflater.DEFAULT_COMPRESSION, Deflater.DEFAULT_STRATEGY);
            entry.method = 8;
        });
        jar("jar.comments", zip -> {
            zip.entries.getFirst().comment = utf8("entry comment");
            zip.archiveComment = utf8("archive comment");
        });
        jar("jar.extra-fields", zip -> zip.entries.forEach(entry -> {
            entry.localExtra = ZipBytes.concat(entry.localExtra, new byte[]{(byte) 0xFE, (byte) 0xCA, 4, 0, 1, 2, 3, 4});
            entry.centralExtra = ZipBytes.concat(new byte[]{0x75, 0x78, 3, 0, 1, 0, 0}, entry.centralExtra);
        }));
        jar("jar.attributes", zip -> zip.entries.forEach(entry -> {
            entry.madeBy = 0x031E;
            entry.externalAttributes = 0100644L << 16;
            entry.time = 0x6B5A;
            entry.date = 0x5946;
        }));
        jar("jar.no-utf8-flag", zip -> zip.entries.forEach(entry -> entry.flags &= ~0x800));
        jarEntries("jar.utf8-name", entries -> insertAfter(entries, "META-INF/LICENSES/LICENSE",
                "META-INF/LICENSES/café.txt", utf8("x")));
        jar("jar.utf8-name-no-flag", zip -> {
            ZipBytes.Entry entry = zip.stored("META-INF/LICENSES/café.txt", utf8("x"));
            entry.flags = 0;
            zip.entries.add(2, zip.entries.removeLast());
        });
        jar("jar.directory", zip -> zip.entries.add(1, directory("META-INF/LICENSES/")));
        jar("jar.duplicate", zip -> {
            zip.stored("q/C.ironclass", classC().build());
        });
        jar("jar.zip64-end", zip -> zip.zip64End = true);
        jar("jar.zip64-local", zip -> zip.entries.forEach(entry -> entry.zip64Local = true));
        jar("jar.zip64-central", zip -> zip.entries.forEach(entry -> entry.zip64Central = true));
        jar("jar.stored-descriptor", zip -> zip.entries.forEach(entry -> {
            entry.flags = 0x808;
            entry.descriptor = true;
        }));
        jar("jar.method-12-payload", zip -> named(zip, "q/C.ironclass").method = 12);
        jar("jar.method-12-manifest", zip -> named(zip, "META-INF/IRONWOOD.MF").method = 12);
        jar("jar.encrypted-payload", zip -> named(zip, "q/C.ironclass").flags |= 1);
        jar("jar.crc-mismatch-payload", zip -> named(zip, "q/C.ironclass").crc ^= 1);
        jar("jar.crc-mismatch-index", zip -> named(zip, "META-INF/types.tsv").crc ^= 1);
        jar("jar.central-size-short", zip -> {
            ZipBytes.Entry entry = named(zip, "META-INF/types.tsv");
            entry.csize -= 1;
            entry.usize -= 1;
            entry.localCsize = entry.data.length;
            entry.localUsize = entry.data.length;
        });
        jar("jar.central-size-long", zip -> {
            ZipBytes.Entry entry = named(zip, "META-INF/types.tsv");
            entry.csize += 1;
            entry.usize += 1;
            entry.localCsize = entry.data.length;
            entry.localUsize = entry.data.length;
        });
        jar("jar.local-name-mismatch", zip -> named(zip, "q/C.ironclass").localName = utf8("q/D.ironclass"));
        jar("jar.local-method-mismatch", zip -> named(zip, "META-INF/types.tsv").localMethod = 8);
        jar("jar.local-offset-wrong", zip -> named(zip, "q/C.ironclass").centralOffsetDelta = 1);
        jar("jar.count-short", zip -> zip.countOverride = zip.entries.size() - 1);
        jar("jar.count-long", zip -> zip.countOverride = zip.entries.size() + 1);
        jar("jar.central-offset-wrong", zip -> zip.centralOffsetOverride = 1);
        jar("jar.no-central", zip -> zip.omitCentral = true);
        jar("jar.prefix", zip -> zip.prefix = new byte[16]);
        jar("jar.trailer", zip -> zip.trailer = utf8("trailing garbage"));
        jar("jar.invalid-deflate-payload", zip -> {
            ZipBytes.Entry entry = named(zip, "q/C.ironclass");
            recompress(entry, Deflater.DEFAULT_COMPRESSION, Deflater.DEFAULT_STRATEGY);
            entry.method = 8;
            entry.payload[0] = (byte) (entry.payload[0] | 6);
        });
        jar("jar.malformed-name", zip -> {
            ZipBytes.Entry entry = zip.stored("META-INF/LICENSES/x", utf8("x"));
            entry.name = new byte[]{'M', 'E', 'T', 'A', '-', 'I', 'N', 'F', '/', 'L', 'I', 'C', 'E', 'N', 'S', 'E', 'S',
                '/', 'z', (byte) 0xFF};
        });
        byte[] wholeJar = ironJar(jarEntries(), false).build();
        JARS.add(new Variant("jar.truncated", java.util.Arrays.copyOf(wholeJar, wholeJar.length - 30)));
        JARS.add(new Variant("jar.empty-archive", new ZipBytes().build()));
        JARS.add(new Variant("jar.empty-file", new byte[0]));

        // Profile variants of the IronJar profile.
        jarEntries("jar.manifest-wrong", entries -> entries.put("META-INF/IRONWOOD.MF", utf8("Ironwood-Jar-Format: 2\n")));
        jarEntries("jar.manifest-missing", entries -> entries.remove("META-INF/IRONWOOD.MF"));
        jarEntries("jar.manifest-malformed", entries -> entries.put("META-INF/IRONWOOD.MF",
                ZipBytes.concat(utf8("Ironwood-Jar-Format: 1"), new byte[]{(byte) 0xC3, '\n'})));
        jarEntries("jar.index-missing", entries -> entries.remove("META-INF/types.tsv"));
        jarEntries("jar.index-crlf", entries -> entries.put("META-INF/types.tsv", utf8("p.A\tp/A.ironclass\r\nq.C\tq/C.ironclass\r\n")));
        jarEntries("jar.index-no-final-lf", entries -> entries.put("META-INF/types.tsv", utf8("p.A\tp/A.ironclass\nq.C\tq/C.ironclass")));
        jarEntries("jar.index-empty", entries -> entries.put("META-INF/types.tsv", utf8("\n")));
        jarEntries("jar.index-zero", entries -> entries.put("META-INF/types.tsv", new byte[0]));
        jarEntries("jar.index-blank-line", entries -> entries.put("META-INF/types.tsv", utf8("p.A\tp/A.ironclass\n\nq.C\tq/C.ironclass\n")));
        jarEntries("jar.index-three-fields", entries -> entries.put("META-INF/types.tsv", utf8("p.A\tp/A.ironclass\tx\nq.C\tq/C.ironclass\n")));
        jarEntries("jar.index-duplicate", entries -> entries.put("META-INF/types.tsv", utf8("p.A\tp/A.ironclass\np.A\tp/A.ironclass\nq.C\tq/C.ironclass\n")));
        jarEntries("jar.index-unsorted", entries -> entries.put("META-INF/types.tsv", utf8("q.C\tq/C.ironclass\np.A\tp/A.ironclass\n")));
        jarEntries("jar.index-canonical", entries -> entries.put("META-INF/types.tsv", utf8("p.A\tp/A.ironclass\nq.1C\tq/1C.ironclass\n")));
        jarEntries("jar.index-canonical-unicode", entries -> entries.put("META-INF/types.tsv", utf8("p.A\tp/A.ironclass\nq.É\tq/É.ironclass\n")));
        jarEntries("jar.index-empty-component", entries -> entries.put("META-INF/types.tsv", utf8("p..A\tp//A.ironclass\nq.C\tq/C.ironclass\n")));
        jarEntries("jar.index-path-mismatch", entries -> entries.put("META-INF/types.tsv", utf8("p.A\tq/C.ironclass\nq.C\tq/C.ironclass\n")));
        jarEntries("jar.index-malformed", entries -> entries.put("META-INF/types.tsv",
                ZipBytes.concat(utf8("p.A\tp/A.ironclass\nq.C\tq/C.ironclass\n"), new byte[]{(byte) 0x80})));
        for (String unsafe : new String[]{"../evil", "/evil", "a\\b", "a:b", "a//b", "./a", "a/../b", "a/"}) {
            jarEntries("jar.unsafe " + unsafe, entries -> insertSorted(entries, unsafe, utf8("x")));
        }
        jarEntries("jar.unsafe-nul", entries -> insertSorted(entries, "a\0b", utf8("x")));
        jarEntries("jar.unsorted", entries -> {
            byte[] manifest = entries.remove("META-INF/IRONWOOD.MF");
            entries.put("META-INF/IRONWOOD.MF", manifest);
        });
        jarEntries("jar.nested", entries -> entries.put("z.ironjar", utf8("x")));
        jarEntries("jar.unexpected", entries -> entries.put("z.txt", utf8("x")));
        jarEntries("jar.unindexed", entries -> entries.put("z/Z.ironclass", classC().build()));
        jarEntries("jar.unindexed-license", entries -> insertAfter(entries, "META-INF/LICENSES/LICENSE",
                "META-INF/LICENSES/x.ironclass", utf8("x")));
        jarEntries("jar.indexed-missing", entries -> entries.remove("q/C.ironclass"));
        jarEntries("jar.payload-malformed", entries -> entries.put("q/C.ironclass", new byte[]{1, 2, 3}));
        jarEntries("jar.payload-wrong-type", entries -> entries.put("q/C.ironclass", classA().build()));
        jarEntries("jar.payload-stored", entries -> entries.put("q/C.ironclass", ironClass(false, "Ironwood-Class-Format: 1\n",
                "q.C\tclass\n", null, "source/C.iron", SOURCE_C).build()));
        jarEntries("jar.index-dollar", entries -> {
            entries.put("META-INF/types.tsv", utf8("p.A\tp/A.ironclass\np.A$I\tp/A$I.ironclass\nq.C\tq/C.ironclass\n"));
            insertSorted(entries, "p/A$I.ironclass", ironClass(true, "Ironwood-Class-Format: 1\n", "p.A$I\tclass\n", null,
                    "source/A.iron", SOURCE_A).build());
        });
        JARS.add(new Variant("jar.zip64-count", zip64Count()));
        jarEntries("jar.license-ironjar", entries -> insertAfter(entries, "META-INF/LICENSES/LICENSE",
                "META-INF/LICENSES/x.ironjar", utf8("x")));
    }

    /** 65,540 license entries written by Java's ZipOutputStream, which needs ZIP64 end records for the count. */
    static byte[] zip64Count() {
        java.util.TreeMap<String, byte[]> entries = new java.util.TreeMap<>(jarEntries());
        for (int index = 0; index < 65540; index++) {
            entries.put(String.format("META-INF/LICENSES/n%05d", index), utf8(Integer.toString(index)));
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(item.getKey());
                entry.setMethod(java.util.zip.ZipEntry.STORED);
                entry.setSize(item.getValue().length);
                entry.setCompressedSize(item.getValue().length);
                entry.setCrc(ZipBytes.crc(item.getValue()));
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(item.getValue());
                zip.closeEntry();
            }
        } catch (java.io.IOException impossible) {
            throw new java.io.UncheckedIOException(impossible);
        }
        return bytes.toByteArray();
    }

    static String visible(String text) {
        StringBuilder result = new StringBuilder();
        for (char unit : text.toCharArray()) {
            if (unit < 0x20 && unit != '\t' || unit == 0x7F) result.append(String.format("\\x%02x", (int) unit));
            else result.append(unit);
        }
        return result.toString();
    }

    static void replaceData(ZipBytes.Entry entry, byte[] data) {
        entry.data = data;
        entry.crc = ZipBytes.crc(data);
        entry.usize = data.length;
        if (entry.method == 8) {
            recompress(entry, Deflater.DEFAULT_COMPRESSION, Deflater.DEFAULT_STRATEGY);
        } else {
            entry.payload = data;
            entry.csize = data.length;
        }
    }

    static ZipBytes.Entry directory(String name) {
        ZipBytes holder = new ZipBytes();
        ZipBytes.Entry entry = holder.stored(name, new byte[0]);
        return entry;
    }

    static void insertAfter(Map<String, byte[]> entries, String after, String name, byte[] data) {
        Map<String, byte[]> copy = new LinkedHashMap<>(entries);
        entries.clear();
        copy.forEach((key, value) -> {
            entries.put(key, value);
            if (key.equals(after)) entries.put(name, data);
        });
    }

    static void insertSorted(Map<String, byte[]> entries, String name, byte[] data) {
        java.util.TreeMap<String, byte[]> sorted = new java.util.TreeMap<>(entries);
        sorted.put(name, data);
        entries.clear();
        entries.putAll(sorted);
    }

    static String digest(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(utf8(text))).substring(0, 16);
    }

    /** Writes every variant under root and returns the Java baseline's verdict transcript. */
    static String javaVerdicts(Path root) throws Exception {
        CLASSES.clear();
        JARS.clear();
        define();
        StringBuilder out = new StringBuilder();
        Files.createDirectories(root.resolve("class"));
        Files.createDirectories(root.resolve("jar"));
        for (Variant variant : CLASSES) {
            Path file = root.resolve("class").resolve(variant.name().substring(6) + ".ironclass");
            Files.write(file, variant.bytes());
            String display = "corpus/" + variant.name().substring(6) + ".ironclass";
            out.append(variant.name()).append(' ');
            try {
                IronClass ironClass = IronClass.read(variant.bytes(), display);
                // Map.copyOf iteration order varies between JVM runs; the declared types are a set.
                List<String> types = ironClass.declaredTypes().stream().sorted().toList();
                out.append("ok types=").append(String.join(",", types))
                        .append(" entry=").append(ironClass.entryPoint().orElse("-"));
                String first = types.getFirst();
                SourceFile source = ironClass.source(first).orElseThrow();
                out.append(" path=").append(source.path()).append(" source=").append(digest(source.content()));
            } catch (Exception exception) {
                out.append("error ").append(exception.getClass().getName()).append(": ")
                        .append(visible(String.valueOf(exception.getMessage())));
            }
            out.append('\n');
        }
        for (Variant variant : JARS) {
            Path file = root.resolve("jar").resolve(variant.name().substring(4).replace(' ', '_').replace('/', '%')
                    .replace('\\', '%').replace(':', '%').replace('\0', '%') + ".ironjar");
            Files.write(file, variant.bytes());
            String rootText = root.toAbsolutePath().normalize().toString();
            out.append(variant.name()).append(' ');
            try {
                IronJar archive = IronJar.read(file);
                List<String> names = archive.entries();
                if (names.size() > 20) {
                    out.append("ok entries=").append(names.size()).append(" names=")
                            .append(digest(String.join("\n", names)));
                } else {
                    out.append("ok entries=").append(String.join(",", names));
                }
                for (String type : archive.declaredTypes().stream().sorted().toList()) {
                    out.append("\n  source ").append(type).append(' ');
                    try {
                        SourceFile source = archive.source(type).orElseThrow();
                        out.append("ok path=").append(source.path().toString().replace(rootText, "<root>"))
                                .append(" source=").append(digest(source.content()));
                    } catch (Exception exception) {
                        out.append("error ").append(exception.getClass().getName()).append(": ")
                                .append(String.valueOf(exception.getMessage()).replace(rootText, "<root>"));
                    }
                }
            } catch (Exception exception) {
                out.append("error ").append(exception.getClass().getName()).append(": ")
                        .append(visible(String.valueOf(exception.getMessage()).replace(rootText, "<root>")));
            }
            out.append('\n');
        }
        return out.toString();
    }

}
