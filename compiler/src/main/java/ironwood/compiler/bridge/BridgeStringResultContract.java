// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.IrType;

import java.util.Set;

/** Cleanup authority only after the exact String result has been copied to Java. */
public record BridgeStringResultContract(BridgeCallableId callable, Kind kind, Set<Integer> inputs) {
    public enum Kind { FRESH, INPUT_ALIAS, IMMORTAL }

    public BridgeStringResultContract {
        inputs = Set.copyOf(inputs);
        if (!callable.result().equals(IrType.reference("ironwood.lang.String")) || kind == null
                || inputs.stream().anyMatch(index -> index < 0 || index >= callable.parameters().size()
                        || !callable.parameters().get(index).equals(callable.result()))
                || (kind == Kind.INPUT_ALIAS) != !inputs.isEmpty()) {
            throw new IllegalArgumentException("String result kind and input alternatives disagree");
        }
    }
}
