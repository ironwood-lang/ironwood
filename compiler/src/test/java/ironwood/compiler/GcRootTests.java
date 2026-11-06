// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Source-derived development closure; no handwritten LLVM program. */
public final class GcRootTests {
    static final String NAME = "GC3 collection effects and explicit closure fail closed";
    private static final SourceSpan SPAN = SourceSpan.at(new SourcePosition(0, 1, 1));
    private static final IrType NODE = IrType.reference("Node");
    private GcRootTests() {}

    private static IrProgram source() throws Exception {
        var source = SourceFile.of("gc3_closure.iron", Files.readString(Path.of("integration-tests/cases/gc3_closure.iron")));
        return source(source);
    }

    private static IrProgram source(SourceFile source) {
        var artifact = new CompilerPipeline().analyzeForBridge(List.of(source));
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact.program().orElseThrow();
    }

    static IrProgram closure() throws Exception {
        return closure(source());
    }

    private static IrProgram closure(IrProgram input) {
        var functions = new ArrayList<IrFunction>();
        for (var function : input.functions()) {
            boolean constructor = function.kind() == IrCallableKind.CONSTRUCTOR
                    && Set.of("Node", "ironwood.lang.Object").contains(function.ownerClass());
            if (!constructor && (!Set.of("Main", "Node", "Init").contains(function.ownerClass()) || function.kind() != IrCallableKind.METHOD)) continue;
            if (function.ownerClass().equals("Main") && function.sourceName().equals("phase")) {
                function = new IrFunction(function.ownerClass(), function.sourceName(), function.linkageName(), function.returnType(),
                        function.parameters(), List.of(new IrBasicBlock("entry", List.of(new IrGcCollectInstruction(function.sourceSpan())),
                        new IrReturnTerminator(Optional.empty(), SPAN), SPAN)), function.sourceSpan(), function.sourceFileName(), function.kind());
            }
            functions.add(function);
        }
        var names = functions.stream().map(IrFunction::linkageName).collect(Collectors.toSet());
        // Explicit standalone layout selection. None of these classes is allocated
        // through a legacy constructor/failure path; only their physical GC2 maps
        // and the selected dispatch implementations enter this isolated image.
        var classes = input.classes().stream().filter(type -> Set.of("Main", "Node", "Init", "Step", "ironwood.lang.Object").contains(type.name()))
                .map(type -> new IrClass(type.name(), type.kind(), type.superclass(), type.interfaces(), type.fields(), type.typeId(),
                        type.typeMembership(), type.dispatchEntries().stream().filter(entry -> names.contains(entry.targetLinkageName())).toList(), type.sourceSpan())).toList();
        var node = classes.stream().filter(type -> type.name().equals("Node")).findFirst().orElseThrow();
        var field = node.fields().stream().filter(f -> f.name().equals("value")).findFirst().orElseThrow();
        IrValueReference value = value(0, NODE), result = value(1, NODE), slot = value(2, IrType.EXCEPTION);
        functions.add(function("stack", NODE, List.of(value), List.of(new IrGcRootSlotInstruction(slot, value, SPAN),
                new IrGcCollectInstruction(SPAN), new IrGcRootLoadInstruction(result, slot, SPAN)), result));
        var loadedNumber = value(3, IrType.I64);
        functions.add(function("stack_clear", IrType.I64, List.of(value), List.of(new IrGcRootSlotInstruction(slot, value, SPAN),
                new IrGcCollectInstruction(SPAN), new IrGcRootLoadInstruction(result, slot, SPAN),
                new IrFieldLoadInstruction(loadedNumber, result, field, SPAN), new IrGcRootSlotInstruction(slot, new IrNull(NODE, SPAN), SPAN),
                new IrGcCollectInstruction(SPAN)), loadedNumber));
        var allocated = value(0, NODE);
        functions.add(function("allocate", NODE, List.of(), List.of(new IrAllocateInstruction(allocated, "Node", SPAN),
                new IrFieldStoreInstruction(allocated, field, new IrConstant(IrType.I64, 123L, SPAN), SPAN),
                new IrGcCollectInstruction(SPAN)), allocated));
        var stepSlot = node.dispatchEntries().stream().filter(entry -> entry.slot().methodName().equals("step")).findFirst().orElseThrow().slot();
        functions.add(function("dispatch", NODE, List.of(value), List.of(new IrInterfaceCallInstruction(Optional.of(result), "Step", stepSlot,
                NODE, List.of(value), SPAN)), result));
        var address = value(1, NODE);
        var number = value(2, IrType.I64);
        functions.add(function("derived", IrType.I64, List.of(value), List.of(new IrGcProjectionInstruction(address, value, field, SPAN),
                new IrGcCollectInstruction(SPAN), new IrGcReadProjectionInstruction(number, address, SPAN)), number));
        functions.add(new IrFunction("Gc3", "exception", "gc3_exception", NODE, List.of(new IrParameter("value", value, SPAN)), List.of(
                new IrBasicBlock("entry", List.of(), new IrInvokeTerminator(new IrGcTestExceptionInstruction(
                        IrGcTestExceptionInstruction.Operation.RAISE_SCALAR, SPAN), "unexpected", "cleanup", SPAN), SPAN),
                new IrBasicBlock("unexpected", List.of(), new IrUnreachable(SPAN), SPAN),
                new IrBasicBlock("cleanup", List.of(new IrGcTestExceptionInstruction(IrGcTestExceptionInstruction.Operation.CATCH_SCALAR, SPAN),
                        new IrGcCollectInstruction(SPAN)), new IrReturnTerminator(Optional.of(value), SPAN), SPAN)), SPAN,
                "gc3-native-exception.iron", IrCallableKind.METHOD));
        var statics = input.staticFields().stream().filter(f -> Set.of("Main", "Init").contains(f.ownerClass())).toList();
        // Initialization prerequisites are preserved, including types without code.
        var initializations = input.typeInitializations().stream().filter(i -> Set.of("Main", "Init", "Node", "Step", "ironwood.lang.Object").contains(i.typeName())).toList();
        // Keep the source class initializer when present. It has no legacy owner.
        input.functions().stream().filter(f -> f.ownerClass().equals("Init") && f.kind() == IrCallableKind.CLASS_INITIALIZER).forEach(functions::add);
        Set<String> exports = functions.stream().map(IrFunction::linkageName).collect(Collectors.toSet());
        return new IrProgram("gc3_closure.iron", classes, statics, initializations, List.of(), List.of(), input.dispatchSlots(), functions,
                Optional.empty(), Optional.empty(), exports);
    }

