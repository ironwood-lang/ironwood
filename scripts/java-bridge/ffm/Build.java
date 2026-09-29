// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;
import java.nio.file.*;
import java.util.*;

/** Experiment-only bindings, never included in producer output or an IDK. */
public final class Build {
    private record Method(String name, String result, String parameters) {}
    private static final List<Method> METHODS = List.of(
            new Method("add", "int", "int,int"), new Method("z", "boolean", "boolean"),
            new Method("b", "byte", "byte"), new Method("s", "short", "short"),
            new Method("c", "char", "char"), new Method("i", "int", "int"),
            new Method("j", "long", "long"), new Method("f", "float", "float"),
            new Method("d", "double", "double"), new Method("noop", "void", ""),
            new Method("fail", "int", ""), new Method("initialized", "int", ""),
            new Method("broken", "int", ""), new Method("allocate", "int", ""),
            new Method("recurse", "long", "int,long,boolean"), new Method("ticks", "int", ""));
    private static final Map<String, String> FIELDS = Map.of("boolean", "boolean", "byte", "byte",
            "short", "short_integer", "char", "character", "int", "integer", "long", "wide",
            "float", "single", "double", "real");
    private static final Map<String, String> SIG = Map.of("boolean", "Z", "byte", "B", "short", "S",
            "char", "C", "int", "I", "long", "J", "float", "F", "double", "D", "void", "V");
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(out);
        var pipeline = new CompilerPipeline(UnfreedMode.OFF);
        var artifact = pipeline.analyzeForBridge(List.of(SourceFile.of("ffm/Probe.iron",
                Files.readString(Path.of("scripts/java-bridge/ffm/Probe.iron")))));
        if (!artifact.valid()) throw new AssertionError(artifact.diagnostics());
        var roots = BridgeRootSet.resolve(artifact.program().orElseThrow(), artifact.program().orElseThrow().functions()
                .stream().filter(f -> f.ownerClass().equals("ffmexperiment.Probe")
                        && METHODS.stream().anyMatch(m -> m.name().equals(f.sourceName())))
                .map(BridgeCallableId::of).toList());
        var module = BridgeEntryModule.scalars(artifact, roots);
        var compiled = pipeline.compileBridge(artifact, roots);
        if (!compiled.valid()) throw new AssertionError(compiled.diagnostics());
        Path llvm = out.resolve("program.ll");
        Files.writeString(llvm, compiled.llvmIr().orElseThrow());
        var c = new StringBuilder(Files.readString(Path.of("scripts/java-bridge/ffm/adapter-prefix.c")));
        var registration = new StringBuilder();
        var addresses = new StringBuilder();
        var jni = new StringBuilder("// SPDX-License-Identifier: MIT OR Apache-2.0\npackage probe;\npublic final class JniProbe {\n");
        var ffm = new StringBuilder();
        var table = new StringBuilder();
        for (int index = 0; index < METHODS.size(); index++) {
            var m = METHODS.get(index);
            String symbol = module.entries().stream().filter(e -> e.root().callable().name().equals(m.name()))
                    .findFirst().orElseThrow().function().linkageName();
            String[] params = m.parameters().isEmpty() ? new String[0] : m.parameters().split(",");
            var decl = new StringJoiner(", ");
            var jdecl = new StringJoiner(", ");
            var values = new StringBuilder();
            var layouts = new StringBuilder();
            var signature = new StringBuilder("(");
            for (int n = 0; n < params.length; n++) {
                decl.add("j" + params[n] + " a" + n);
                jdecl.add(params[n] + " a" + n);
                values.append("a").append(n).append(", ");
                layouts.append("JAVA_").append(params[n].toUpperCase(Locale.ROOT)).append(", ");
                signature.append(SIG.get(params[n]));
            }
            signature.append(')').append(SIG.get(m.result()));
            String prefix = params.length == 0 ? "" : decl + ", ";
            c.append("extern int32_t ").append(symbol).append('(').append(prefix).append("int64_t);\n")
                    .append("static int32_t entry_").append(m.name()).append('(').append(prefix)
                    .append("struct ironwood_bridge_result *frame) { return ").append(symbol).append('(')
                    .append(values).append("(int64_t)(uintptr_t)frame); }\n")
                    .append("static ").append(m.result().equals("void") ? "void" : "j" + m.result())
                    .append(" jni_").append(m.name()).append("(JNIEnv *env, jclass type")
                    .append(params.length == 0 ? "" : ", " + decl).append(") {\n")
                    .append(" (void)type; struct ironwood_bridge_result frame; int32_t status = entry_")
                    .append(m.name()).append('(').append(values).append("&frame);\n")
                    .append(" if (status != 0) { failure(env, &frame, status); ")
                    .append(m.result().equals("void") ? "return;" : "return 0;").append(" }\n")
                    .append(m.result().equals("void") ? "" : " return frame.value." + FIELDS.get(m.result()) + ";\n")
                    .append("}\n");
            registration.append(" {\"").append(m.name()).append("\", \"").append(signature)
                    .append("\", (void *)jni_").append(m.name()).append("},\n");
            addresses.append(" case ").append(index).append(": return (jlong)(uintptr_t)entry_").append(m.name()).append(";\n");
            jni.append(" public static native ").append(m.result()).append(' ').append(m.name()).append('(').append(jdecl).append(");\n");
            ffm.append(" private static final MethodHandle H_").append(m.name()).append(" = handle(").append(index)
                    .append(", FunctionDescriptor.of(JAVA_INT, ").append(layouts).append("ADDRESS));\n")
                    .append(" public static ").append(m.result()).append(' ').append(m.name()).append('(').append(jdecl)
                    .append(") throws Throwable {\n  int status = (int) H_").append(m.name()).append(".invokeExact(")
                    .append(values).append("FRAME); check(status);\n")
                    .append(m.result().equals("void") ? "" : "  return FRAME.get(JAVA_" + m.result().toUpperCase(Locale.ROOT) + ", 0);\n")
                    .append(" }\n");
            table.append(index).append('\t').append(m.name()).append('\t').append(symbol).append('\n');
        }
        c.append("static jlong address(JNIEnv *env, jclass type, jint id) { (void)type; switch(id) {\n")
                .append(addresses).append(" default: { jclass e = (*env)->FindClass(env, \"java/lang/IllegalArgumentException\");\n")
                .append(" if (e != NULL) { (*env)->ThrowNew(env, e, \"unknown experiment entry\"); (*env)->DeleteLocalRef(env, e); } return 0; } } }\n")
                .append("JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {\n")
                .append(" (void)reserved; JNIEnv *env; if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;\n")
                .append(" jclass type = (*env)->FindClass(env, \"probe/JniProbe\"); if (!type) return JNI_ERR;\n JNINativeMethod methods[] = {\n")
                .append(registration).append(" {\"address\", \"(I)J\", (void *)address}, {\"allocations\", \"()J\", (void *)allocations},\n")
                .append(" {\"failure\", \"(JI)V\", (void *)java_failure} };\n")
                .append(" if ((*env)->RegisterNatives(env, type, methods, sizeof(methods)/sizeof(methods[0]))) return JNI_ERR;\n")
                .append(" (*env)->DeleteLocalRef(env, type); ironwood_bridge_bootstrap(); return JNI_VERSION_1_8; }\n");
        jni.append(Files.readString(Path.of("scripts/java-bridge/ffm/jni-tail.java.inc")));
        Files.createDirectories(out.resolve("src/probe"));
        Files.writeString(out.resolve("src/probe/JniProbe.java"), jni);
        Files.writeString(out.resolve("src/probe/FfmProbe.java"), Files.readString(Path.of("scripts/java-bridge/ffm/ffm-template.java.inc"))
                .replace("// GENERATED_METHODS", ffm));
        Files.writeString(out.resolve("entries.tsv"), table);
        Path adapter = out.resolve("adapter.c");
        Files.writeString(adapter, c);
        var discovery = LlvmToolchain.discover(null);
        if (!discovery.successful()) throw new AssertionError(discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        Path javaHome = Path.of(System.getProperty("java.home"));
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path object = out.resolve("adapter-" + level + ".o");
            BridgeEntryTests.run(out, List.of(toolchain.clang().toString(), "-std=c11", "-fPIC", "-fvisibility=hidden", level.clangArgument(),
                    "-Wall", "-Wextra", "-Werror", "-I" + javaHome.resolve("include"),
                    "-I" + javaHome.resolve(mac ? "include/darwin" : "include/linux"),
                    "-I" + Path.of("runtime/include").toAbsolutePath(), "-c", adapter.toString(), "-o", object.toString()), "adapter-" + level);
            Path image = out.resolve("probe-" + level + (mac ? ".dylib" : ".so"));
            var linked = new NativeBackend().link(toolchain, llvm, image, level,
                    NativeLinkRequirements.from(compiled.program().orElseThrow()), TargetMachine.DEFAULT,
                    NativeOutputKind.SHARED_LIBRARY, List.of(object));
            Files.writeString(out.resolve("link-" + level + ".log"), linked.output());
            if (!linked.success()) throw new AssertionError(linked.output());
            BridgeEntryTests.run(out, List.of(toolchain.home().resolve("bin/llvm-objdump").toString(),
                    "--disassemble", "--no-show-raw-insn", image.toString()), "disassembly-" + level);
            BridgeEntryTests.run(out, List.of(toolchain.home().resolve("bin/llvm-nm").toString(),
                    "--extern-only", "--defined-only", image.toString()), "symbols-" + level);
        }
    }
}
