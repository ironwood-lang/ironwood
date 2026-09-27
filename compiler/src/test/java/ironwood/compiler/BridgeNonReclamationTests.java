// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeNonReclamationContract;
import ironwood.compiler.bridge.BridgeProof;
import ironwood.compiler.bridge.BridgeRootSet;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeNonReclamationAnalyzer;
import ironwood.compiler.source.SourceFile;

import java.util.List;
import java.util.Set;

final class BridgeNonReclamationTests {
    private static final String SOURCE = """
            package permanentfixture;
            final class Item { int value; }
            final class Operations {
                static Item saved;
                static Item identity(Item value) { return helper(value); }
                static Item helper(Item value) { return value; }
                static void publish(Item value) { saved = value; }
                static void reclaim() { Item temporary = new Item(); free temporary; }
                static void arrayReclaim() { int[] temporary = new int[1]; free temporary; }
                static int scalar() { return 42; }
            }
            """;

    private BridgeNonReclamationTests() {}

    static void closure() {
        IrProgram program = program(SOURCE);
        var positive = analyze(program, List.of("identity", "publish"), IrType.reference("permanentfixture.Item"));
        check(positive.status() == BridgeProof.Status.PROVED, positive.reason());
        var contract = positive.contract().orElseThrow();
        check(contract.dynamicTypes().equals(Set.of(IrType.reference("permanentfixture.Item"))), "dynamic type set");
        check(contract.checkedClosure().stream().anyMatch(call -> call.name().equals("helper")), "helper missing from closure");
        check(contract.checkedClosure().stream().noneMatch(call -> call.name().equals("reclaim")), "unreachable method entered closure");
        check(analyze(program, List.of("identity", "reclaim"), IrType.reference("permanentfixture.Item"))
                .status() == BridgeProof.Status.REJECTED, "reachable deallocation accepted");
        check(analyze(program, List.of("scalar"), IrType.array(IrType.I32)).status() == BridgeProof.Status.PROVED,
                "registered array descriptor must resolve despite private negative type id");
        check(analyze(program, List.of("arrayReclaim"), IrType.array(IrType.I32)).status() == BridgeProof.Status.REJECTED,
                "array reclamation ignored");
        var constructor = BridgeCallableId.of(program.functions().stream()
                .filter(function -> function.ownerClass().equals("permanentfixture.Item")
                        && function.kind() == IrCallableKind.CONSTRUCTOR).findFirst().orElseThrow());
        var rollback = BridgeNonReclamationAnalyzer.analyze(program,
                BridgeRootSet.resolve(program, List.of(constructor)), IrType.reference("permanentfixture.Item"));
        check(rollback.status() == BridgeProof.Status.REJECTED, "unproved construction rollback silently excluded");
        check(rollback.reason().contains("constructor"), rollback.reason());
        var analyzed = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(
                List.of(SourceFile.of("test/Permanent.iron", SOURCE)));
        var withFacts = analyzed.program().orElseThrow();
        var rootsWithReclamation = withFacts.functions().stream()
                .filter(function -> BridgeCallableId.of(function).equals(constructor)
                        || function.sourceName().equals("reclaim")).map(BridgeCallableId::of).toList();
        check(BridgeNonReclamationAnalyzer.analyze(withFacts, BridgeRootSet.resolve(withFacts, rootsWithReclamation),
                IrType.reference("permanentfixture.Item"), analyzed.bridgeConstructionFacts().orElseThrow())
                .status() == BridgeProof.Status.REJECTED, "construction exemption swallowed ordinary free");
    }

    static void unknownAndDispatch() {
        IrProgram program = program(SOURCE);
        var missing = new IrProgram(program.moduleName(), program.classes(), program.staticFields(), program.typeInitializations(),
                program.arrayTypes(), program.stringConstants(), program.dispatchSlots(), program.functions().stream()
                .filter(function -> !function.sourceName().equals("helper")).toList(), program.entryPoint(), program.allocationFailure());
        check(analyze(missing, List.of("identity"), IrType.reference("permanentfixture.Item"))
                .status() == BridgeProof.Status.UNKNOWN, "unknown callee assumed non-reclaiming");
        IrProgram dispatch = program(SOURCE + """
                interface Action { void run(); }
                final class Observe implements Action {
                    @Override public void run() { }
                }
                final class Reclaim implements Action {
                    @Override public void run() { Operations.reclaim(); }
                }
                final class Driver { static void invoke(Action action) { action.run(); } }
                """);
        check(analyze(dispatch, List.of("invoke"), IrType.reference("permanentfixture.Item"))
                .status() == BridgeProof.Status.REJECTED, "unfavorable dynamic target omitted");
    }

    private static BridgeProof<BridgeNonReclamationContract> analyze(IrProgram program, List<String> names, IrType type) {
        var roots = program.functions().stream().filter(function -> names.contains(function.sourceName()))
                .map(BridgeCallableId::of).toList();
        check(roots.size() == names.size(), "ambiguous test roots");
        return BridgeNonReclamationAnalyzer.analyze(program, BridgeRootSet.resolve(program, roots), type);
    }

    private static IrProgram program(String text) {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyze(List.of(SourceFile.of("test/Permanent.iron", text)));
        check(artifact.valid(), artifact.diagnostics().toString());
        return artifact.program().orElseThrow();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
