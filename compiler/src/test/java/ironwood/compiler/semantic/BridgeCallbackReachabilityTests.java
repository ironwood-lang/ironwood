// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.CompilerPipeline;
import ironwood.compiler.UnfreedMode;
import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class BridgeCallbackReachabilityTests {
    public static final String NAME = "Java Bridge callback reachability covers dispatch initialization cleanup and unknown edges";
    private static final String SOURCE = """
            class CallbackHooks { static int fire() { return 1; } }
            class ColdCallback {
                static int value = CallbackHooks.fire();
                static int read() { return 42; }
            }
            interface CallbackAction { int run(); }
            final class NativeAction implements CallbackAction { @Override public int run() { return 1; } }
            final class ForeignAction implements CallbackAction { @Override public int run() { return CallbackHooks.fire(); } }
            final class CleanupCallback { destructor { CallbackHooks.fire(); } }
            class CallbackDriver {
                static int safe() { return 42; }
                static int call() { return CallbackHooks.fire(); }
                static int dispatch(CallbackAction action) { return action.run(); }
                static int cycleA(int count) { return count == 0 ? CallbackHooks.fire() : cycleB(count - 1); }
                static int cycleB(int count) { return cycleA(count); }
                static void cleanup() { CleanupCallback value = new CleanupCallback(); free value; }
            }
            """;

    private BridgeCallbackReachabilityTests() {}

    public static void proofs() {
        var artifact = new CompilerPipeline(UnfreedMode.ERROR).analyzeForBridge(
                List.of(SourceFile.of("CallbackDriver.iron", SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var original = artifact.program().orElseThrow();
        var baseline = BridgeCallbackReachability.analyze(original);
        check(baseline.matches(original), "reachability detached from its program");
        for (String name : List.of("safe", "call", "dispatch", "cycleA", "cycleB", "cleanup", "read")) {
            var effect = entry(baseline, original, name);
            check(!effect.requiresContext(), "callback-free control acquired context: " + name + " " + effect);
        }
        var foreign = withOperation(original, new IrForeignCallInstruction(Optional.empty(),
                "ironwood_bridge_callback_fire", IrType.VOID, List.of(), function(original, "fire").sourceSpan()));
        var plan = BridgeCallbackReachability.analyze(foreign);
        check(!plan.matches(original), "stale callback facts accepted");
        check(!entry(plan, foreign, "safe").requiresContext(), "unrelated scalar path acquired context");
        for (String name : List.of("call", "dispatch", "cycleA", "cycleB", "cleanup", "read")) {
            var effect = entry(plan, foreign, name);
            check(effect.foreign() && effect.complete() && effect.requiresContext(),
                    "foreign callback lost through " + name + " " + effect);
        }
        var read = function(foreign, "read");
        check(!plan.functions().get(BridgeCallableId.of(read)).requiresContext(),
                "entry-only initialization confused with body effects");
        var missing = withOperation(original, new IrCallInstruction(Optional.empty(), "missingForeignTarget",
                IrType.VOID, List.of(), function(original, "fire").sourceSpan()));
        var unresolved = BridgeCallbackReachability.analyze(missing);
        for (String name : List.of("call", "dispatch", "cycleA", "cycleB", "cleanup", "read")) {
            var effect = entry(unresolved, missing, name);
            check(!effect.complete() && effect.requiresContext(), "unknown edge treated as callback-free: " + name);
        }
        // An unresolved dispatch alternative must not be hidden by a native alternative.
        var incomplete = copy(original, original.functions().stream().filter(function ->
                !(function.ownerClass().equals("ForeignAction") && function.sourceName().equals("run"))).toList());
        check(!entry(BridgeCallbackReachability.analyze(incomplete), incomplete, "dispatch").complete(),
                "partial dispatch set treated as complete");
        // Protected calls participate in the same graph as ordinary instructions.
        var target = function(original, "fire");
        var span = target.sourceSpan();
        var protectedBody = new IrFunction(target.ownerClass(), target.sourceName(), target.linkageName(),
                target.returnType(), target.parameters(), List.of(
                new IrBasicBlock("entry", List.of(), new IrInvokeTerminator(new IrForeignCallInstruction(
                        Optional.empty(), "ironwood_bridge_callback_fire", IrType.VOID, List.of(), span),
                        "normal", "failure", span), span),
                new IrBasicBlock("normal", List.of(), new IrReturnTerminator(
                        Optional.of(new IrConstant(IrType.I32, 1, span)), span), span),
                new IrBasicBlock("failure", List.of(), new IrReturnTerminator(
                        Optional.of(new IrConstant(IrType.I32, -1, span)), span), span)),
                span, target.sourceFileName(), target.kind());
        var protectedProgram = copy(original, original.functions().stream().map(function ->
                function.equals(target) ? protectedBody : function).toList());
        check(entry(BridgeCallbackReachability.analyze(protectedProgram), protectedProgram, "cycleB").foreign(),
                "protected foreign call omitted from recursive closure");
    }

    private static BridgeCallbackReachability.Effects entry(BridgeCallbackReachability plan, IrProgram program, String name) {
        var id = BridgeCallableId.of(function(program, name));
        return plan.entries(BridgeRootSet.resolve(program, List.of(id))).get(id);
    }

    private static IrFunction function(IrProgram program, String name) {
        return program.functions().stream().filter(function -> function.sourceName().equals(name)).findFirst().orElseThrow();
    }

    private static IrProgram withOperation(IrProgram program, IrInstruction operation) {
        var target = function(program, "fire");
        var blocks = new ArrayList<>(target.blocks());
        var first = blocks.getFirst();
        var instructions = new ArrayList<>(first.instructions());
        instructions.add(operation);
        blocks.set(0, new IrBasicBlock(first.label(), instructions, first.terminator(), first.sourceSpan()));
        var replacement = new IrFunction(target.ownerClass(), target.sourceName(), target.linkageName(), target.returnType(),
                target.parameters(), blocks, target.sourceSpan(), target.sourceFileName(), target.kind());
        return copy(program, program.functions().stream().map(function -> function.equals(target) ? replacement : function).toList());
    }

    private static IrProgram copy(IrProgram program, List<IrFunction> functions) {
        return new IrProgram(program.moduleName(), program.classes(), program.staticFields(), program.typeInitializations(),
                program.arrayTypes(), program.stringConstants(), program.dispatchSlots(), functions,
                program.entryPoint(), program.allocationFailure(), program.exportRoots());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