    private static IrValueReference value(int id, IrType type) { return new IrValueReference(id, type, SPAN); }
    private static IrFunction function(String name, IrType type, List<IrValueReference> parameters, List<IrInstruction> instructions, IrOperand result) {
        return new IrFunction("Gc3", name, "gc3_" + name, type, parameters.stream().map(p -> new IrParameter("p" + p.id(), p, SPAN)).toList(),
                List.of(new IrBasicBlock("entry", instructions, new IrReturnTerminator(Optional.ofNullable(result), SPAN), SPAN)), SPAN,
                "gc3-generated.iron", IrCallableKind.METHOD);
    }

    static void proofs() throws Exception {
        var program = closure();
        var effects = GcExperimentalClosure.validate(program);
        for (String name : List.of("phase", "identity", "merge", "loop", "pending", "pendingYield", "capture", "initialize")) {
            check(effects.function("ironwood.Main." + name).mayCollect(), "lost collection route: " + name);
        }
        check(effects.function("gc3_dispatch").mayCollect(), "interface route lost");
        check(effects.function("ironwood.Node.<init>").mayCollect(), "constructor collection lost");
        check(effects.function("gc3_exception").mayCollect(), "exceptional CFG collection lost");
        check(!effects.function("ironwood.Main.scalar").mayCollect(), "allocation-independent leaf graph polluted");
        check(effects.function("missing.native.callback").unknown(), "unknown native body admitted");
        check(!effects.instruction(new IrAllocateInstruction(value(10, NODE), "Node", SPAN)).mayCollect(), "allocation schedules collection");
        check(effects.initialization("Init").mayCollect(), "initializer collection lost");
        var transformed = NativeLinkTransformation.apply(program).program();
        var finalEffects = GcExperimentalClosure.validate(transformed);
        check(finalEffects.function("ironwood.Main.pending").mayCollect(), "cleanup effect lost after native transforms");
        var renamed = new IrCfgRenamer(v -> new IrValueReference(v.id() + 100, v.type(), v.sourceSpan()), l -> "copy." + l)
                .block(program.functions().stream().filter(f -> f.linkageName().equals("gc3_stack")).findFirst().orElseThrow().blocks().getFirst());
        check(((IrGcRootSlotInstruction)renamed.instructions().getFirst()).slot().id() == 102, "native stack owner not cloned");
        check(new LlvmEmitter().emit(sourceUnchecked()).contains("@iw_gc_root_abi = hidden constant i32 2"),
                "normal source emission lost integrated root ABI");
        denied(() -> GcExperimentalClosure.validate(sourceUnchecked()));
        String output = new LlvmEmitter().emitGcExperiment(transformed);
        check(output.contains("ptr addrspace(1)") && output.contains("\"deopt\"(i64 1230455603"), "stack/SSA root descriptions missing");
        check(!output.contains("ironwood_deallocate") && !output.contains("@ironwood_allocate("), "legacy allocation domain leaked");
        // Nearby unsafe controls are introduced after frontend proof, so admission
        // must independently reject them rather than relying on source diagnostics.
        for (var instruction : List.<IrInstruction>of(new IrCallInstruction(Optional.empty(), "ironwood_deallocate", IrType.VOID, List.of(value(0, NODE)), SPAN),
                new IrCallInstruction(Optional.empty(), "unknown", IrType.VOID, List.of(), SPAN))) {
            var unsafe = function("unsafe", IrType.VOID, List.of(value(0, NODE)), List.of(instruction), null);
            var functions = new ArrayList<>(program.functions()); functions.add(unsafe);
            denied(() -> GcExperimentalClosure.validate(copy(program, functions)));
        }
        artifactParity(program);
        cloneEffects();
        closureEffects();
        check(!new NativeBackend().compileGcExperiment(null, program, new NativeTarget("aarch64-unknown-linux-gnu", "e"),
                Path.of("workspace/gc3-unqualified-refused"), OptimizationLevel.O3).success(), "unqualified Linux arm64 admitted");
    }

