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

/** Explicit GC4 projection, preserving ordinary source analysis and production ABI. */
public final class GcLifetimeTests {
    static final String NAME = "GC4 runtime owners require exact exception layouts and effects";
    private static final SourceSpan SPAN = SourceSpan.at(new SourcePosition(0, 1, 1));
    private static final IrType THROWABLE = IrType.reference("ironwood.lang.Throwable");
    private static final IrType OBJECT = IrType.reference("ironwood.lang.Object");
    private GcLifetimeTests() {}

    private static IrValueReference value(int id, IrType type) { return new IrValueReference(id, type, SPAN); }
    private static IrBasicBlock block(String label, List<IrInstruction> operations, IrTerminator exit) {
        return new IrBasicBlock(label, operations, exit, SPAN);
    }
    private static IrFunction function(String name, IrType result, List<IrValueReference> parameters, List<IrBasicBlock> blocks) {
        return new IrFunction("Gc4", name, "gc4_" + name, result,
                parameters.stream().map(p -> new IrParameter("p" + p.id(), p, SPAN)).toList(), blocks,
                SPAN, "gc4-runtime.iron", IrCallableKind.METHOD);
    }
    private static IrProgram closure() throws Exception {
        var base = GcRootTests.closure();
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("gc4_lifetimes.iron",
                Files.readString(Path.of("integration-tests/cases/gc4_lifetimes.iron")))));
        check(artifact.valid(), artifact.diagnostics().toString());
        var input = artifact.program().orElseThrow();
        var functions = new ArrayList<>(base.functions());
        for (var f : input.functions()) if (f.ownerClass().equals("Lifetime") && f.kind() == IrCallableKind.METHOD) {
            if (f.sourceName().equals("phase")) f = new IrFunction(f.ownerClass(), f.sourceName(), f.linkageName(), f.returnType(), f.parameters(),
                    List.of(block("entry", List.of(new IrGcCollectInstruction(SPAN)), new IrReturnTerminator(Optional.empty(), SPAN))),
                    f.sourceSpan(), f.sourceFileName(), f.kind());
            // Legacy IR uses EXCEPTION for both native handles and managed
            // objects. The GC4 projection separates them before effects/lowering.
            Set<Integer> nativeHandles = f.blocks().stream().flatMap(b -> b.instructions().stream())
                    .filter(IrExceptionLandingPadInstruction.class::isInstance).map(IrExceptionLandingPadInstruction.class::cast)
                    .map(p -> p.exceptionHandle().id()).collect(Collectors.toSet());
            var renamer = new IrCfgRenamer(v -> v.type().equals(IrType.EXCEPTION) && !nativeHandles.contains(v.id())
                    ? new IrValueReference(v.id(), THROWABLE, v.sourceSpan()) : v, label -> label);
            f = new IrFunction(f.ownerClass(), f.sourceName(), f.linkageName(), f.returnType(), f.parameters(),
                    f.blocks().stream().map(renamer::block).toList(), f.sourceSpan(), f.sourceFileName(), f.kind());
            functions.add(f);
        }
        var classes = new ArrayList<>(base.classes());
        var sourceThrowable = input.classes().stream().filter(c -> c.name().equals("ironwood.lang.Throwable")).findFirst().orElseThrow();
        var fields = List.of(new IrField(sourceThrowable.name(), "message", OBJECT, 0, SPAN),
                new IrField(sourceThrowable.name(), "cause", THROWABLE, 1, SPAN),
                new IrField(sourceThrowable.name(), "<gcMetadata>", OBJECT, 2, SPAN),
                new IrField(sourceThrowable.name(), "ownedMessage", OBJECT, 3, SPAN),
                new IrField(sourceThrowable.name(), "causeInitialized", IrType.I1, 4, SPAN));
        classes.add(new IrClass(sourceThrowable.name(), sourceThrowable.kind(), sourceThrowable.superclass(), List.of(), fields,
                sourceThrowable.typeId(), sourceThrowable.typeMembership(), List.of(), SPAN));
        var npe = input.classes().stream().filter(c -> c.name().equals("ironwood.lang.NullPointerException")).findFirst().orElseThrow();
        for (String name : List.of("ironwood.lang.Exception", "ironwood.lang.RuntimeException", npe.name())) {
            var projected = input.classes().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
            classes.add(new IrClass(projected.name(), projected.kind(), projected.superclass(), List.of(), fields,
                    projected.typeId(), projected.typeMembership(), List.of(), SPAN));
        }
        var npeReceiver = value(0, IrType.reference(npe.name()));
        functions.add(new IrFunction(npe.name(), "<init>", "ironwood.ironwood.lang.NullPointerException.<init>$void", IrType.VOID,
                List.of(new IrParameter("this", npeReceiver, SPAN)), List.of(block("entry",
                List.of(new IrThrowableTraceInstruction(Optional.empty(), IrThrowableTraceInstruction.Operation.CAPTURE, List.of(npeReceiver), SPAN)),
                new IrReturnTerminator(Optional.empty(), SPAN))), SPAN, "gc4-runtime.iron", IrCallableKind.CONSTRUCTOR));
        var lifetime = input.classes().stream().filter(c -> c.name().equals("Lifetime")).findFirst().orElseThrow();
        classes.add(new IrClass(lifetime.name(), lifetime.kind(), lifetime.superclass(), List.of(), lifetime.fields(), lifetime.typeId(),
                lifetime.typeMembership(), List.of(), SPAN));
        var statics = new ArrayList<>(base.staticFields());
        input.staticFields().stream().filter(f -> f.ownerClass().equals("Lifetime")).forEach(statics::add);
        var initializations = new ArrayList<>(base.typeInitializations());
        input.typeInitializations().stream().filter(i -> i.typeName().equals("Lifetime")).forEach(initializations::add);
        var primary = value(0, THROWABLE);
        var secondary = value(1, THROWABLE);
        var later = value(2, OBJECT);
        var handle = value(3, IrType.EXCEPTION);
        var caught = value(4, THROWABLE);
        // A cleanup-only pad leaves the real native wrapper alive while collecting.
        functions.add(function("live_wrapper", IrType.VOID, List.of(primary), List.of(
                block("entry", List.of(), new IrThrowTerminator(primary, "unexpected", Optional.of("cleanup"), SPAN)),
                block("unexpected", List.of(), new IrUnreachable(SPAN)),
                block("cleanup", List.of(new IrGcUnwindInstruction(handle, true, SPAN), new IrGcCollectInstruction(SPAN),
                        new IrGcUnwindInstruction(handle, false, SPAN)), new IrUnreachable(SPAN)))));
        functions.add(function("nested_wrappers", IrType.VOID, List.of(primary, secondary), List.of(
                block("entry", List.of(), new IrThrowTerminator(primary, "unexpected", Optional.of("cleanup"), SPAN)),
                block("cleanup", List.of(new IrGcUnwindInstruction(handle, true, SPAN)), new IrInvokeTerminator(
                        new IrCallInstruction(Optional.empty(), "gc4_live_wrapper", IrType.VOID, List.of(secondary), SPAN),
                        "unexpected", "inner", SPAN)),
                block("inner", List.of(new IrExceptionLandingPadInstruction(value(5, IrType.EXCEPTION), caught, SPAN),
                        new IrGcCollectInstruction(SPAN), new IrGcUnwindInstruction(handle, false, SPAN)), new IrUnreachable(SPAN)),
                block("unexpected", List.of(), new IrUnreachable(SPAN)))));
        for (String name : List.of("unwind", "nested", "live_wrapper", "nested_wrappers")) {
            List<IrOperand> arguments = name.equals("nested") ? List.of(primary, secondary, later)
                    : name.equals("unwind") ? List.of(primary, later) : name.equals("nested_wrappers") ? List.of(primary, secondary) : List.of(primary);
            String target = name.endsWith("wrapper") || name.endsWith("wrappers") ? "gc4_" + name : "ironwood.Lifetime." + name;
            functions.add(function("catch_" + name, THROWABLE, List.of(primary, secondary, later), List.of(
                    block("entry", List.of(), new IrInvokeTerminator(new IrCallInstruction(Optional.empty(), target, IrType.VOID,
                            arguments, SPAN), "unexpected", "caught", SPAN)),
                    block("unexpected", List.of(), new IrUnreachable(SPAN)),
                    block("caught", List.of(new IrExceptionLandingPadInstruction(handle, caught, SPAN), new IrGcCollectInstruction(SPAN),
                            new IrExceptionCaughtInstruction(caught, SPAN)), new IrReturnTerminator(Optional.of(caught), SPAN)))));
        }
        functions.add(function("rethrow", THROWABLE, List.of(primary, secondary, later), List.of(
                block("entry", List.of(), new IrInvokeTerminator(new IrCallInstruction(Optional.empty(), "gc4_live_wrapper", IrType.VOID,
                        List.of(primary), SPAN), "unexpected", "first", SPAN)),
                block("first", List.of(new IrExceptionLandingPadInstruction(handle, caught, SPAN), new IrGcCollectInstruction(SPAN)),
                        new IrThrowTerminator(caught, "unexpected", Optional.of("second"), SPAN)),
                block("second", List.of(new IrExceptionLandingPadInstruction(value(5, IrType.EXCEPTION), value(6, THROWABLE), SPAN),
                        new IrGcCollectInstruction(SPAN)), new IrReturnTerminator(Optional.of(value(6, THROWABLE)), SPAN)),
                block("unexpected", List.of(), new IrUnreachable(SPAN)))));
        var published = statics.stream().filter(f -> f.ownerClass().equals("Lifetime") && f.name().equals("published")).findFirst().orElseThrow();
        IrType node = IrType.reference("Node");
        var allocation = value(7, node);
        var child = value(8, node);
        var exported = value(9, OBJECT);
        var fail = value(10, IrType.I1);
        var link = classes.stream().filter(c -> c.name().equals("Node")).findFirst().orElseThrow().fields().getFirst();
        var constructor = function("construct", IrType.VOID, List.of(allocation, child, primary, fail), List.of(
                block("entry", List.of(new IrFieldStoreInstruction(allocation, link, child, SPAN),
                        new IrReferenceConversionInstruction(exported, allocation, SPAN), new IrStaticFieldStoreInstruction(published, exported, SPAN)),
                        new IrBranch(fail, "failed", "success", SPAN)),
                block("failed", List.of(), new IrThrowTerminator(primary, "unused", Optional.empty(), SPAN)),
                block("success", List.of(new IrGcCollectInstruction(SPAN)), new IrReturnTerminator(Optional.empty(), SPAN))));
        functions.add(new IrFunction(constructor.ownerClass(), "<init>", constructor.linkageName(), constructor.returnType(), constructor.parameters(),
                constructor.blocks(), constructor.sourceSpan(), constructor.sourceFileName(), IrCallableKind.CONSTRUCTOR));
        for (boolean failed : List.of(false, true)) functions.add(function(failed ? "published_failure" : "published_success", THROWABLE,
                List.of(primary), List.of(block("entry", List.of(new IrAllocateInstruction(allocation, "Node", SPAN),
                        new IrAllocateInstruction(child, "Node", SPAN)), new IrInvokeTerminator(new IrCallInstruction(Optional.empty(), "gc4_construct", IrType.VOID,
                        List.of(allocation, child, primary, new IrConstant(IrType.I1, failed ? 1 : 0, SPAN)), SPAN), "success", "caught", SPAN)),
                block("success", List.of(), new IrReturnTerminator(Optional.of(primary), SPAN)),
                block("caught", List.of(new IrExceptionLandingPadInstruction(handle, caught, SPAN), new IrGcCollectInstruction(SPAN)),
                        new IrReturnTerminator(Optional.of(caught), SPAN)))));
        var exports = functions.stream().map(IrFunction::linkageName).collect(Collectors.toSet());
        return new IrProgram("gc4_lifetimes.iron", classes, statics, initializations, base.arrayTypes(), List.of(), base.dispatchSlots(),
                functions, Optional.empty(), Optional.empty(), exports);
    }

    static void proofs() throws Exception {
        var program = closure();
        var effects = GcExperimentalClosure.validate(program, true);
        for (String name : List.of("gc4_catch_nested", "gc4_live_wrapper", "gc4_rethrow", "ironwood.Lifetime.unwind")) {
            check(effects.function(name).mayCollect() && !effects.function(name).unknown(), "exceptional collection route lost: " + name);
        }
        denied(() -> GcExperimentalClosure.validate(program));
        denied(() -> new LlvmEmitter().emit(program));
        for (var target : List.of(new NativeTarget("aarch64-unknown-linux-gnu", "e"),
                new NativeTarget("x86_64-unknown-linux-gnu", "e"))) {
            check(!new NativeBackend().compileGcExperiment(null, program, target, Path.of("workspace/gc4-unqualified"), OptimizationLevel.O3, true)
                    .success(), "unqualified GC4 target admitted");
        }
        String llvm = new LlvmEmitter().emitGcExperiment(program, true);
        check(llvm.contains("@iw_gc_root_abi = hidden constant i32 2") && llvm.contains("@iw_gc_owner_requirements = hidden constant i32 7"),
                "owner protocol identity missing");
        check(llvm.contains("landingpad { ptr, i32 } cleanup") && llvm.contains("@iw_gc_throw(ptr addrspace(1)"), "managed transport missing");
        check(!llvm.contains("@ironwood_deallocate") && !llvm.contains("@ironwood_exception_take"), "legacy owner leaked");
        var renamed = new IrCfgRenamer(v -> value(v.id() + 100, v.type()), l -> "copy." + l)
                .instruction(new IrGcUnwindInstruction(value(1, IrType.EXCEPTION), true, SPAN));
        check(((IrGcUnwindInstruction)renamed).handle().id() == 101, "native handle clone lost");
        var badClasses = program.classes().stream().map(c -> {
            if (!c.name().equals("ironwood.lang.Throwable")) return c;
            var badFields = new ArrayList<>(c.fields());
            badFields.set(2, new IrField(c.name(), "<gcMetadata>", IrType.I64, 2, SPAN));
            return new IrClass(c.name(), c.kind(), c.superclass(), c.interfaces(), badFields, c.typeId(), c.typeMembership(), c.dispatchEntries(), SPAN);
        }).toList();
        var bad = new IrProgram(program.moduleName(), badClasses, program.staticFields(), program.typeInitializations(), program.arrayTypes(),
                program.stringConstants(), program.dispatchSlots(), program.functions(), program.entryPoint(), program.allocationFailure(), program.exportRoots());
        denied(() -> GcExperimentalClosure.validate(bad, true));
        var unsafe = function("unsafe_throw", IrType.VOID, List.of(value(0, OBJECT)), List.of(block("entry", List.of(),
                new IrThrowTerminator(value(0, OBJECT), "unused", Optional.empty(), SPAN))));
        var functions = new ArrayList<>(program.functions()); functions.add(unsafe);
        var unsafeProgram = new IrProgram(program.moduleName(), program.classes(), program.staticFields(), program.typeInitializations(), program.arrayTypes(),
                program.stringConstants(), program.dispatchSlots(), functions, program.entryPoint(), program.allocationFailure(), program.exportRoots());
        denied(() -> GcExperimentalClosure.validate(unsafeProgram, true));
        // Full legacy input remains refused, including hidden carrier integer fields.
        check(IrAllocationLayout.of(new IrClass("bad", IrTypeKind.CLASS, Optional.empty(), List.of(),
                List.of(new IrField("ironwood.lang.Throwable", "traceState", IrType.I64, 0, SPAN)), 0, List.of(), List.of(), SPAN))
                .shape() == IrAllocationLayout.Shape.REFUSED, "legacy metadata admitted");
    }
    public static void main(String[] arguments) throws Exception {
        proofs();
        Path out = Path.of(arguments[0]); Files.createDirectories(out);
        var program = closure();
        var toolchain = LlvmToolchain.discover(Path.of(arguments[1])).toolchain().orElseThrow();
        var target = NativeTarget.fromLlvm(Files.readString(Path.of(arguments[2])));
        for (var level : OptimizationLevel.values()) {
            var result = new NativeBackend().compileGcExperiment(toolchain, program, target, out.resolve(level.name()), level, true);
            check(result.success(), result.output());
        }
        var layouts = IrAllocationLayout.of(program);
        StringBuilder header = new StringBuilder("// SPDX-License-Identifier: MIT OR Apache-2.0\n#define IW_GC_TYPE_COUNT " + layouts.size() + "\n");
        for (int i = 0; i < layouts.size(); ++i) {
            header.append("extern const struct iw_gc_layout iw_llvm_layout_").append(i).append(";\n");
            if (layouts.get(i).name().equals("Node")) header.append("#define IW_GC_NODE_TYPE ").append(i).append('\n');
            if (layouts.get(i).name().equals("ironwood.lang.Throwable")) header.append("#define IW_GC_THROWABLE_TYPE ").append(i).append('\n');
            if (layouts.get(i).name().equals("ironwood.lang.NullPointerException")) header.append("#define IW_GC_NPE_TYPE ").append(i).append('\n');
        }
        header.append("static const struct iw_gc_layout *const layouts[] = {");
        for (int i = 0; i < layouts.size(); ++i) header.append("&iw_llvm_layout_").append(i).append(',');
        Files.writeString(out.resolve("layouts.h"), header.append("};\n"));
    }
    private static void denied(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("GC4 unsafe closure admitted");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
