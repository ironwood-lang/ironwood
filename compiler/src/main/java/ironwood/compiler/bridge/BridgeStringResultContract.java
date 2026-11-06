// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.IrType;

import java.util.Set;

/** Cleanup authority only after the exact String result has been copied to Java. */
public record BridgeStringResultContract(BridgeCallableId callable, Kind kind, Set<Integer> inputs) {
    public enum Kind { FRESH, INPUT_ALIAS, IMMORTAL, BORROWED, MANAGED }

    public BridgeStringResultContract {
        inputs = Set.copyOf(inputs);
        if (!callable.result().equals(IrType.reference("ironwood.lang.String")) || kind == null
                || inputs.stream().anyMatch(index -> index < 0 || index >= callable.parameters().size()
                        || (kind == Kind.BORROWED ? !callable.parameters().get(index).isNominalReference()
                        || callable.parameters().get(index).equals(callable.result())
                        : !callable.parameters().get(index).equals(callable.result())))
                || (kind == Kind.INPUT_ALIAS || kind == Kind.BORROWED) != !inputs.isEmpty()
                || kind == Kind.BORROWED && inputs.size() != 1) {
            throw new IllegalArgumentException("String result kind and input alternatives disagree");
        }
    }

    /** Historical query: copying a managed String never grants deallocation. */
    public boolean releaseAfterCopy() { return false; }
}
