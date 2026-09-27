// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeEntryModule;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Private P1 consumer of the production final-link pipeline. */
final class BridgeLibraryNativeTests {
    static final String NAME = "Java Bridge production libraries contain cold initialization and allocation failures";
    private static final String SOURCE = """
            package librarynative;
            final class Engine {
                static int add(int a, int b) { return a + b; }
                static int fail() { throw null; }
                static int initialized() { return Lazy.value; }
                static int broken() { return Broken.value; }
                static int attempts() { return Counts.lazy * 100 + Counts.broken; }
                static int allocate() {
                    Cell cell = new Cell(73);
                    int value = cell.read();
                    free cell;
                    return value;
                }
                static int unreachable() { return 998; }
            }
            final class Counts { static int lazy; static int broken; }
            final class Lazy {
                static int value = initialize();
                static int initialize() {
                    Counts.lazy++;
                    Cell cell = new Cell(37);
                    int value = cell.read();
                    free cell;
                    return value;
                }
            }
            final class Broken {
                static int value = initialize();
                static int initialize() { Counts.broken++; throw null; }
            }
            final class Cell {
                private int value;
                Cell(int value) { this.value = value; }
                int read() { return value; }
            }
            """;

    private BridgeLibraryNativeTests() {}

    static void libraries() throws Exception {
        var pipeline = new CompilerPipeline(UnfreedMode.OFF);
        var analyzed = pipeline.analyzeForBridge(List.of(SourceFile.of("test/LibraryNative.iron", SOURCE)));
        check(analyzed.valid(), analyzed.diagnostics().toString());
        var program = analyzed.program().orElseThrow();
        var roots = BridgeRootSet.resolve(program, program.functions().stream()
                .filter(function -> function.ownerClass().equals("librarynative.Engine")
                        && Set.of("add", "fail", "initialized", "broken", "attempts", "allocate").contains(function.sourceName()))
                .map(BridgeCallableId::of).toList());
        var module = BridgeEntryModule.scalars(analyzed, roots);
        var compiled = pipeline.compileBridge(analyzed, roots);
        check(compiled.valid(), compiled.diagnostics().toString());
        check(compiled.program().orElseThrow().functions().stream().noneMatch(function -> function.sourceName().equals("unreachable")),
                "production library retained unused function");
        var discovery = LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        Path base = Path.of("workspace/java-bridge/evidence/p1/libraries").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Files.writeString(directory.resolve("LibraryNative.iron"), SOURCE);
        Path llvm = directory.resolve("program.ll");
        Files.writeString(llvm, compiled.llvmIr().orElseThrow());
        Path adapter = directory.resolve("adapter.c");
        Files.writeString(adapter, BridgeEntryTests.adapter(module, "BridgeLibraryConsumer"));
        Path consumer = directory.resolve("BridgeLibraryConsumer.java");
        Files.writeString(consumer, CONSUMER);
        Path javaHome = Path.of(System.getProperty("java.home"));
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", consumer.toString()), "javac");
        Files.writeString(directory.resolve("environment.txt"), "java=" + javaHome + "\nversion="
                + System.getProperty("java.runtime.version") + "\nos=" + System.getProperty("os.name")
                + "\narch=" + System.getProperty("os.arch") + "\nllvm=" + toolchain.version() + "\n");
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path object = directory.resolve("adapter-" + level + ".o");
            BridgeEntryTests.run(directory, List.of(toolchain.clang().toString(), "-std=c11", "-fPIC", "-fvisibility=hidden",
                    level.clangArgument(), "-I" + javaHome.resolve("include"),
                    "-I" + javaHome.resolve(mac ? "include/darwin" : "include/linux"),
                    "-I" + Path.of("runtime/include").toAbsolutePath(), "-c", adapter.toString(), "-o", object.toString()), "adapter-" + level);
            Path image = directory.resolve("library-" + level + (mac ? ".dylib" : ".so"));
            var linked = new NativeBackend().link(toolchain, llvm, image, level,
                    NativeLinkRequirements.from(compiled.program().orElseThrow()), TargetMachine.DEFAULT,
                    NativeOutputKind.SHARED_LIBRARY, List.of(object));
            Files.writeString(directory.resolve("link-" + level + ".log"), linked.output());
            check(linked.success(), linked.output());
            BridgeEntryTests.run(directory, List.of(toolchain.home().resolve("bin/llvm-objdump").toString(),
                    "--disassemble", "--no-show-raw-insn", image.toString()), "disassembly-" + level);
            Files.writeString(directory.resolve("sha256-" + level + ".txt"), HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image))) + "  " + image.getFileName() + "\n");
            for (String budget : List.of("normal", "0", "1")) {
                var command = List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", directory.toString(),
                        "BridgeLibraryConsumer", image.toString(), budget);
                String name = "consumer-" + level + "-" + budget;
                Files.writeString(directory.resolve(name + ".command.txt"), String.join("\n", command)
                        + "\nIRONWOOD_ALLOCATION_LIMIT=" + budget + "\n");
                var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve(name + ".log").toFile());
                if (budget.equals("normal")) builder.environment().remove("IRONWOOD_ALLOCATION_LIMIT");
                else builder.environment().put("IRONWOOD_ALLOCATION_LIMIT", budget);
                var process = builder.start();
                if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("child timed out"); }
                Files.writeString(directory.resolve(name + ".exit.txt"), process.exitValue() + "\n");
                var output = Files.readString(directory.resolve(name + ".log"));
                check(process.exitValue() == 0 && output.equals("library-ok:" + budget + "\n"), name + ": " + output);
            }
            if (!mac) missingSymbol(directory, javaHome, toolchain, llvm, level);
        }
        System.out.println("bridge production library evidence: " + directory);
    }

    private static void missingSymbol(Path directory, Path javaHome, LlvmToolchain toolchain,
            Path llvm, OptimizationLevel level) throws Exception {
        Path source = directory.resolve("missing.c");
        Files.writeString(source, """
                // SPDX-License-Identifier: MIT OR Apache-2.0
                #include <jni.h>
                #include <stdio.h>
                extern int ironwood_bridge_required_missing_symbol(void);
                JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
                    (void)vm; (void)reserved;
                    fputs("UNEXPECTED_NATIVE_ENTRY\\n", stderr);
                    return ironwood_bridge_required_missing_symbol();
                }
                """);
        Path object = directory.resolve("missing-" + level + ".o");
        BridgeEntryTests.run(directory, List.of(toolchain.clang().toString(), "-std=c11", "-fPIC", "-fvisibility=hidden",
                level.clangArgument(), "-I" + javaHome.resolve("include"), "-I" + javaHome.resolve("include/linux"),
                "-c", source.toString(), "-o", object.toString()), "missing-adapter-" + level);
        Path image = directory.resolve("missing-" + level + ".so");
        var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
        Files.writeString(directory.resolve("missing-link-" + level + ".log"), linked.output());
        check(linked.success(), linked.output());
        Path consumer = directory.resolve("BridgeMissingSymbolConsumer.java");
        Files.writeString(consumer, """
                // SPDX-License-Identifier: MIT OR Apache-2.0
                public final class BridgeMissingSymbolConsumer {
                    public static void main(String[] args) {
                        try { System.load(args[0]); throw new AssertionError("unexpected load success"); }
                        catch (UnsatisfiedLinkError expected) {
                            if (!expected.getMessage().contains("ironwood_bridge_required_missing_symbol")) {
                                throw new AssertionError(expected);
                            }
                        }
                        System.out.println("missing-symbol-contained");
                    }
                }
                """);
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", consumer.toString()),
                "missing-javac-" + level);
        var output = BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp",
                directory.toString(), "BridgeMissingSymbolConsumer", image.toString()), "missing-consumer-" + level);
        check(output.equals("missing-symbol-contained\n"), "native body entered or missing relocation was not contained: " + output);
    }

    private static final String CONSUMER = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            public final class BridgeLibraryConsumer {
                private static native int add(int a, int b);
                private static native int fail();
                private static native int initialized();
                private static native int broken();
                private static native int attempts();
                private static native int allocate();
                private static native long allocations();
                private static native int handwrittenAdd(int a, int b);
                private static void failure(Runnable action, String type) {
                    try { action.run(); throw new AssertionError("missing failure: " + type); }
                    catch (RuntimeException caught) {
                        if (!caught.getMessage().contains(type)) throw new AssertionError(caught.getMessage());
                    }
                    if (add(17, 25) != 42) throw new AssertionError("post-catch call");
                }
                public static void main(String[] arguments) {
                    System.load(arguments[0]);
                    boolean zero = arguments[1].equals("0");
                    boolean limited = !arguments[1].equals("normal");
                    if (attempts() != 0 || allocations() != 0) throw new AssertionError("eager source initialization");
                    for (int index = 0; index < 3; index++) {
                        if (zero) failure(() -> initialized(), "OutOfMemoryError");
                        else if (initialized() != 37) throw new AssertionError("initialized value");
                        if (attempts() != 100 || allocations() != (zero ? 0 : 1)) throw new AssertionError("initializer repeated");
                    }
                    for (int index = 0; index < 3; index++) {
                        failure(() -> broken(), limited ? "OutOfMemoryError" : "NullPointerException");
                        if (attempts() != 101) throw new AssertionError("failed initializer repeated");
                        failure(() -> fail(), limited ? "OutOfMemoryError" : "NullPointerException");
                        if (limited) failure(() -> allocate(), "OutOfMemoryError");
                        else if (allocate() != 73) throw new AssertionError("allocation result");
                    }
                    long before = allocations();
                    for (int index = 0; index < 50000; index++) {
                        if (add(17, 25) != 42) throw new AssertionError("scalar result");
                    }
                    if (allocations() != before) throw new AssertionError("scalar allocation");
                    System.out.println("library-ok:" + arguments[1]);
                }
            }
            """;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
