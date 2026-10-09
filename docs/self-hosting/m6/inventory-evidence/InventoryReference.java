// SPDX-License-Identifier: MIT OR Apache-2.0

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.TreeMap;
import java.util.jar.Manifest;
import java.util.logging.LogManager;

/**
 * Java 21 reference for integration-tests/cases/compiler_bridge_inventories.iron.
 * Run with the compiler's classes on the class path. It writes the corpus to
 * the file named by its argument and prints, per item: the baseline's own
 * BridgeGeneration identity digest and contentIdentity (called by reflection)
 * for each map, bytesDigest for each byte vector, Properties.load's entries or
 * failure with the pairing readers' canonical check against
 * BridgePackageManifest.serialize for each properties text (the pinned TLS
 * and Bridge support inventories included), the serialization
 * of each map, Manifest.write's bytes for each attribute list, and
 * new Manifest(InputStream)'s main attributes or failure for each manifest
 * text. Strings are UTF-16 hex, bytes two hex digits each, "-" when empty.
 */
public final class InventoryReference {
    private static Method digest;
    private static Method bytesDigest;
    private static Method contentIdentity;
    private static Method serialize;

    private InventoryReference() { }

    static String hex(String text) {
        StringBuilder out = new StringBuilder();
        for (char unit : text.toCharArray()) out.append(String.format("%04x", (int) unit));
        return out.length() == 0 ? "-" : out.toString();
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format("%02x", value & 0xFF));
        return out.length() == 0 ? "-" : out.toString();
    }

    static Object call(Method method, Object... arguments) throws Exception {
        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            throw (Exception) failure.getCause();
        }
    }

    static String text(Random random, String alphabet, int max) {
        StringBuilder out = new StringBuilder();
        for (int index = random.nextInt(max + 1); index > 0; index--) out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return out.toString();
    }

    static List<List<String[]>> maps() {
        List<List<String[]>> maps = new ArrayList<>();
        maps.add(List.of());
        maps.add(List.<String[]>of(new String[]{"", ""}));
        maps.add(List.<String[]>of(new String[]{"a", "b"}, new String[]{"A", "c"}, new String[]{"\uffff", "1"},
                new String[]{"\ud83d\ude00", "2"}));
        maps.add(List.<String[]>of(new String[]{"x", "\ud800"}, new String[]{"y", "\udc00"}));
        maps.add(List.<String[]>of(new String[]{"x", "\udc00"}, new String[]{"y", "\ud800"}));
        maps.add(List.<String[]>of(new String[]{"k", "a".repeat(300)}, new String[]{"k2", "é中😀"}));
        maps.add(List.<String[]>of(new String[]{"name", "0".repeat(64)}, new String[]{"other", "f".repeat(64)}));
        maps.add(List.<String[]>of(new String[]{"b", "0".repeat(64)}, new String[]{"a", "F".repeat(64)}));
        maps.add(List.<String[]>of(new String[]{" ", "0".repeat(64)}));
        maps.add(List.<String[]>of(new String[]{"\u2028", "0".repeat(64)}));
        maps.add(List.<String[]>of(new String[]{"\u00a0", "0".repeat(64)}));
        maps.add(List.<String[]>of(new String[]{"a", "0".repeat(63)}, new String[]{"b", "0".repeat(65)}));
        maps.add(List.<String[]>of(new String[]{"a", "0".repeat(63) + "g"}));
        maps.add(List.<String[]>of(new String[]{"schema", "1"}, new String[]{"generation", "x"}, new String[]{"a=b", "c:d"},
                new String[]{"#c", "!e"}, new String[]{"tab\tkey", "line\nvalue\r"}, new String[]{"\\", "\u007f\u0080"}));
        Random random = new Random(20261008);
        String alphabet = "ab=:# !\\\t\n\r\f\u0000\u007f\u0080\u00ffé中\ud83d\ude00\ud800\udc00z0f9u";
        for (int index = 0; index < 400; index++) {
            List<String[]> map = new ArrayList<>();
            for (int entry = random.nextInt(6); entry > 0; entry--) {
                String value = random.nextInt(3) == 0 ? String.format("%064x", random.nextLong()).substring(0, 64)
                        : text(random, alphabet, 12);
                map.add(new String[]{text(random, alphabet, 10), value});
            }
            maps.add(map);
        }
        return maps;
    }

    static List<byte[]> byteVectors() {
        List<byte[]> vectors = new ArrayList<>(List.of(new byte[0], new byte[]{0}, new byte[]{0x7f}, new byte[]{(byte) 0x80},
                new byte[]{(byte) 0xff}));
        byte[] all = new byte[256];
        for (int index = 0; index < 256; index++) all[index] = (byte) index;
        vectors.add(all);
        Random random = new Random(55);
        for (int index = 0; index < 60; index++) {
            byte[] bytes = new byte[random.nextInt(1000)];
            random.nextBytes(bytes);
            vectors.add(bytes);
        }
        return vectors;
    }

    static List<byte[]> properties(List<List<String[]>> maps) throws Exception {
        List<byte[]> texts = new ArrayList<>();
        for (String text : List.of("", "a=b", "a=b\n", "a\\u004", "#a\\u00\nb=c", "a=\\\n\n b", "a=b\\", "a\\=b=c", " a : b ",
                "a\\\r\n  b=c", "x=\\u00G1", "\\", "a=b\n\\", "\\\n#a=b", "\\\n\n", "!x\ny", "a\\\\=b", "a b c", "a\tb", "a\fb",
                "  \n\r\n#\n!\n", "a=\\u0041\\U0041", "a=\\uFFFF", "a=\\uffff\\u", "k\\:x=v", "\\ key=v", "key\\ =v",
                "# Ironwood Java Bridge paired artifact; schema 1\n", "# Ironwood Java Bridge paired artifact; schema 1\na=b\n",
                "# Ironwood Java Bridge paired artifact; schema 1\nb=c\na=b\n", "# Ironwood Java Bridge paired artifact; schema 1\na=b\na=c\n",
                "# Ironwood Java Bridge paired artifact; schema 1\r\na=b\r\n", "# Ironwood Java Bridge paired artifact; schema 1\na=\\u0041\n",
                "# Ironwood Java Bridge paired artifact; schema 1\na=\\u003d\n", "# Ironwood Java Bridge paired artifact; schema 1\na\\u003d=\\u003d\n",
                "a=b\r", "a=b\r\r\n\nc=d", "a\\\rb=c", "a\\\n\\\nb=c", "a=b\\\\\n", "a=b\\\\\\\n c")) {
            texts.add(text.getBytes(StandardCharsets.ISO_8859_1));
        }
        texts.add(new byte[]{'a', '=', (byte) 0xe9, (byte) 0xff, 0});
        // The pinned TLS and Bridge support inventories, and a support SDK's
        // generated build.properties: sorted key=value lines without escapes.
        texts.add(Files.readAllBytes(Path.of("packaging/tls-dependencies.properties")));
        texts.add(Files.readAllBytes(Path.of("packaging/java-bridge-support.properties")));
        texts.add(("format=1\npins.sha256=" + "ab".repeat(32) + "\nsha256.include/stdio.h=" + "01".repeat(32)
                + "\nsha256.lib/libgcc_s.so.1=" + "fe".repeat(32) + "\nsha256.lib/libstdc++.so.6=" + "0f".repeat(32)
                + "\n").getBytes(StandardCharsets.UTF_8));
        for (List<String[]> map : maps) {
            texts.add((byte[]) call(serialize, toMap(map)));
        }
        Random random = new Random(99);
        byte[] alphabet = {'a', 'b', '=', ':', ' ', '\t', '\f', '\r', '\n', '\\', 'u', '0', 'f', 'G', '#', '!', (byte) 0x80,
            (byte) 0xff, 0, '3', 'd'};
        for (int index = 0; index < 1500; index++) {
            byte[] bytes = new byte[random.nextInt(24)];
            for (int position = 0; position < bytes.length; position++) bytes[position] = alphabet[random.nextInt(alphabet.length)];
            texts.add(bytes);
        }
        List<byte[]> canonical = new ArrayList<>(texts.subList(texts.size() - 1500 - maps.size(), texts.size() - 1500));
        for (int index = 0; index < 600; index++) {
            byte[] base = canonical.get(random.nextInt(canonical.size()));
            byte[] mutated = Arrays.copyOf(base, base.length + 1);
            int at = random.nextInt(mutated.length);
            int kind = random.nextInt(3);
            if (kind == 0 && base.length > 0) {
                mutated = new byte[base.length - 1];
                int cut = random.nextInt(base.length);
                System.arraycopy(base, 0, mutated, 0, cut);
                System.arraycopy(base, cut + 1, mutated, cut, base.length - cut - 1);
            } else if (kind == 1) {
                System.arraycopy(base, at, mutated, at + 1, base.length - at);
                mutated[at] = alphabet[random.nextInt(alphabet.length)];
            } else {
                mutated = base.clone();
                if (mutated.length > 0) mutated[random.nextInt(mutated.length)] = alphabet[random.nextInt(alphabet.length)];
            }
            texts.add(mutated);
        }
        return texts;
    }

    static Map<String, String> toMap(List<String[]> pairs) {
        Map<String, String> map = new TreeMap<>();
        for (String[] pair : pairs) map.put(pair[0], pair[1]);
        return map;
    }

    static List<List<String[]>> attributeLists() {
        List<List<String[]>> lists = new ArrayList<>();
        String hexes = "0123456789abcdef".repeat(4);
        lists.add(List.of());
        lists.add(List.<String[]>of(new String[]{"Manifest-Version", "1.0"}));
        lists.add(List.<String[]>of(new String[]{"Ironwood-X", "y"}));
        lists.add(List.<String[]>of(new String[]{"Signature-Version", "2"}, new String[]{"A", "b"}));
        lists.add(List.<String[]>of(new String[]{"A", "b"}, new String[]{"manifest-version", "1.0"}, new String[]{"C", "d"}));
        lists.add(List.<String[]>of(new String[]{"Manifest-Version", "1.0"}, new String[]{"Automatic-Module-Name", "ironwood.bridge.a" + hexes},
                new String[]{"Ironwood-Bridge-Schema", "1"}, new String[]{"Ironwood-Bridge-Generation", hexes}));
        lists.add(List.<String[]>of(new String[]{"Manifest-Version", "1.0"}, new String[]{"X", "é".repeat(80)},
                new String[]{"Y", "a" + "中".repeat(60)}, new String[]{"Z", "ab" + "😀".repeat(50)}, new String[]{"W", ""},
                new String[]{"V", "\ud800x\udc00"}));
        lists.add(List.<String[]>of(new String[]{"Manifest-Version", "1.0"}, new String[]{"A", "b"}, new String[]{"a", "c"}));
        lists.add(List.<String[]>of(new String[]{"Manifest-Version", "1.0"}, new String[]{"Bad Name", "x"}));
        lists.add(List.<String[]>of(new String[]{"Manifest-Version", "1.0"}, new String[]{"", "x"}));
        lists.add(List.<String[]>of(new String[]{"Manifest-Version", "1.0"}, new String[]{"N".repeat(70), "x"},
                new String[]{"M".repeat(71), "x"}));
        Random random = new Random(1234);
        String nameAlphabet = "aZ09-_";
        String valueAlphabet = "aé中😀 :.-";
        for (int index = 0; index < 200; index++) {
            List<String[]> list = new ArrayList<>();
            if (random.nextBoolean()) list.add(new String[]{"Manifest-Version", "1.0"});
            for (int attribute = random.nextInt(4); attribute > 0; attribute--) {
                String name = text(random, nameAlphabet, 6);
                if (name.isEmpty()) name = "n";
                list.add(new String[]{name, text(random, valueAlphabet, 90)});
            }
            lists.add(list);
        }
        return lists;
    }

    static List<byte[]> manifests(List<byte[]> written) {
        List<byte[]> texts = new ArrayList<>();
        String y = "y";
        for (String text : List.of("A: b\n", "A: b\r\n", "A: b\r", "A: b\n\n", "A: b\nB: c\n", "A: b\n c\n", "A: b\n\tc\n",
                "A: b\n  c\n", "A:\n", "A: \n", "A:  b\n", "A :b\n", ": b\n", "-A: b\n", "_A: b\n", "A_b-c9: d\n", "Ä: b\n",
                "A: b\nName: x\n\n", "A: b\n\nName: x\nB: c\n", "A: b\n\nB: c\n", "A: b\n\n\nName: x\n",
                "A: b\n\nName: x\n c\nB: d\n", "A: b\n\nname: x\n", "A: b\n\n Name: x\n", "A: b\n\nName:x\n",
                "A: " + y.repeat(508) + "\n", "A: " + y.repeat(509) + "\n", "A: " + y.repeat(508) + "\r\n",
                "A: " + y.repeat(509) + "\r", "A: b\n" + "B: " + y.repeat(508), "A: b\n" + "B: " + y.repeat(509),
                "A: b\n " + y.repeat(510) + "\n", "A: b\n " + y.repeat(511) + "\n", "A: b\n\nName: " + y.repeat(505) + "\n",
                "A: b\n\nName: " + y.repeat(506) + "\n", "A: b\n\nName: x\n " + "z".repeat(600) + "\n",
                "A: b\n\nName: x\nB: " + y.repeat(600) + "\n", "A: b\n\n" + "q".repeat(600), "A".repeat(70) + ": b\n",
                "A".repeat(71) + ": b\n", "A: b\u0000c\n", "A: b\nA: c\n", "A: b\na: c\n", "", "\n", "A", "A: b", "A: b\n c",
                "A: b\nB", " A: b\n", "\n\nA: b\n", "A: b\n\n \n", "A: b\r\rB: c\r", "A: b\n\nName: x\nName: y\n",
                "A: b\n\nName: \n", "Manifest-Version: 1.0\nA: b\n c\n d\n", "A:b: c\n", "A: b: c\n", "a b: c\n",
                "\u0000: c\n", "A: b\n c\r\n d\r e\n", "A: b\r\n\r", "A: b\n \n", "Bad Name: x\n y\n", "A: b\n\nName: x\n\tB: c\n",
                "A: b\n\nName: x\n\n z\n", "A: b\n\nN\n", "manifest-version: 1.0\nMANIFEST-VERSION: 2\n")) {
            texts.add(text.getBytes(StandardCharsets.UTF_8));
        }
        texts.add(new byte[]{'A', ':', ' ', (byte) 0xff, (byte) 0xfe, '\n'});
        texts.add(new byte[]{'A', (byte) 0xc3, (byte) 0xa4, ':', ' ', 'b', '\n'});
        texts.add(new byte[]{'A', ':', ' ', (byte) 0xc3, '\n', ' ', (byte) 0xa9, '\n'});
        texts.add(new byte[]{'A', ':', ' ', (byte) 0xe4, (byte) 0xb8, '\n', ' ', (byte) 0xad, '\n'});
        texts.addAll(written);
        Random random = new Random(4321);
        String[] pieces = {"Manifest-Version: 1.0", "Name: x", "name: y", "A: b", "a: c", " cont", " ", "", "Bad Name: x",
            "B:c", ":", "Name:", "C: " + y.repeat(505), " " + "z".repeat(509), "é: x", "D: é", "Name: " + y.repeat(503)};
        String[] terminators = {"\n", "\r\n", "\r"};
        for (int index = 0; index < 1200; index++) {
            StringBuilder text = new StringBuilder();
            for (int line = random.nextInt(7); line > 0; line--) {
                text.append(pieces[random.nextInt(pieces.length)]).append(terminators[random.nextInt(3)]);
            }
            if (random.nextInt(4) == 0) text.append(pieces[random.nextInt(pieces.length)]);
            texts.add(text.toString().getBytes(StandardCharsets.UTF_8));
        }
        return texts;
    }

    static void printMap(String label, Map<String, String> map) {
        System.out.println(label + " " + map.size());
        map.forEach((key, value) -> System.out.println("  " + hex(key) + " " + hex(value)));
    }

    public static void main(String[] args) throws Exception {
        LogManager.getLogManager().reset();
        Class<?> generation = Class.forName("ironwood.compiler.bridge.BridgeGeneration");
        digest = generation.getDeclaredMethod("digest", Map.class);
        digest.setAccessible(true);
        bytesDigest = generation.getDeclaredMethod("bytesDigest", byte[].class);
        contentIdentity = generation.getDeclaredMethod("contentIdentity", Map.class);
        serialize = Class.forName("ironwood.compiler.BridgePackageManifest").getDeclaredMethod("serialize", Map.class);
        serialize.setAccessible(true);
        List<List<String[]>> maps = maps();
        List<byte[]> vectors = byteVectors();
        List<byte[]> properties = properties(maps);
        List<List<String[]>> attributes = attributeLists();
        List<byte[]> written = new ArrayList<>();
        StringBuilder corpus = new StringBuilder();
        for (List<String[]> map : maps) {
            corpus.append("map\n");
            for (String[] pair : map) corpus.append(hex(pair[0])).append(' ').append(hex(pair[1])).append('\n');
        }
        for (byte[] bytes : vectors) corpus.append("bytes ").append(hex(bytes)).append('\n');
        for (byte[] bytes : properties) corpus.append("properties ").append(hex(bytes)).append('\n');
        for (List<String[]> list : attributes) {
            corpus.append("attributes\n");
            for (String[] pair : list) corpus.append(hex(pair[0])).append(' ').append(hex(pair[1])).append('\n');
        }
        // Maps are printed first: identity, content identity and serialization.
        for (List<String[]> map : maps) {
            Map<String, String> values = toMap(map);
            System.out.println("map " + values.size() + " digest " + call(digest, values));
            try {
                System.out.println("  content " + call(contentIdentity, values));
            } catch (IllegalArgumentException failure) {
                System.out.println("  content error " + hex(failure.getMessage()));
            }
            System.out.println("  serialized " + hex((byte[]) call(serialize, values)));
        }
        for (byte[] bytes : vectors) System.out.println("bytes " + call(bytesDigest, (Object) bytes));
        for (byte[] bytes : properties) {
            try {
                var loaded = new Properties();
                loaded.load(new ByteArrayInputStream(bytes));
                var metadata = new TreeMap<String, String>();
                loaded.forEach((key, value) -> metadata.put((String) key, (String) value));
                printMap("properties", metadata);
                System.out.println("  canonical " + Arrays.equals(bytes, (byte[]) call(serialize, metadata)));
            } catch (IllegalArgumentException failure) {
                System.out.println("properties error " + hex(failure.getMessage()));
            }
        }
        for (List<String[]> list : attributes) {
            var manifest = new Manifest();
            String outcome = null;
            for (String[] pair : list) {
                try {
                    manifest.getMainAttributes().putValue(pair[0], pair[1]);
                } catch (IllegalArgumentException failure) {
                    outcome = "invalid " + hex(failure.getMessage());
                }
            }
            var bytes = new ByteArrayOutputStream();
            manifest.write(bytes);
            written.add(bytes.toByteArray());
            System.out.println("attributes " + (outcome == null ? "ok" : outcome) + " " + hex(bytes.toByteArray()));
        }
        List<byte[]> texts = manifests(written);
        for (byte[] bytes : texts) corpus.append("manifest ").append(hex(bytes)).append('\n');
        Files.writeString(Path.of(args[0]), corpus.toString(), StandardCharsets.UTF_8);
        for (byte[] bytes : texts) {
            try {
                var manifest = new Manifest(new ByteArrayInputStream(bytes));
                var main = manifest.getMainAttributes();
                System.out.println("manifest " + main.size());
                for (var entry : main.entrySet()) {
                    System.out.println("  " + hex(entry.getKey().toString()) + " " + hex((String) entry.getValue()));
                }
                String version = main.getValue("manifest-version");
                System.out.println("  version " + (version == null ? "null" : hex(version)));
            } catch (java.io.IOException failure) {
                System.out.println("manifest error " + hex(failure.getMessage()));
            }
        }
    }
}
