// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeCallbackContextLowering;
import ironwood.compiler.semantic.BridgeCallbackReachability;
import ironwood.compiler.semantic.BridgeCallTargets;

import java.util.ArrayList;
import java.util.List;

/** Copied-input cleanup proof only; does not grant listener or public invocation admission. */
public final class BridgeCallbackStringEntries {
    private BridgeCallbackStringEntries() {}

    public static List<IrFunction> create(CompilationArtifact artifact, BridgeListenerProxies proxies,
            BridgeRootSet requested, BridgeCallbackContextLowering.Result context) {
        proxies.validateArtifact(artifact);
        var program = artifact.program().orElseThrow();
        var roots = requested.revalidate(program);
        var expected = BridgeCallbackContextLowering.lower(program, roots, BridgeCallbackReachability.analyze(program));
        if (!expected.equals(context)) throw new IllegalArgumentException("String callback entry requires exact context specialization");
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        if (!facts.matches(program) || program.allocationFailure().isEmpty()) {
            throw new IllegalArgumentException("String callback copy requires final borrowing and allocation-failure facts");
        }
        var string = IrType.reference("ironwood.lang.String");
        var listeners = proxies.proxies().stream().map(proxy -> IrType.reference(proxy.listener().binaryName())).toList();
        var entries = new ArrayList<IrFunction>();
        for (var root : roots.roots()) {
            var id = root.callable();
            if (!id.parameters().contains(string)) continue;
            if (!facts.isStatic(id) || id.kind() != IrCallableKind.METHOD
                    || !(id.result().isPrimitive() || id.result().equals(IrType.VOID))) {
                throw new IllegalArgumentException("copied callback inputs require static primitive/void result entry");
            }
            for (int index = 0; index < id.parameters().size(); index++) {
                var type = id.parameters().get(index);
                if (type.isReference() && ((!type.equals(string) && !listeners.contains(type)) || !facts.borrowsInput(id, index))) {
                    throw new IllegalArgumentException("callback input cleanup is not proved for parameter " + index);
                }
            }
            var specialized = context.entries().get(id);
            var target = BridgeRootSet.resolve(context.program(), List.of(BridgeCallableId.of(specialized))).roots().getFirst();
            String symbol = "ironwood_bridge_callback_strings_" + entries.size();
            if (context.program().functions().stream().anyMatch(function -> function.linkageName().equals(symbol))) {
                throw new IllegalArgumentException("callback String entry symbol collision");
            }
            // All reference inputs are copied values or nonpublishing listener
            // inputs, not independently owned facade roots requiring slot records.
            var initialization = new BridgeCallTargets(program).initializers(id.owner());
            entries.add(BridgeRootEntryLowering.lower(target, symbol, new BridgeRetentionContract(List.of()),
                    !initialization.targets().isEmpty()));
        }
        return List.copyOf(entries);
    }
}
