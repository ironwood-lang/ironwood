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

/** Exercises the protected root ABI before the separate host count-commit tests. */
final class BridgeRootNativeTests {
    private BridgeRootNativeTests() {}

    static void payloads() throws Exception {
        boolean fault = "1".equals(System.getenv("IRONWOOD_BRIDGE_ROOT_SNAPSHOT_FAULT"));
        String source = BridgeRootEntryTests.SOURCE.replace("Item() {}", "Item(int number) { this.number = number; }")
                .replace("Holder alias()", "int value() { return first == null ? -1 : first.number; } Holder alias()");
        var artifact = BridgeRootRetentionTests.artifact(source, UnfreedMode.OFF);
        var methods = new java.util.HashSet<>(BridgeRootEntryTests.METHODS);
        methods.add("value");
        var module = BridgeEntryModule.rootObjects(artifact, BridgeRootRetentionTests.roots(artifact, methods, BridgeRootEntryTests.TYPES));
        Path base = Path.of("workspace/java-bridge/evidence/p0b/root-payloads").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path llvm = directory.resolve("program.ll");
        Files.writeString(llvm, new LlvmEmitter().emit(module));
        Files.writeString(directory.resolve("Roots.iron"), source);
        Path adapter = directory.resolve("adapter.c");
        Files.writeString(adapter, adapter(module, fault));
        Files.writeString(directory.resolve("instrumentation.txt"), fault ? "test-only OOM snapshot failure and state inspection\n"
                : "unmodified production runtime\n");
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path consumer = directory.resolve("BridgeRootConsumer.java");
        Files.writeString(consumer, """
                public final class BridgeRootConsumer {
                    static native void exercise(boolean limited);
                    public static void main(String[] args) {
                        System.load(args[0]); exercise(args[1].equals("limited"));
                        System.out.println("root-payloads-ok:" + args[1]);
                    }
                }
                """);
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", consumer.toString()), "javac");
        var discovery = LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path object = directory.resolve("adapter-" + level + ".o");
            BridgeEntryTests.run(directory, List.of(toolchain.clang().toString(), "-std=c11", "-fPIC", "-fvisibility=hidden",
                    level.clangArgument(), "-I" + javaHome.resolve("include"), "-I" + javaHome.resolve(mac ? "include/darwin" : "include/linux"),
                    "-I" + Path.of("runtime/include").toAbsolutePath(), "-c", adapter.toString(), "-o", object.toString()), "adapter-" + level);
            Path image = directory.resolve("roots-" + level + (mac ? ".dylib" : ".so"));
            var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
            Files.writeString(directory.resolve("link-" + level + ".log"), linked.output());
            check(linked.success(), linked.output());
            Files.writeString(directory.resolve("sha256-" + level + ".txt"), HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image))) + "  " + image.getFileName() + "\n");
            for (String scenario : List.of("normal", "limited")) {
                String name = "consumer-" + level + "-" + scenario;
                var command = BridgeEntryTests.grantNativeAccess(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", directory.toString(),
                        "BridgeRootConsumer", image.toString(), scenario));
                Files.writeString(directory.resolve(name + ".command.txt"), String.join("\n", command)
                        + "\nIRONWOOD_ALLOCATION_LIMIT=" + (scenario.equals("limited") ? "5" : "unset") + "\n");
                var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve(name + ".log").toFile());
                if (scenario.equals("limited")) builder.environment().put("IRONWOOD_ALLOCATION_LIMIT", "5");
                else builder.environment().remove("IRONWOOD_ALLOCATION_LIMIT");
                var child = builder.start();
                if (!child.waitFor(90, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("root payload child timeout"); }
                String output = Files.readString(directory.resolve(name + ".log"));
                check(child.exitValue() == 0 && output.matches("root-native-benchmark:100000:2000000:[0-9]+\\nroot-payloads-ok:"
                        + scenario + "\\n"), output);
            }
        }
        System.out.println("bridge root payload evidence: " + directory);
        if (!fault) {
            Path runtimeHome = directory.resolve("fault-runtime");
            try (var paths = Files.walk(Path.of("runtime"))) {
                for (Path path : paths.toList()) {
                    Path destination = runtimeHome.resolve(path);
                    if (Files.isDirectory(path)) Files.createDirectories(destination);
                    else Files.copy(path, destination);
                }
            }
            Path runtime = runtimeHome.resolve("runtime/src/ironwood_runtime.c");
            Files.writeString(runtime, "#define ironwood_bridge_snapshot_failure ironwood_bridge_original_snapshot_failure\n"
                    + Files.readString(runtime) + FAULT_RUNTIME);
            var command = List.of(javaHome.resolve("bin/java").toString(), "-ea", "-cp", System.getProperty("java.class.path"),
                    "ironwood.compiler.CompilerTests", "--test", "Java Bridge root JNI payloads preserve mutation and unpublished rollback");
            Files.writeString(directory.resolve("fault-child.command.txt"), String.join("\n", command)
                    + "\nIRONWOOD_RUNTIME_HOME=" + runtimeHome + "\nIRONWOOD_BRIDGE_ROOT_SNAPSHOT_FAULT=1\n");
            var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve("fault-child.log").toFile());
            builder.environment().put("IRONWOOD_RUNTIME_HOME", runtimeHome.toString());
            builder.environment().put("IRONWOOD_BRIDGE_ROOT_SNAPSHOT_FAULT", "1");
            var child = builder.start();
            if (!child.waitFor(90, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("root snapshot fault child timeout"); }
            check(child.exitValue() == 0, Files.readString(directory.resolve("fault-child.log")));
        }
    }

    private static String adapter(BridgeEntryModule module, boolean fault) {
        check(module.rootRetention().orElseThrow().entries().values().stream().allMatch(entry -> entry.slots().size() <= 4),
                "fixed P0 adapter frame is too small for the proved payload");
        StringBuilder declarations = new StringBuilder();
        declarations.append("#define OOM_STATUS ").append(fault ? 2 : 1).append('\n');
        declarations.append(fault ? "extern int32_t bridge_test_state(void);\n#define STATE_OK() (bridge_test_state() == 0)\n"
                : "#define STATE_OK() 1\n");
        String body = ADAPTER;
        for (var entry : module.entries()) {
            var callable = entry.root().callable();
            String name = callable.kind() == IrCallableKind.CONSTRUCTOR
                    ? "new" + callable.owner().substring(callable.owner().lastIndexOf('.') + 1) : callable.name();
            declarations.append("extern int32_t ").append(entry.function().linkageName()).append('(');
            for (var parameter : entry.function().parameters()) declarations.append(cType(parameter.value().type())).append(", ");
            declarations.setLength(declarations.length() - 2);
            declarations.append(");\n#define call_").append(name).append(' ').append(entry.function().linkageName()).append('\n');
            body = body.replace(name + "(", "call_" + name + "(");
            var slots = module.rootRetention().orElseThrow().entries().get(callable).slots();
            for (int index = 0; index < slots.size(); index++) {
                var slot = slots.get(index);
                body = body.replace("${" + name + ":" + slot.holderInput() + ":" + slot.field().name() + "}", Integer.toString(index));
            }
        }
        for (var destruction : module.destructions()) {
            String type = destruction.contract().type().referenceName();
            declarations.append("extern void ").append(destruction.function().linkageName()).append("(void *);\n#define free")
                    .append(type.substring(type.lastIndexOf('.') + 1)).append(' ').append(destruction.function().linkageName()).append('\n');
        }
        check(!body.contains("${"), "unresolved proved slot mapping");
        return "#include <jni.h>\n#include <stdint.h>\n#include <string.h>\n#include <stdio.h>\n#include \"ironwood_bridge.h\"\n"
                + declarations + body;
    }

    private static String cType(IrType type) {
        if (type.isReference()) return "void *";
        if (type.equals(IrType.I8)) return "uint8_t";
        if (type.equals(IrType.I32)) return "int32_t";
        if (type.equals(IrType.I64)) return "int64_t";
        throw new AssertionError(type);
    }

    private static final String ADAPTER = """
            extern void ironwood_bridge_bootstrap(void);
            struct frame { struct ironwood_bridge_result result; struct ironwood_bridge_slot slots[4]; };
            #define ADDRESS(f) ((int64_t)(uintptr_t)&(f))
            #define CHECK(condition) do { if (!(condition)) { jclass error = (*env)->FindClass(env, "java/lang/AssertionError"); \
                if (error != NULL) { (*env)->ThrowNew(env, error, #condition); (*env)->DeleteLocalRef(env, error); } return; } } while (0)
            JNIEXPORT void JNICALL Java_BridgeRootConsumer_exercise(JNIEnv *env, jclass type, jboolean limited) {
                (void)type; ironwood_bridge_bootstrap();
                struct frame f = {0}; int64_t baseline = ironwood_live_allocation_count();
                CHECK(newItem(11, ADDRESS(f)) == 0); void *a = f.result.value.reference;
                CHECK(newHolder(NULL, a, 0, ADDRESS(f)) == 0); void *h = f.result.value.reference;
                CHECK(f.slots[${newHolder:0:first}].holder == h && f.slots[${newHolder:0:first}].value == a);
                CHECK(f.slots[${newHolder:1:first}].holder == NULL);
                CHECK(newItem(29, ADDRESS(f)) == 0); void *b = f.result.value.reference;
                int64_t live = ironwood_live_allocation_count(); CHECK(live == baseline + 4);
                if (limited) {
                    CHECK(newHolder(h, b, 0, ADDRESS(f)) == OOM_STATUS);
                    if (OOM_STATUS == 1) CHECK(strstr(f.result.failure.type_name, "OutOfMemoryError") != NULL);
                    CHECK(STATE_OK());
                    CHECK(ironwood_allocation_count() == 5 && ironwood_live_allocation_count() == live);
                    CHECK(f.result.value.reference == NULL && f.slots[${newHolder:0:first}].holder == NULL);
                    CHECK(f.slots[${newHolder:1:first}].holder == h && f.slots[${newHolder:1:first}].value == b);
                } else {
                    CHECK(setThenFail(h, b, ADDRESS(f)) == 1);
                    CHECK(strstr(f.result.failure.type_name, "NullPointerException") != NULL);
                    CHECK(f.slots[${setThenFail:0:first}].holder == h && f.slots[${setThenFail:0:first}].value == b);
                    CHECK(both(h, h, a, b, ADDRESS(f)) == 0);
                    CHECK(f.slots[${both:0:first}].holder == h && f.slots[${both:0:first}].value == b);
                    CHECK(f.slots[${both:1:first}].holder == h && f.slots[${both:1:first}].value == b);
                    CHECK(newHolder(h, a, 1, ADDRESS(f)) == 1);
                    CHECK(f.result.value.reference == NULL && f.slots[${newHolder:0:first}].holder == NULL);
                    CHECK(f.slots[${newHolder:1:first}].holder == h && f.slots[${newHolder:1:first}].value == a);
                    CHECK(other(h, NULL, a, ADDRESS(f)) == 1 && f.slots[${other:1:first}].holder == NULL);
                }
                CHECK(value(h, ADDRESS(f)) == 0 && f.result.value.integer == (limited ? 29 : 11));
                int64_t allocations = ironwood_allocation_count(), checksum = 0, start = ironwood_nano_time();
                for (int iteration = 0; iteration < 100000; iteration++) {
                    void *item = (iteration & 1) ? b : a;
                    CHECK(set(h, item, ADDRESS(f)) == 0);
                    CHECK(f.slots[${set:0:first}].holder == h && f.slots[${set:0:first}].value == item);
                    CHECK(value(h, ADDRESS(f)) == 0); checksum += f.result.value.integer;
                }
                int64_t elapsed = ironwood_nano_time() - start;
                CHECK(checksum == 2000000 && ironwood_allocation_count() == allocations);
                printf("root-native-benchmark:100000:2000000:%lld\\n", (long long)elapsed); fflush(stdout);
                CHECK(clear(h, ADDRESS(f)) == 0);
                CHECK(f.slots[${clear:0:first}].holder == h && f.slots[${clear:0:first}].value == NULL);
                CHECK(f.slots[${clear:0:second}].holder == h && f.slots[${clear:0:second}].value == NULL);
                freeHolder(h); freeItem(a); freeItem(b);
                /* The three ordinary native NPE snapshots remain live in this private P0 transport. */
                CHECK(ironwood_live_allocation_count() == baseline + (limited ? 0 : 3));
            }
            """;

    private static final String FAULT_RUNTIME = """

            /* Test-only appendage in an ignored runtime copy. */
            #undef ironwood_bridge_snapshot_failure
            int32_t bridge_test_state(void) {
                return (active_implicit_failure != NULL ? 1 : 0) | (emergency_exception_in_use ? 2 : 0);
            }
            void ironwood_bridge_snapshot_failure(const void *object, struct ironwood_bridge_result *result) {
                if (strcmp(object_type_name(object), "ironwood.lang.OutOfMemoryError") == 0) {
                    void *unexpected = ironwood_allocate(SIZE_MAX, NULL, (void *)object);
                    ironwood_deallocate(unexpected);
                    abort();
                }
                ironwood_bridge_original_snapshot_failure(object, result);
            }
            """;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
