// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Typed native segment qualification. Public bridge admission remains separate. */
public final class Gc6SegmentTests {
    static final String NAME = "GC6 foreign effects require exact versioned transition contexts";
    private static final SourceSpan SPAN = SourceSpan.at(new SourcePosition(0, 1, 1));
    private static final IrType OBJECT = IrType.reference("ironwood.lang.Object");
    private static final IrType NODE = IrType.reference("Gc6Node");
    private Gc6SegmentTests() {}

    private static IrValueReference value(int id, IrType type) { return new IrValueReference(id, type, SPAN); }
    private static IrFunction function(String name, List<IrValueReference> inputs, List<IrInstruction> instructions, IrOperand result) {
        return new IrFunction("Gc6Probe", name, "gc6_" + name, IrType.I64,
                inputs.stream().map(v -> new IrParameter("p" + v.id(), v, SPAN)).toList(),
                List.of(new IrBasicBlock("entry", instructions, new IrReturnTerminator(Optional.of(result), SPAN), SPAN)),
                SPAN, "gc6-segments.iron", IrCallableKind.METHOD);
    }

    private static IrProgram program() {
        var throwable = IrType.reference("ironwood.lang.Throwable");
        var fields = List.of(new IrField(throwable.referenceName(), "message", OBJECT, 0, SPAN),
                new IrField(throwable.referenceName(), "cause", throwable, 1, SPAN),
                new IrField(throwable.referenceName(), "<gcMetadata>", OBJECT, 2, SPAN),
                new IrField(throwable.referenceName(), "ownedMessage", OBJECT, 3, SPAN),
                new IrField(throwable.referenceName(), "causeInitialized", IrType.I1, 4, SPAN));
        var link = new IrField("Gc6Node", "link", NODE, 0, SPAN);
        var payload = new IrField("Gc6Node", "payload", IrType.I64, 1, SPAN);
        var classes = List.of(new IrClass(OBJECT.referenceName(), IrTypeKind.CLASS, Optional.empty(), List.of(), List.of(),
                        0, List.of(0), List.of(), SPAN),
                new IrClass(throwable.referenceName(), IrTypeKind.CLASS, Optional.of(OBJECT.referenceName()), List.of(), fields,
                        1, List.of(0, 1), List.of(), SPAN),
                new IrClass("Gc6Node", IrTypeKind.CLASS, Optional.of(OBJECT.referenceName()), List.of(), List.of(link, payload),
                        2, List.of(0, 2), List.of(), SPAN));
        var context = value(0, IrType.I64);
        var depth = value(1, IrType.I64);
        var seed = value(2, IrType.I64);
        var functions = new ArrayList<IrFunction>();
        for (boolean derived : List.of(false, true)) {
            var instructions = new ArrayList<IrInstruction>();
            var nodes = new ArrayList<IrValueReference>();
            int next = 3, count = derived ? 1 : 20;
            for (int i = 0; i < count; i++) {
                var node = value(next++, NODE);
                nodes.add(node);
                instructions.add(new IrAllocateInstruction(node, "Gc6Node", SPAN));
            }
            if (!derived) instructions.add(new IrFieldStoreInstruction(nodes.getFirst(), link, nodes.get(1), SPAN));
            var address = value(next++, NODE);
            if (derived) instructions.add(new IrGcProjectionInstruction(address, nodes.getFirst(), payload, SPAN));
            var callback = value(next++, IrType.I64);
            instructions.add(new IrForeignCallInstruction(Optional.of(callback), "ironwood_bridge_callback_reenter", IrType.I64,
                    List.of(depth, seed), Optional.of(context), SPAN));
            instructions.add(new IrGcCollectInstruction(SPAN));
            IrOperand sum = callback;
            for (int i = 0; i < count; i++) {
                var contents = value(next++, IrType.I64);
                if (derived) instructions.add(new IrGcReadProjectionInstruction(contents, address, SPAN));
                else if (i == 1) {
                    var child = value(next++, NODE);
                    instructions.add(new IrFieldLoadInstruction(child, nodes.getFirst(), link, SPAN));
                    instructions.add(new IrFieldLoadInstruction(contents, child, payload, SPAN));
                } else instructions.add(new IrFieldLoadInstruction(contents, nodes.get(i), payload, SPAN));
                var added = value(next++, IrType.I64);
                instructions.add(new IrBinaryInstruction(added, IrBinaryOperator.ADD, sum, contents, SPAN));
                sum = added;
            }
            functions.add(function(derived ? "derived" : "run", List.of(context, depth, seed), instructions, sum));
        }
        functions.add(function("phase", List.of(seed), List.of(new IrGcCollectInstruction(SPAN)), seed));
        functions.add(function("scalar", List.of(seed), List.of(), seed));
        var transitionResult = value(3, IrType.I64);
        functions.add(function("transition", List.of(context, seed), List.of(new IrForeignCallInstruction(Optional.of(transitionResult),
                "ironwood_bridge_callback_reenter", IrType.I64, List.of(new IrConstant(IrType.I64, 0L, SPAN), seed), Optional.of(context), SPAN)),
                transitionResult));
        var borrowed = value(3, NODE);
        var callback = value(4, IrType.I64);
        var payloadValue = value(5, IrType.I64);
        var sum = value(6, IrType.I64);
        functions.add(function("borrow", List.of(context, depth, seed, borrowed), List.of(
                new IrForeignCallInstruction(Optional.of(callback), "ironwood_bridge_callback_reenter", IrType.I64,
                        List.of(depth, seed), Optional.of(context), SPAN), new IrGcCollectInstruction(SPAN),
                new IrFieldLoadInstruction(payloadValue, borrowed, payload, SPAN),
                new IrBinaryInstruction(sum, IrBinaryOperator.ADD, callback, payloadValue, SPAN)), sum));
        return new IrProgram("gc6-segments.iron", classes, List.of(), List.of(), List.of(), List.of(), List.of(), functions,
                Optional.empty(), Optional.empty(), Set.of("gc6_run", "gc6_derived", "gc6_phase", "gc6_scalar", "gc6_borrow", "gc6_transition"));
    }