    private static void closureEffects() {
        var artifact = new CompilerPipeline().analyzeForBridge(List.of(SourceFile.of("gc3-capture-effects.iron", """
                class ClosureEffects {

                    static void phase() {

                    }

                    static Object run(Object value) {

                        class Local {

                            Object get() {

                                phase();
                                return value;
                            }
                        }
                        Local local = new Local();
                        return local.get();
                    }
                }
                """)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var input = artifact.program().orElseThrow();
        var functions = input.functions().stream().map(function -> {
            if (!function.linkageName().equals("ironwood.ClosureEffects.phase")) return function;
            return new IrFunction(function.ownerClass(), function.sourceName(), function.linkageName(), function.returnType(), function.parameters(),
                    List.of(new IrBasicBlock("entry", List.of(new IrGcCollectInstruction(function.sourceSpan())),
                            new IrReturnTerminator(Optional.empty(), function.sourceSpan()), function.sourceSpan())),
                    function.sourceSpan(), function.sourceFileName(), function.kind());
        }).toList();
        var captured = input.classes().stream().filter(type -> type.fields().stream()
                .anyMatch(field -> field.name().startsWith("<capture:"))).findFirst().orElseThrow();
        var getter = functions.stream().filter(f -> f.ownerClass().equals(captured.name()) && f.sourceName().equals("get")).findFirst().orElseThrow();
        var effects = GcEffects.analyze(copy(input, functions));
        check(effects.function(getter.linkageName()).mayCollect() && !effects.function(getter.linkageName()).unknown(),
                "lowered captured closure lost its collecting call");
        check(effects.function("ironwood.ClosureEffects.run").mayCollect(), "closure call did not propagate collection");
    }

    private static void cloneEffects() throws Exception {
        var input = new CompilerPipeline().analyzeForBridge(List.of(SourceFile.of("enum_argument_specialization.iron",
                Files.readString(Path.of("integration-tests/cases/enum_argument_specialization.iron"))))).program().orElseThrow();
        var functions = input.functions().stream().map(function -> {
            if (!function.ownerClass().equals("Main") || !function.sourceName().equals("score")) return function;
            var blocks = new ArrayList<>(function.blocks());
            var first = blocks.getFirst();
            var instructions = new ArrayList<IrInstruction>(); instructions.add(new IrGcCollectInstruction(first.sourceSpan()));
            instructions.addAll(first.instructions());
            blocks.set(0, new IrBasicBlock(first.label(), instructions, first.terminator(), first.sourceSpan()));
            return new IrFunction(function.ownerClass(), function.sourceName(), function.linkageName(), function.returnType(),
                    function.parameters(), blocks, function.sourceSpan(), function.sourceFileName(), function.kind());
        }).toList();
        var collecting = copy(input, functions);
        // GC5 no longer synthesizes managed destruction metadata for this
        // ordinary source control. Final-link admission must now succeed.
        ironwood.compiler.backend.GcManagedHeapVerifier.verify(NativeLinkTransformation.apply(collecting).program());
        var transformed = ClosedWorldPruner.prune(NativeLinkPipeline.optimize(collecting));
        var effects = GcEffects.analyze(transformed);
        var clones = transformed.functions().stream().filter(f -> f.linkageName().contains(".$enumarg.")).toList();
        check(!clones.isEmpty(), "did not exercise actual enum argument specialization");
        for (var function : clones) {
            check(function.blocks().stream().flatMap(b -> b.instructions().stream()).anyMatch(IrGcCollectInstruction.class::isInstance)
                    && effects.function(function.linkageName()).mayCollect(), "specialized clone lost collection IR/effect");
        }
    }

    private static void artifactParity(IrProgram program) throws Exception {
        Files.createDirectories(Path.of("workspace"));
        Path temporary = Files.createTempDirectory(Path.of("workspace"), "gc3-artifacts-");
        var input = SourceFile.of("gc3_closure.iron", Files.readString(Path.of("integration-tests/cases/gc3_closure.iron")));
        var unit = SourceParser.parse(input).unit().orElseThrow();
        Path loose = temporary.resolve("Main.ironclass"), archive = temporary.resolve("closure.ironjar");
        IronClass.write(loose, unit, "Main");
        IronJar.create(archive, List.of(loose));
        var sources = List.of(IronClass.read(loose).source("Main").orElseThrow(), IronJar.read(archive).source("Main").orElseThrow());
        var expected = new LlvmEmitter().emitGcExperiment(NativeLinkTransformation.apply(program).program());
        for (var loaded : sources) {
            var reconstructed = closure(source(SourceFile.of("gc3_closure.iron", loaded.content())));
            check(new LlvmEmitter().emitGcExperiment(NativeLinkTransformation.apply(reconstructed).program()).equals(expected),
                    "GC3 source/class/archive effect or root lowering differs");
        }
    }

    private static IrProgram sourceUnchecked() {
        try { return source(); } catch (Exception error) { throw new AssertionError(error); }
    }

    private static IrProgram copy(IrProgram p, List<IrFunction> functions) {
        return new IrProgram(p.moduleName(), p.classes(), p.staticFields(), p.typeInitializations(), p.arrayTypes(), p.stringConstants(),
                p.dispatchSlots(), functions, p.entryPoint(), p.allocationFailure(), p.exportRoots());
    }

    public static void main(String[] arguments) throws Exception {
        proofs();
        Path out = Path.of(arguments[0]); Files.createDirectories(out);
        var program = NativeLinkTransformation.apply(closure()).program();
        var toolchain = LlvmToolchain.discover(Path.of(arguments[1])).toolchain().orElseThrow();
        var target = NativeTarget.fromLlvm(Files.readString(Path.of(arguments[2])));
        for (var level : OptimizationLevel.values()) {
            var result = new NativeBackend().compileGcExperiment(toolchain, program, target, out.resolve(level.name()), level);
            check(result.success(), result.output());
        }
        var layouts = IrAllocationLayout.of(program);
        StringBuilder header = new StringBuilder("// SPDX-License-Identifier: MIT OR Apache-2.0\n#define IW_GC_TYPE_COUNT " + layouts.size() + "\n");
        for (int index = 0; index < layouts.size(); index++) {
            header.append("extern const struct iw_gc_layout iw_llvm_layout_").append(index).append(";\n");
            if (layouts.get(index).name().equals("Node")) header.append("#define IW_GC_NODE_TYPE ").append(index).append('\n');
        }
        header.append("static const struct iw_gc_layout *const layouts[] = {");
        for (int index = 0; index < layouts.size(); index++) header.append("&iw_llvm_layout_").append(index).append(',');
        header.append("};\n");
        Files.writeString(out.resolve("layouts.h"), header);
        Files.writeString(out.resolve("effects.txt"), new java.util.TreeMap<>(GcEffects.analyze(program).functions()).toString());
    }

    private static void denied(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("GC3 unsafe closure admitted");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
