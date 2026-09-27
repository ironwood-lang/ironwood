// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarInputStream;
import java.util.zip.ZipFile;

final class BridgeJarArchiveTests {
    static final String NAME = "Java Bridge jar publication verifies bytes and preserves earlier output on failure";
    private BridgeJarArchiveTests() {}

    static void archive() throws Exception {
        Path directory = Files.createTempDirectory("bridge jar publication ");
        try {
            Path output = directory.resolve("artifact.jar");
            var entries = new TreeMap<String, byte[]>();
            entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            entries.put("META-INF/ironwood/native/macos-arm64/image.dylib", new byte[]{0, 1, -1, 42});
            entries.put("META-INF/LICENSES/notice", "notice\n".getBytes(StandardCharsets.UTF_8));
            entries.put("META-INF/ironwood/sources/Δ.java", "source\n".getBytes(StandardCharsets.UTF_8));
            BridgeJarArchive.publish(output, entries);
            byte[] first = Files.readAllBytes(output);
            try (var jar = new JarInputStream(Files.newInputStream(output))) {
                check(jar.getManifest() != null, "Java manifest not first");
            }
            try (var jar = new ZipFile(output.toFile())) {
                for (var entry : entries.entrySet()) try (var input = jar.getInputStream(jar.getEntry(entry.getKey()))) {
                    check(java.util.Arrays.equals(input.readAllBytes(), entry.getValue()), "jar entry bytes changed");
                }
            }
            BridgeJarArchive.publish(output, entries);
            check(java.util.Arrays.equals(first, Files.readAllBytes(output)), "container ordering/timestamps changed identical jar content");
            for (String name : new String[]{"../outside", "/absolute", "a//b", "a/./b", "a/../b", "a\\b", "a\0b", ""}) {
                var invalid = new TreeMap<>(entries); invalid.put(name, new byte[]{1});
                refuse(output, invalid); check(java.util.Arrays.equals(first, Files.readAllBytes(output)), "failed validation replaced earlier output");
            }
            refuse(output, Map.of("unversioned", new byte[]{1}));
            Path link = directory.resolve("alias.jar"); Files.createSymbolicLink(link, output);
            refuse(link, entries); check(Files.isSymbolicLink(link) && java.util.Arrays.equals(first, Files.readAllBytes(output)), "symlink output was modified");
            entries.put("new-entry", new byte[]{2}); BridgeJarArchive.publish(output, entries);
            check(!java.util.Arrays.equals(first, Files.readAllBytes(output)), "successful publication did not replace old jar");
            try (var paths = Files.list(directory)) { check(paths.count() == 2, "staged archive was left behind"); }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void refuse(Path output, Map<String, byte[]> entries) throws Exception {
        try { BridgeJarArchive.publish(output, entries); throw new AssertionError("invalid publication accepted"); }
        catch (IOException expected) { check(expected.getMessage().contains("Java Bridge"), expected.toString()); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
