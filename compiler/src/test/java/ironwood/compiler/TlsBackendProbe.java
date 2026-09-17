// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.source.SourceFile;
import java.nio.file.*;
import java.util.HexFormat;
import java.security.MessageDigest;

/** Subprocess probe with an isolated SDK and a tracing Clang. No production hooks. */
public final class TlsBackendProbe {
    private TlsBackendProbe() {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Path sdk = Path.of(args[1]);
        Path trace = Path.of(args[2]);
        var toolchain = LlvmToolchain.discover(null).toolchain().orElseThrow();
        for (String name : new String[] {"Plain", "Client"}) {
            String source = Files.readString(Path.of(args[name.equals("Plain") ? 3 : 4]));
            var result = new CompilerPipeline(UnfreedMode.ERROR).compile(SourceFile.of(name + ".iron", source));
            if (!result.successful()) throw new AssertionError(result.diagnostics());
            var program = ClosedWorldPruner.prune(result.program().orElseThrow());
            var requirements = NativeLinkRequirements.from(program);
            Path llvm = output.resolve(name + ".ll");
            Files.writeString(llvm, new LlvmEmitter().emit(program));
            int before = compilations(trace);
            link(toolchain, llvm, output.resolve(name), requirements);
            int after = compilations(trace);
            link(toolchain, llvm, output.resolve(name), requirements);
            if (compilations(trace) != after) throw new AssertionError("unchanged runtime inputs missed the cache");
            if (name.equals("Client")) {
                if (after <= before) throw new AssertionError("TLS adapter was not compiled");
                Path header = sdk.resolve("include/openssl/configuration.h");
                byte[] bytes = Files.readAllBytes(header);
                Files.delete(header); // SDK files may be hardlinked to the untouched prepared prefix.
                Files.writeString(header, new String(bytes, java.nio.charset.StandardCharsets.UTF_8) + "\n/* cache probe */\n");
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(header)));
                Path manifest = sdk.resolve("build.properties");
                String text = Files.readString(manifest).replaceAll("(?m)^sha256.include/openssl/configuration.h=.*$",
                        "sha256.include/openssl/configuration.h=" + digest);
                Files.delete(manifest);
                Files.writeString(manifest, text);
                link(toolchain, llvm, output.resolve(name), requirements);
                if (compilations(trace) != after + 1) throw new AssertionError("SDK header/build change did not recompile exactly the TLS adapter");
            }
        }
        System.out.println("PASS: source pipeline and same-process runtime cache invalidation");
    }

    private static int compilations(Path trace) throws Exception {
        return (int) Files.readAllLines(trace).stream().filter(line -> line.contains("\"-c\"")).count();
    }
    private static void link(LlvmToolchain tools, Path llvm, Path output, NativeLinkRequirements requirements) {
        var linked = new NativeBackend().link(tools, llvm, output, OptimizationLevel.O3, requirements);
        if (!linked.success()) throw new AssertionError(linked.output());
    }
}