    static void proofs() {
        var program = program();
        var effects = GcExperimentalClosure.validate(program, true, true);
        check(effects.function("gc6_run").mayCollect() && !effects.function("gc6_run").unknown(), "foreign execution is not collecting");
        check(!effects.function("gc6_scalar").mayCollect(), "scalar polluted by transitions");
        check(GcEffects.analyzeRuntimeOwners(program).function("gc6_run").unknown(), "old protocol admitted foreign execution");
        denied(() -> GcManagedHeapVerifier.verify(program));
        denied(() -> GcExperimentalClosure.validate(program, true));
        check(effects.instruction(new IrForeignCallInstruction(Optional.empty(), "ironwood_bridge_callback_unknown", IrType.VOID,
                List.of(), SPAN)).unknown(), "missing context admitted");
        String llvm = new LlvmEmitter().emitGcExperiment(program, true, true);
        check(llvm.contains("@iw_gc_root_abi = hidden constant i32 3") && llvm.contains("@iw_gc_owner_requirements = hidden constant i32 15"),
                "foreign coverage not versioned");
    }

    public static void main(String[] args) throws Exception {
        proofs();
        var program = program();
        Path out = Path.of(args[0]);
        var toolchain = LlvmToolchain.discover(Path.of(args[1])).toolchain().orElseThrow();
        var target = NativeTarget.fromLlvm(Files.readString(Path.of(args[2])));
        for (var level : OptimizationLevel.values()) {
            var result = new NativeBackend().compileGcExperiment(toolchain, program, target, out.resolve(level.name()), level, true, true);
            check(result.success(), result.output());
        }
        StringBuilder header = new StringBuilder("// SPDX-License-Identifier: MIT OR Apache-2.0\n#define IW_GC_TYPE_COUNT 3\n");
        for (int i = 0; i < 3; i++) header.append("extern const struct iw_gc_layout iw_llvm_layout_").append(i).append(";\n");
        header.append("static const struct iw_gc_layout *const layouts[] = {&iw_llvm_layout_0, &iw_llvm_layout_1, &iw_llvm_layout_2};\n");
        Files.writeString(out.resolve("layouts.h"), header);
    }

    private static void denied(Runnable operation) {
        try { operation.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("unqualified foreign protocol accepted");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
