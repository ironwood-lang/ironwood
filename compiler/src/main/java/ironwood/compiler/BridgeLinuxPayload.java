// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.BridgeNativeSupport;
import ironwood.compiler.backend.LlvmToolchain;
import ironwood.compiler.bridge.BridgeGeneration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Audits the final ELF and its pinned, source-complete private dependency delivery. */
record BridgeLinuxPayload(Map<String, String> metadata, Map<String, byte[]> supportEntries,
                          Map<String, String> extractedDependencies) {
    BridgeLinuxPayload {
        metadata = Map.copyOf(metadata); supportEntries = Map.copyOf(supportEntries);
        extractedDependencies = Map.copyOf(extractedDependencies);
    }

    static BridgeLinuxPayload inspect(Path stage, Path image, String target, LlvmToolchain tools,
            BridgeNativeSupport support) throws IOException {
        var metadata = new TreeMap<String, String>();
        metadata.put("native.cpu", target.equals("linux-arm64") ? "baseline-arm64" : "baseline-x86_64");
        metadata.put("native.linux.libc", "glibc"); metadata.put("native.linux.glibc.minimum", "2.17");
        metadata.put("native.linux.support", support.directory()); metadata.put("native.linux.binding", "now");
        String audit = audit(stage, image, target, tools, "$ORIGIN/" + support.directory() + "/lib", true);
        var dependencies = matches(audit, "Shared library: \\[([^\\]]+)\\]");
        int index = 0;
        for (String dependency : dependencies) metadata.put("native.dependency." + index++, dependency);
        var entries = new TreeMap<String, byte[]>();
        var extracted = new TreeMap<String, String>();
        for (String key : support.manifest().stringPropertyNames().stream().sorted().toList()) {
            if (!key.startsWith("sha256.")) continue;
            String relative = key.substring(7), delivered = support.directory() + "/" + relative;
            byte[] bytes = Files.readAllBytes(stage.resolve(delivered));
            String digest = BridgeGeneration.bytesDigest(bytes);
            if (!digest.equals(support.manifest().getProperty(key))) throw new IOException("changed delivered bridge support: " + relative);
            entries.put(delivered, bytes);
            if (Set.of("lib/libgcc_s.so.1", "lib/libstdc++.so.6").contains(relative)) {
                audit(stage, stage.resolve(delivered), target, tools, "$ORIGIN/.", false);
                extracted.put(delivered, digest);
            }
        }
        byte[] manifest = Files.readAllBytes(stage.resolve(support.directory()).resolve("build.properties"));
        if (!java.util.Arrays.equals(manifest, Files.readAllBytes(support.home().resolve("build.properties")))) {
            throw new IOException("changed delivered bridge support manifest");
        }
        entries.put(support.directory() + "/build.properties", manifest);
        if (extracted.size() != 2) throw new IOException("incomplete delivered bridge runtime");
        return new BridgeLinuxPayload(metadata, entries, extracted);
    }

    private static String audit(Path stage, Path image, String target, LlvmToolchain tools,
            String expectedRunpath, boolean eager) throws IOException {
        byte[] bytes = Files.readAllBytes(image);
        var elf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int machine = target.equals("linux-arm64") ? 183 : target.equals("linux-x86_64") ? 62 : -1;
        if (machine < 0 || bytes.length < 64 || elf.getInt(0) != 0x464c457f || bytes[4] != 2 || bytes[5] != 1
                || elf.getShort(16) != 3 || elf.getShort(18) != machine) throw new IOException("invalid " + target + " ELF payload: " + image);
        String output = BridgeBuildTools.run(stage, "Linux bridge dependency audit", List.of(tools.clang().resolveSibling("llvm-readelf").toString(),
                "--dynamic", "--version-info", image.toString()));
        auditDynamic(output, target, expectedRunpath, eager);
        return output;
    }

    static void auditDynamic(String output, String target, String expectedRunpath, boolean eager) throws IOException {
        if (eager && !output.contains("BIND_NOW") && !Pattern.compile("FLAGS[^\\n]*NOW").matcher(output).find()) {
            throw new IOException("Linux bridge image lacks eager relocation binding");
        }
        var paths = matches(output, "Library (?:rpath|runpath): \\[([^\\]]*)\\]");
        if (!paths.equals(Set.of(expectedRunpath))) throw new IOException("Linux bridge image has unexpected dependency lookup paths: " + paths);
        Set<String> allowed = Set.of("libstdc++.so.6", "libgcc_s.so.1", "libc.so.6", "libm.so.6", "libdl.so.2", "libpthread.so.0", "librt.so.1",
                target.equals("linux-arm64") ? "ld-linux-aarch64.so.1" : "ld-linux-x86-64.so.2");
        var dependencies = matches(output, "Shared library: \\[([^\\]]+)\\]");
        if (dependencies.isEmpty() || !allowed.containsAll(dependencies)) throw new IOException("unsupported Linux bridge dependency: " + dependencies);
        String needs = output.contains("Version needs section") ? output.substring(output.indexOf("Version needs section")) : "";
        for (String version : matches(needs, "Name: GLIBC_([0-9.]+)")) {
            String[] parts = version.split("\\.");
            if (parts.length < 2 || Integer.parseInt(parts[0]) > 2 || (Integer.parseInt(parts[0]) == 2
                    && (Integer.parseInt(parts[1]) > 17 || (Integer.parseInt(parts[1]) == 17
                    && java.util.Arrays.stream(parts).skip(2).anyMatch(part -> Integer.parseInt(part) != 0))))) {
                throw new IOException("Linux bridge payload exceeds glibc 2.17 baseline: " + version);
            }
        }
        if (needs.contains("GLIBC_PRIVATE")) throw new IOException("Linux bridge payload requires private glibc symbols");
    }

    private static Set<String> matches(String text, String regex) {
        var result = new TreeSet<String>(); var matcher = Pattern.compile(regex).matcher(text);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }
}
