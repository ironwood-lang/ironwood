// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.CompilerPipeline;
import ironwood.compiler.UnfreedMode;
import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeListenerProxies;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class BridgeCallbackContextTests {
    public static final String NAME = "Java Bridge invocation context preserves native ABI dispatch recursion and unwind edges";
    private static final String SOURCE = """
            package contextfixture;
            public interface Listener { long onResult(long value); }
            final class NativeListener implements Listener {
                @Override public long onResult(long value) { return value + 1L; }
            }
            final class Driver {
                static long safe(long value) { return value + 1L; }
                static long call(Listener listener, long value) { return listener.onResult(value); }
                static long cycleA(Listener listener, long value) {
                    return value == 0L ? call(listener, value) : cycleB(listener, value - 1L);
                }
                static long cycleB(Listener listener, long value) { return cycleA(listener, value); }
                static long caught(Listener listener) {
                    try { return call(listener, 42L); } catch (Throwable failure) { return -1L; }
                }
            }
            """;

    private BridgeCallbackContextTests() {}

    public static void proofs() {
        var sources = List.of(SourceFile.of("Listener.iron", SOURCE));
        var pipeline = new CompilerPipeline(UnfreedMode.ERROR);
        var initial = pipeline.analyzeForBridge(sources);
        check(initial.valid(), initial.diagnostics().toString());
        var proxies = BridgeListenerProxies.discover(initial, List.of("contextfixture"));
        var bound = pipeline.analyzeForBridge(sources, proxies);
        check(bound.valid(), bound.diagnostics().toString());
        var original = bound.program().orElseThrow();
        var reachability = BridgeCallbackReachability.analyze(original);
        var safe = roots(original, "safe");
        check(BridgeCallbackContextLowering.lower(original, safe, reachability).program().equals(original),
                "native-only path acquired context or changed metadata");
        var requested = roots(original, "cycleB", "caught", "safe");
        var lowered = BridgeCallbackContextLowering.lower(original, requested, reachability);
        check(lowered.program().functions().containsAll(original.functions()), "ordinary native functions changed");
        check(lowered.program().dispatchSlots().containsAll(original.dispatchSlots()), "ordinary native slots changed");
        check(!lowered.specializations().containsKey(function(original, "safe").linkageName()), "native scalar specialized");
        for (var entry : lowered.specializations().entrySet()) {
            var source = symbol(original, entry.getKey());
            var target = symbol(lowered.program(), entry.getValue());
            check(target.parameters().size() == source.parameters().size() + 1, "missing explicit context argument");
            check(target.sourceFileName().equals(source.sourceFileName()) && target.sourceSpan().equals(source.sourceSpan()),
                    "source attribution lost in context specialization");
            var context = target.parameters().getLast().value();
            check(context.type().equals(IrType.I64), "context uses wrong address carrier");
            operations(target).forEach(operation -> {
                if (operation instanceof IrForeignCallInstruction foreign) {
                    check(foreign.invocationContext().equals(Optional.of(context)), "foreign callback uses another frame");
                    var copied = (IrForeignCallInstruction) new IrCfgRenamer(value -> new IrValueReference(
                            value.id() + 100, value.type(), value.sourceSpan()), label -> label).instruction(foreign);
                    check(((IrValueReference) copied.invocationContext().orElseThrow()).id() == context.id() + 100,
                            "CFG copy lost invocation context");
                    check(!new BridgeCallTargets(lowered.program()).resolve(foreign).complete(),
                            "bound foreign context granted closed-world effect proof");
                } else if (operation instanceof IrCallInstruction call && lowered.specializations().containsValue(call.targetLinkageName())) {
                    check(call.arguments().getLast().equals(context), "direct/recursive call lost context");
                } else if (operation instanceof IrInterfaceCallInstruction call) {
                    check(call.arguments().getLast().equals(context), "dispatch lost context");
                    var alternatives = new BridgeCallTargets(lowered.program()).resolve(call);
                    check(alternatives.complete() && alternatives.targets().size() == 2, "dispatch alternatives lost");
                    check(alternatives.targets().stream().allMatch(alternative ->
                            lowered.specializations().containsValue(alternative.linkageName())), "mixed context/native dispatch ABI");
                }
            });
        }
        var nativeAlternative = original.functions().stream().filter(function -> function.ownerClass().equals("contextfixture.NativeListener")
                && function.sourceName().equals("onResult")).findFirst().orElseThrow();
        check(symbol(lowered.program(), lowered.specializations().get(nativeAlternative.linkageName())).blocks()
                .equals(nativeAlternative.blocks()), "native dispatch alternative acquired bookkeeping");
        var caught = function(original, "caught");
        var copy = symbol(lowered.program(), lowered.specializations().get(caught.linkageName()));
        for (int index = 0; index < caught.blocks().size(); index++) {
            if (caught.blocks().get(index).terminator() instanceof IrInvokeTerminator before) {
                var after = (IrInvokeTerminator) copy.blocks().get(index).terminator();
                check(before.normalTarget().equals(after.normalTarget()) && before.unwindTarget().equals(after.unwindTarget()),
                        "context plumbing changed protected edges");
            }
        }
        reject(() -> BridgeCallbackContextLowering.lower(initial.program().orElseThrow(), safe, reachability), "stale");
        reject(() -> BridgeCallbackContextLowering.lower(lowered.program(),
                BridgeRootSet.resolve(lowered.program(), lowered.entries().values().stream().map(BridgeCallableId::of).toList()),
                BridgeCallbackReachability.analyze(lowered.program())), "already bound");
        implicitPaths();
    }

    private static void implicitPaths() {
        var artifact = new CompilerPipeline(UnfreedMode.ERROR).analyzeForBridge(List.of(SourceFile.of("Hooks.iron", """
                class Hooks { static int fire() { return 1; } }
                class Cold { static int value = Hooks.fire(); static int read() { return 42; } }
                class Cleanup { destructor { Hooks.fire(); } }
                class Caller { static void run() { Cleanup value = new Cleanup(); free value; } }
                """)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var original = artifact.program().orElseThrow();
        var hook = function(original, "fire");
        for (boolean unknown : List.of(false, true)) {
            IrInstruction operation = unknown
                    ? new IrCallInstruction(Optional.empty(), "missing", IrType.VOID, List.of(), hook.sourceSpan())
                    : new IrForeignCallInstruction(Optional.empty(), "ironwood_bridge_callback_hook", IrType.VOID, List.of(), hook.sourceSpan());
            var function = new IrFunction(hook.ownerClass(), hook.sourceName(), hook.linkageName(), hook.returnType(), hook.parameters(),
                    List.of(new IrBasicBlock("entry", List.of(operation),
                            new IrReturnTerminator(Optional.of(new IrConstant(IrType.I32, 1, hook.sourceSpan())), hook.sourceSpan()), hook.sourceSpan())),
                    hook.sourceSpan(), hook.sourceFileName(), hook.kind());
            var program = new IrProgram(original.moduleName(), original.classes(), original.staticFields(), original.typeInitializations(),
                    original.arrayTypes(), original.stringConstants(), original.dispatchSlots(), original.functions().stream()
                    .map(candidate -> candidate.equals(hook) ? function : candidate).toList(), original.entryPoint(), original.allocationFailure());
            for (String root : List.of("read", "run")) {
                reject(() -> BridgeCallbackContextLowering.lower(program, roots(program, root), BridgeCallbackReachability.analyze(program)),
                        unknown ? "incomplete" : "unsupported through");
            }
        }
    }

    private static BridgeRootSet roots(IrProgram program, String... names) {
        return BridgeRootSet.resolve(program, Stream.of(names).map(name -> BridgeCallableId.of(function(program, name))).toList());
    }

    private static IrFunction function(IrProgram program, String name) {
        return program.functions().stream().filter(function -> function.sourceName().equals(name)).findFirst().orElseThrow();
    }

    private static IrFunction symbol(IrProgram program, String name) {
        return program.functions().stream().filter(function -> function.linkageName().equals(name)).findFirst().orElseThrow();
    }

    private static Stream<IrInstruction> operations(IrFunction function) {
        return function.blocks().stream().flatMap(block -> Stream.concat(block.instructions().stream(),
                block.terminator() instanceof IrInvokeTerminator invoke ? Stream.of(invoke.call()) : Stream.empty()));
    }

    private static void reject(Runnable action, String fragment) {
        try { action.run(); } catch (IllegalArgumentException failure) {
            check(failure.getMessage().contains(fragment), failure.toString());
            return;
        }
        throw new AssertionError("missing refusal: " + fragment);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
