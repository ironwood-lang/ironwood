// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.backend;

import ironwood.compiler.bridge.BridgeGeneration;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;

/** Actual verified collector and root payloads for one target's paired image. */
public final class BridgeGcBuildInputs {
    private BridgeGcBuildInputs() {}

    public static Map<String, String> discover(LlvmToolchain toolchain) throws IOException {
        var runtime = RuntimeLibrary.discover();
        if (!runtime.successful()) throw new IOException(runtime.error());
        var distribution = runtime.source().orElseThrow().getParent().getParent().getParent();
        var dependency = GcDependency.discover(distribution, toolchain);
        var values = new TreeMap<>(BridgeGeneration.managedContract());
        values.put("gc.config.sha256", dependency.identity());
        values.put("gc.sdk.manifest.sha256", TlsDependency.sha256(dependency.home().resolve("build.properties")));
        values.put("gc.archive.sha256", TlsDependency.sha256(dependency.home().resolve("lib/libgc.a")));
        values.put("gc.adapter.sha256", TlsDependency.sha256(dependency.home().resolve("lib/libironwood_gc_engine.a")));
        values.put("gc.root-reader.sha256", TlsDependency.sha256(distribution.resolve("runtime/src/ironwood_gc_roots.c")));
        values.put("llvm.stackmap.version", "3");
        values.put("gc.isolation", "private-per-image");
        return Map.copyOf(values);
    }

    public static Map<String, byte[]> distribution(LlvmToolchain toolchain) throws IOException {
        var runtime = RuntimeLibrary.discover();
        if (!runtime.successful()) throw new IOException(runtime.error());
        var distribution = runtime.source().orElseThrow().getParent().getParent().getParent();
        var dependency = GcDependency.discover(distribution, toolchain);
        return Map.of("META-INF/ironwood/licenses/BDWGC-NOTICES.txt", Files.readAllBytes(dependency.home().resolve("licenses/BDWGC-NOTICES.txt")),
                "META-INF/ironwood/source/BDWGC/gc-8.2.12.tar.gz", Files.readAllBytes(dependency.home().resolve("sources/gc-8.2.12.tar.gz")),
                "META-INF/ironwood/source/BDWGC/configurations/" + dependency.identity() + "/configuration.json", Files.readAllBytes(dependency.home().resolve("configuration.json")),
                "META-INF/ironwood/source/BDWGC/configurations/" + dependency.identity() + "/build.properties", Files.readAllBytes(dependency.home().resolve("build.properties")));
    }
}
