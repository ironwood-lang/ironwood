// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.CompilerPipeline;
import ironwood.compiler.UnfreedMode;
import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeCallbackCarrierCleanup;
import ironwood.compiler.bridge.BridgeCallbackCarrierSources;
import ironwood.compiler.bridge.BridgeListenerProxies;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class BridgeCallbackCarrierLifetimeTests {
    public static final String NAME = "Java Bridge carrier cleanup requires complete nonescaping handler and initialization proofs";
    private static final String SOURCE = """
            package carrierfixture;
            public interface Listener { void onResult(long value); }
            final class Driver {
                static Throwable saved;
                static void direct(Listener listener) { listener.onResult(42L); }
                static void discard(Listener listener) { try { direct(listener); } catch (RuntimeException failure) {} }
                static void rethrow(Listener listener) { try { direct(listener); } catch (RuntimeException failure) { throw failure; } }
                static void replace(Listener listener) {
                    try { direct(listener); } catch (RuntimeException failure) { throw new IllegalStateException(); }
                }
                static void retain(Listener listener) {
                    try { direct(listener); } catch (RuntimeException failure) { saved = failure; }
                }
                static Throwable returned(Listener listener) {
                    try { direct(listener); } catch (RuntimeException failure) { return failure; }
                    return null;
                }
                static void store(Throwable value) { saved = value; }
                static void helper(Listener listener) {
                    try { direct(listener); } catch (RuntimeException failure) { store(failure); }
                }
                static void alias(Listener listener, boolean choose) {
                    try { direct(listener); } catch (RuntimeException failure) {
                        Throwable value = choose ? failure : new IllegalStateException(); saved = value;
                    }
                }
                static void cause(Listener listener) {
                    try { direct(listener); } catch (RuntimeException failure) { throw new IllegalStateException("wrapped", failure); }
                }
                static void secondary(Listener listener) {
                    try { direct(listener); } finally { throw new IllegalStateException(); }
                }
            }
            """;

    private BridgeCallbackCarrierLifetimeTests() {}

    public static void proofs() {
        for (var mode : UnfreedMode.values()) {
            var pipeline = new CompilerPipeline(mode);
            var sources = new ArrayList<>(List.of(SourceFile.of("Listener.iron", SOURCE)));
            var initial = pipeline.analyzeForBridge(sources);
            check(initial.valid(), initial.diagnostics().toString());
            var carrier = BridgeCallbackCarrierSources.discover(initial);
            sources.add(carrier.source());
            var proxies = BridgeListenerProxies.discover(pipeline.analyzeForBridge(sources), List.of("carrierfixture"));
            var artifact = pipeline.analyzeForBridge(sources, proxies);
            check(artifact.valid(), artifact.diagnostics().toString());
            var program = artifact.program().orElseThrow();
            for (String name : List.of("direct", "discard", "rethrow", "replace")) {
                var roots = roots(program, name);
                var lifetime = BridgeCallbackCarrierLifetime.analyze(program, roots);
                check(lifetime.invocationOwned(), "safe carrier control retained: " + name + " " + lifetime.retentionReasons());
                check(lifetime.matches(program, roots), "carrier lifetime lost its program/root binding");
                check(!lifetime.matches(initial.program().orElseThrow(), roots), "stale carrier plan accepted");
                var cleanup = BridgeCallbackCarrierCleanup.prove(artifact, carrier, roots);
                check(cleanup.matches(artifact, roots) && !cleanup.matches(initial, roots), "cleanup lost artifact binding");
                check(!cleanup.matches(artifact, roots(program, "retain")), "cleanup admitted a different retaining root");
            }
            for (String name : List.of("retain", "returned", "helper", "alias", "cause", "secondary")) {
                var lifetime = BridgeCallbackCarrierLifetime.analyze(program, roots(program, name));
                check(!lifetime.invocationOwned() && !lifetime.retentionReasons().isEmpty(), "retained/unknown carrier reclaimed: " + name);
                try {
                    BridgeCallbackCarrierCleanup.prove(artifact, carrier, roots(program, name));
                    throw new AssertionError("retained carrier gained destruction: " + name);
                } catch (IllegalArgumentException expected) {
                    check(expected.getMessage().contains("outlive"), expected.toString());
                }
            }
            var direct = function(program, "direct");
            var blocks = new ArrayList<>(direct.blocks());
            var first = blocks.getFirst();
            var instructions = new ArrayList<>(first.instructions());
            instructions.add(new IrCallInstruction(Optional.empty(), "unresolved_carrier_helper", IrType.VOID, List.of(), direct.sourceSpan()));
            blocks.set(0, new IrBasicBlock(first.label(), instructions, first.terminator(), first.sourceSpan()));
            var replacement = new IrFunction(direct.ownerClass(), direct.sourceName(), direct.linkageName(), direct.returnType(),
                    direct.parameters(), blocks, direct.sourceSpan(), direct.sourceFileName(), direct.kind());
            var unknown = copy(program, program.functions().stream().map(function -> function.equals(direct) ? replacement : function).toList());
            check(!BridgeCallbackCarrierLifetime.analyze(unknown, roots(unknown, "discard")).invocationOwned(),
                    "unknown helper granted temporary carrier ownership");
            var invalidFree = pipeline.analyzeForBridge(List.of(SourceFile.of("Listener.iron", SOURCE.replace(
                    "catch (RuntimeException failure) {}", "catch (RuntimeException failure) { free failure; }"))));
            check(!invalidFree.valid() && invalidFree.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("cannot prove free")),
                    "transport cleanup permission weakened source exception free: " + invalidFree.diagnostics());
        }
        initializationCache();
        carrierDeclaration();
    }

    private static void carrierDeclaration() {
        var pipeline = new CompilerPipeline(UnfreedMode.ERROR);
        var originalSources = List.of(SourceFile.of("CarrierControl.iron", "final class CarrierControl {}"));
        var original = pipeline.analyzeForBridge(originalSources);
        check(original.valid(), original.diagnostics().toString());
        var declaration = BridgeCallbackCarrierSources.discover(original);
        var sources = new ArrayList<>(originalSources);
        sources.add(declaration.source());
        var artifact = pipeline.analyzeForBridge(sources);
        check(artifact.valid(), artifact.diagnostics().toString());
        var bound = declaration.bind(artifact);
        check(bound.factory().returnType().equals(declaration.type())
                && bound.reference().returnType().equals(IrType.I64) && bound.next().returnType().equals(IrType.I64),
                "carrier operations lost their native value ABI");
        var modified = new ArrayList<>(originalSources);
        modified.add(SourceFile.of(declaration.source().path().toString(), declaration.source().content().replace(
                "return new _ForeignFailure(reference, next);", "return null;")));
        var changed = pipeline.analyzeForBridge(modified);
        check(changed.valid(), changed.diagnostics().toString());
        try { declaration.bind(changed); throw new AssertionError("changed carrier factory accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("exact compiler-owned declaration"), expected.toString()); }
        try { BridgeCallbackCarrierSources.discover(artifact); throw new AssertionError("carrier collision accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("collision"), expected.toString()); }
    }

    private static void initializationCache() {
        var artifact = new CompilerPipeline(UnfreedMode.ERROR).analyzeForBridge(List.of(SourceFile.of("Cold.iron", """
                class Hooks { static int fire() { return 1; } }
                class Cold { static int value = Hooks.fire(); static int read() { return value; } }
                """)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var original = artifact.program().orElseThrow();
        var hook = function(original, "fire");
        var span = hook.sourceSpan();
        var changed = new IrFunction(hook.ownerClass(), hook.sourceName(), hook.linkageName(), hook.returnType(), hook.parameters(),
                List.of(new IrBasicBlock("entry", List.of(new IrForeignCallInstruction(Optional.empty(),
                        "ironwood_bridge_callback_initializer", IrType.VOID, List.of(), span)),
                        new IrReturnTerminator(Optional.of(new IrConstant(IrType.I32, 1, span)), span), span)), span,
                hook.sourceFileName(), hook.kind());
        var program = copy(original, original.functions().stream().map(function -> function.equals(hook) ? changed : function).toList());
        var lifetime = BridgeCallbackCarrierLifetime.analyze(program, roots(program, "read"));
        check(!lifetime.invocationOwned() && lifetime.retentionReasons().contains("failed initialization can retain a callback carrier"),
                "implicit initializer failure cache lost carrier lifetime");
    }

    private static BridgeRootSet roots(IrProgram program, String name) {
        return BridgeRootSet.resolve(program, List.of(BridgeCallableId.of(function(program, name))));
    }

    private static IrFunction function(IrProgram program, String name) {
        return program.functions().stream().filter(function -> function.sourceName().equals(name)).findFirst().orElseThrow();
    }

    private static IrProgram copy(IrProgram program, List<IrFunction> functions) {
        return new IrProgram(program.moduleName(), program.classes(), program.staticFields(), program.typeInitializations(),
                program.arrayTypes(), program.stringConstants(), program.dispatchSlots(), functions, program.entryPoint(), program.allocationFailure());
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
