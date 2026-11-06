// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.backend.*;
import java.nio.file.*;
import java.util.*;

/** The production packager consumes the same complete managed admission. */
final class Gc6PackageTests {
    private Gc6PackageTests() {}

    public static void main(String[] args) throws Exception {
        var fixture = Gc6CallbackTests.fixture(0);
        var admission = Gc6CallbackTests.admission(fixture);
        var toolchain = LlvmToolchain.discover(Path.of(args[1])).toolchain().orElseThrow();
        Path out = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(out);
        Path jar = out.resolve("image-" + args[2] + ".jar");
        BridgeProducer.build(fixture.artifact(), admission, jar, toolchain, OptimizationLevel.O3,
                new BridgeDistributionInputs.Options(List.of(), List.of()), System.err);
        var paired = BridgePairedArchive.read(jar);
        if (!paired.generation().managedHeap() || !paired.entries().containsKey("META-INF/ironwood/licenses/BDWGC-NOTICES.txt")
                || !paired.entries().containsKey("META-INF/ironwood/source/BDWGC/gc-8.2.12.tar.gz"))
            throw new AssertionError("production image lost managed compatibility or collector source/notices");
        Files.writeString(out.resolve("support-" + args[2] + ".txt"), paired.generation().supportPackage());
        System.out.println("GC6 production package passed");
    }
}
