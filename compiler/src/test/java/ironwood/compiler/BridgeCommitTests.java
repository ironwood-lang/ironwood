// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Fixed P0 host protocol, authorized only by reusable compiler contracts. */
final class BridgeCommitTests {
    private BridgeCommitTests() {}

    private static final List<String> OPERATIONS = List.of("newItem", "newHolder", "set", "two", "clear",
            "setThenFail", "both", "other", "value", "producerFailure");

    static void commit() throws Exception {
        boolean fault = "1".equals(System.getenv("IRONWOOD_BRIDGE_COMMIT_SNAPSHOT_FAULT"));
        String source = BridgeRootEntryTests.SOURCE.replace("Item() {}", "Item(int number) { this.number = number; }")
                .replace("Holder alias()", "int value() { return first == null ? -1 : first.number; } "
                        + "void producerFailure(Item item) { first = item; throw new IllegalStateException(); } Holder alias()");
        var artifact = BridgeRootRetentionTests.artifact(source, UnfreedMode.OFF);
        var methods = new java.util.HashSet<>(BridgeRootEntryTests.METHODS);
        methods.addAll(Set.of("value", "producerFailure"));
        var module = BridgeEntryModule.rootObjects(artifact, BridgeRootRetentionTests.roots(artifact, methods, BridgeRootEntryTests.TYPES));
        Path base = Path.of("workspace/java-bridge/evidence/p0b/host-commit").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path llvm = directory.resolve("program.ll");
        Files.writeString(llvm, new LlvmEmitter().emit(module));
        Files.writeString(directory.resolve("Roots.iron"), source);
        Files.writeString(directory.resolve("instrumentation.txt"), fault ? "test-only snapshot failure runtime\n" : "unmodified production runtime\n");
        Path adapter = directory.resolve("adapter.c");
        Files.writeString(adapter, adapter(module));
        Path consumer = directory.resolve("BridgeCommitConsumer.java");
        Files.writeString(consumer, BridgeCommitFixtureSources.CONSUMER);
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
            Path image = directory.resolve("commit-" + level + (mac ? ".dylib" : ".so"));
            var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
            Files.writeString(directory.resolve("link-" + level + ".log"), linked.output());
            check(linked.success(), linked.output());
            Files.writeString(directory.resolve("sha256-" + level + ".txt"), HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image))) + "  " + image.getFileName() + "\n");
            var command = new java.util.ArrayList<>(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni",
                    "-cp", directory.toString(), "BridgeCommitConsumer", image.toString()));
            if (fault) command.add("fault");
            String output = BridgeEntryTests.run(directory, command, "consumer-" + level);
            check(fault ? output.equals("host-commit-snapshot-fallback-ok\n")
                    : output.matches("host-commit-benchmark:20000:400000:[0-9]+\\nhost-commit-ok\\n"), output);
        }
        System.out.println("bridge host commit evidence: " + directory);
        if (!fault) {
            Path runtimeHome = directory.resolve("fault-runtime");
            try (var paths = Files.walk(Path.of("runtime"))) {
                for (Path path : paths.toList()) {
                    Path destination = runtimeHome.resolve(path);
                    if (Files.isDirectory(path)) Files.createDirectories(destination); else Files.copy(path, destination);
                }
            }
            Path runtime = runtimeHome.resolve("runtime/src/ironwood_runtime.c");
            Files.writeString(runtime, "#define ironwood_bridge_snapshot_failure ironwood_bridge_original_snapshot_failure\n"
                    + Files.readString(runtime) + """

                    /* Test-only failure in the copied runtime, isolated in a child JVM. */
                    #undef ironwood_bridge_snapshot_failure
                    void ironwood_bridge_snapshot_failure(const void *object, struct ironwood_bridge_result *result) {
                        (void)result;
                        void *unexpected = ironwood_allocate(SIZE_MAX, NULL, (void *)object);
                        ironwood_deallocate(unexpected); abort();
                    }
                    """);
            var command = List.of(javaHome.resolve("bin/java").toString(), "-ea", "-cp", System.getProperty("java.class.path"),
                    "ironwood.compiler.CompilerTests", "--test", "Java Bridge host commit completes retention before Java failure");
            Files.writeString(directory.resolve("fault-child.command.txt"), String.join("\n", command)
                    + "\nIRONWOOD_RUNTIME_HOME=" + runtimeHome + "\nIRONWOOD_BRIDGE_COMMIT_SNAPSHOT_FAULT=1\n");
            var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve("fault-child.log").toFile());
            builder.environment().put("IRONWOOD_RUNTIME_HOME", runtimeHome.toString());
            builder.environment().put("IRONWOOD_BRIDGE_COMMIT_SNAPSHOT_FAULT", "1");
            var child = builder.start();
            if (!child.waitFor(90, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("host commit fault child timeout"); }
            check(child.exitValue() == 0, Files.readString(directory.resolve("fault-child.log")));
        }
    }

    private static String adapter(BridgeEntryModule module) {
        StringBuilder declarations = new StringBuilder();
        String[] layouts = new String[OPERATIONS.size()];
        for (var entry : module.entries()) {
            var callable = entry.root().callable();
            String name = callable.kind() == IrCallableKind.CONSTRUCTOR
                    ? "new" + callable.owner().substring(callable.owner().lastIndexOf('.') + 1) : callable.name();
            int operation = OPERATIONS.indexOf(name);
            check(operation >= 0, "unexpected private fixture entry " + name);
            declarations.append("extern int32_t ").append(entry.function().linkageName()).append('(');
            for (var parameter : entry.function().parameters()) declarations.append(cType(parameter.value().type())).append(", ");
            declarations.setLength(declarations.length() - 2);
            declarations.append(");\n#define call_").append(name).append(' ').append(entry.function().linkageName()).append('\n');
            var slots = module.rootRetention().orElseThrow().entries().get(callable).slots();
            check(slots.size() <= 4, "private bounded payload too small");
            StringBuilder layout = new StringBuilder("{").append(slots.size()).append(", {");
            for (var slot : slots) {
                int field = module.rootRetention().orElseThrow().rootSlots().get(IrType.reference(slot.field().ownerClass())).indexOf(slot.field());
                check(field >= 0 && field < 2, "unexpected persistent root slot");
                check(slot.field().name().equals(List.of("first", "second").get(field)) && slot.holderInput() < 4,
                        "private host layout does not match the proved field/input layout");
                int inputs = 0;
                for (int input : slot.valueInputs()) { check(input < 4, "private input map too small"); inputs |= 1 << input; }
                layout.append('{').append(slot.holderInput()).append(',').append(field).append(',').append(inputs).append("},");
            }
            if (slots.isEmpty()) layout.append("{0,0,0}");
            layouts[operation] = layout.append("}}").toString();
        }
        for (var destruction : module.destructions()) {
            String type = destruction.contract().type().referenceName();
            declarations.append("extern void ").append(destruction.function().linkageName()).append("(void *);\n#define free")
                    .append(type.substring(type.lastIndexOf('.') + 1)).append(' ').append(destruction.function().linkageName()).append('\n');
        }
        for (String layout : layouts) check(layout != null, "missing private fixture operation");
        return BridgeCommitFixtureSources.HEADERS + declarations + BridgeCommitFixtureSources.ADAPTER
                .replace("${layouts}", String.join(",\n", layouts));
    }

    private static String cType(IrType type) {
        if (type.isReference()) return "void *";
        if (type.equals(IrType.I8)) return "uint8_t";
        if (type.equals(IrType.I32)) return "int32_t";
        if (type.equals(IrType.I64)) return "int64_t";
        throw new AssertionError(type);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
