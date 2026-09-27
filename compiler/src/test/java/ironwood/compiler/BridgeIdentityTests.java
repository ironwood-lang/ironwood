// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class BridgeIdentityTests {
    private BridgeIdentityTests() {}

    static void identity() throws Exception {
        String source = BridgeResultOriginTests.SOURCE.replace("destructor { free owned; }", """
                Node() {}
                Node(boolean fail) { if (fail) throw null; }
                static Node freshFail() { return new Node(true); }
                destructor { free owned; }
                """);
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("test/Identity.iron", source)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var module = BridgeEntryModule.rootObjects(artifact, BridgeRootResultTests.roots(artifact,
                Set.of("fresh", "freshFail", "argument", "alias"), Set.of("resultfixture.Node")));
        check(module.rootRetention().orElseThrow().rootSlots().values().stream().allMatch(List::isEmpty),
                "identity-only fixture cannot commit retention slots");
        Path base = Path.of("workspace/java-bridge/evidence/p0b/root-identity").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Files.writeString(directory.resolve("Identity.iron"), source);
        Path llvm = directory.resolve("program.ll");
        Files.writeString(llvm, new LlvmEmitter().emit(module));
        Path adapter = directory.resolve("adapter.c");
        Files.writeString(adapter, adapter(module));
        Path consumer = directory.resolve("BridgeIdentityConsumer.java");
        Files.writeString(consumer, BridgeIdentityFixtureSources.CONSUMER);
        Path javaHome = Path.of(System.getProperty("java.home"));
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", consumer.toString()), "javac");
        var discovery = LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path object = directory.resolve("adapter-" + level + ".o");
            BridgeEntryTests.run(directory, List.of(toolchain.clang().toString(), "-std=c11", "-Wall", "-Wextra", "-Werror",
                    "-fPIC", "-fvisibility=hidden", level.clangArgument(), "-I" + javaHome.resolve("include"),
                    "-I" + javaHome.resolve(mac ? "include/darwin" : "include/linux"), "-I" + Path.of("runtime/include").toAbsolutePath(),
                    "-c", adapter.toString(), "-o", object.toString()), "adapter-" + level);
            Path image = directory.resolve("identity-" + level + (mac ? ".dylib" : ".so"));
            var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
            Files.writeString(directory.resolve("link-" + level + ".log"), linked.output());
            check(linked.success(), linked.output());
            Files.writeString(directory.resolve("sha256-" + level + ".txt"), HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image))) + "  " + image.getFileName() + "\n");
            var command = List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-Xmx64m", "-cp", directory.toString(),
                    "BridgeIdentityConsumer", image.toString());
            String output = BridgeEntryTests.run(directory, command, "consumer-" + level);
            check(output.matches("root-identity-benchmark:50000:0:0:[0-9]+:[0-9]+\\nroot-identity-ok:reuse:collection:reservation:growth\\n"), output);
            var limitedCommand = new java.util.ArrayList<>(command); limitedCommand.add("oom");
            Files.writeString(directory.resolve("oom-" + level + ".command.txt"), String.join("\n", limitedCommand)
                    + "\nIRONWOOD_ALLOCATION_LIMIT=0\n");
            var builder = new ProcessBuilder(limitedCommand).redirectErrorStream(true).redirectOutput(directory.resolve("oom-" + level + ".log").toFile());
            builder.environment().put("IRONWOOD_ALLOCATION_LIMIT", "0");
            var child = builder.start();
            if (!child.waitFor(90, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("identity OOM child timeout"); }
            check(child.exitValue() == 0 && Files.readString(directory.resolve("oom-" + level + ".log")).equals("identity-native-oom-ok\n"),
                    Files.readString(directory.resolve("oom-" + level + ".log")));
        }
        System.out.println("bridge root identity evidence: " + directory);
    }

    private static String adapter(BridgeEntryModule module) {
        StringBuilder declarations = new StringBuilder(BridgeCommitFixtureSources.HEADERS);
        for (var entry : module.entries()) {
            if (entry.root().callable().kind() == IrCallableKind.CONSTRUCTOR) continue;
            var callable = entry.root().callable();
            var result = module.rootRetention().orElseThrow().resultOrigins().get(callable);
            check(result != null && (result.kind() == BridgeResultOriginContract.Kind.FRESH_ROOT
                    || result.kind() == BridgeResultOriginContract.Kind.INPUT_ALIAS), "unproved fixture conversion");
            declarations.append("extern int32_t ").append(entry.function().linkageName()).append('(');
            for (var parameter : entry.function().parameters()) {
                var type = parameter.value().type();
                check(type.isReference() || type.equals(IrType.I8) || type.equals(IrType.I64), "unexpected private ABI");
                declarations.append(type.isReference() ? "void *" : type.equals(IrType.I8) ? "uint8_t" : "int64_t").append(", ");
            }
            declarations.setLength(declarations.length() - 2);
            declarations.append(");\n#define call_").append(callable.name()).append(' ').append(entry.function().linkageName()).append('\n');
        }
        check(module.destructions().size() == 1, "unexpected ownership root");
        declarations.append("extern void ").append(module.destructions().getFirst().function().linkageName())
                .append("(void *);\n#define freeNode ").append(module.destructions().getFirst().function().linkageName()).append('\n');
        return declarations + BridgeIdentityFixtureSources.ADAPTER;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
