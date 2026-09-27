// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeGeneration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipFile;

/** Explicit Linux host selection; absence is a failed prerequisite, never a pass. */
final class BridgeLinuxProducerTests {
    static final String NAME = "Java Bridge Linux producer packages proved roots and complete private native support";
    private BridgeLinuxProducerTests() {}

    static void producer() throws Exception {
        if (!System.getProperty("os.name").equals("Linux")) throw new AssertionError("requires a prepared Linux bridge host");
        String target = System.getProperty("os.arch").equals("aarch64") ? "linux-arm64" : "linux-x86_64";
        Path base = Path.of("workspace/java-bridge/evidence/p6a/" + target + "-producer").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path source = directory.resolve("Holder.iron"), consumer = directory.resolve("Consumer.java");
        Files.writeString(source, BridgeMixedLifetimeTests.SOURCE);
        Files.writeString(consumer, BridgeObjectProducerTests.RETENTION_CONSUMER);
        Path javaHome = Path.of(System.getProperty("java.home"));
        for (String level : List.of("O0", "O3")) {
            Path folder = directory.resolve(level); Files.createDirectories(folder); Path jar = folder.resolve("mixed.jar");
            BridgeProducerTests.command(folder, "producer", 0, new String[]{"--java-bridge", "--export", "mixedlife", "--unfreed=off",
                    "-" + level, "-o", jar.toString(), source.toString()});
            var manifest = new Properties();
            try (var zip = new ZipFile(jar.toFile())) {
                try (var input = zip.getInputStream(zip.getEntry(BridgePackageManifest.PATH))) { manifest.load(input); }
                check(manifest.getProperty("native.target").equals(target) && manifest.getProperty("native.linux.glibc.minimum").equals("2.17")
                        && manifest.getProperty("native.linux.binding").equals("now"), "missing Linux deployment metadata");
                String root = "META-INF/ironwood/native/" + target + "/" + manifest.getProperty("generation") + "/";
                String support = root + manifest.getProperty("native.linux.support") + "/";
                for (String path : List.of("lib/libgcc_s.so.1", "lib/libstdc++.so.6", "sources/gcc-16.2.0.tar.gz", "sources/zlib-1.3.1.tar.gz",
                        "licenses/GPL-3.0.txt", "licenses/GCC-exception-3.1.txt", "recipes/libgcc/info/recipe/meta.yaml", "build.properties")) {
                    check(zip.getEntry(support + path) != null, "missing corresponding support source/license: " + path);
                }
                check(manifest.getProperty("native.extract.0.path").endsWith("libgcc_s.so.1")
                        && manifest.getProperty("native.extract.1.path").endsWith("libstdc++.so.6"), "wrong extracted runtime inventory");
                for (var entry : zip.stream().toList()) {
                    if (entry.getName().equals(BridgePackageManifest.PATH)) continue;
                    try (var input = zip.getInputStream(entry)) {
                        check(BridgeGeneration.bytesDigest(input.readAllBytes()).equals(manifest.getProperty("content.sha256." + entry.getName())),
                                "unpaired Linux content: " + entry.getName());
                    }
                }
            }
            Path classes = folder.resolve("consumer-classes");
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                    "-cp", jar.toString(), "-d", classes.toString(), consumer.toString()), "consumer-javac");
            for (boolean checked : List.of(false, true)) {
                var run = new java.util.ArrayList<>(List.of(javaHome.resolve("bin/java").toString()));
                if (checked) run.add("-Xcheck:jni");
                run.addAll(List.of("-cp", jar + ":" + classes, "Consumer"));
                check(BridgeEntryTests.run(folder, run, "consumer-" + checked).equals("object-producer-ok\n"), "Linux mixed lifetime consumer failed");
            }
        }
        System.out.println("Linux producer evidence: " + directory);
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
