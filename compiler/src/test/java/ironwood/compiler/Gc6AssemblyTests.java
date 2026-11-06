// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import java.nio.file.*;
import java.util.*;

/** Real host artifacts preserve target GC inventories through public assembly. */
final class Gc6AssemblyTests {
    private Gc6AssemblyTests() {}
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(out);
        Path assembled = out.resolve("assembled.jar");
        var command = new ArrayList<>(List.of("--java-bridge-assemble", "-o", assembled.toString()));
        command.addAll(Arrays.asList(args).subList(1, args.length));
        if (Main.run(command.toArray(String[]::new), System.out, System.err) != 0) throw new AssertionError("assembly refused");
        var paired = BridgePairedArchive.read(assembled);
        var targets = paired.metadata().get("native.targets").split(",");
        if (targets.length != args.length - 1) throw new AssertionError("assembly dropped a target");
        for (String target : targets) {
            String config = paired.metadata().get("target." + target + ".gc.config.sha256");
            for (String name : List.of("configuration.json", "build.properties"))
                if (!paired.entries().containsKey("META-INF/ironwood/source/BDWGC/configurations/" + config + "/" + name))
                    throw new AssertionError("assembly dropped target SDK documents");
        }
        if (Main.run(new String[]{"--java-bridge-distribution", "--input", assembled.toString(), "--group-id", "gc6.qualification",
                "--artifact-id", "scalar", "--version", "1.0", "-d", out.resolve("distribution").toString()}, System.out, System.err) != 0)
            throw new AssertionError("distribution refused");
        if (Main.run(new String[]{"--java-bridge-values", "-o", out.resolve("ironwood-bridge-values.jar").toString()}, System.out, System.err) != 0)
            throw new AssertionError("shared values refused");
        System.out.println("GC6 public assembly and distribution passed");
    }
}
