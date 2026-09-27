// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import java.util.Set;

/**
 * Final semantic result origins, not an export or destruction capability.
 * Input indices include an instance receiver. Dependent views name exactly one
 * input root; admission must separately prove its storage and dynamic types.
 * Nullable is conservative for aliases and views, including nullable inputs/fields.
 */
public record BridgeResultOriginContract(BridgeCallableId callable, Kind kind,
        Set<Integer> inputs, boolean nullable) {
    public BridgeResultOriginContract {
        inputs = Set.copyOf(inputs);
        if (kind == null || !callable.result().isReference()
                || inputs.stream().anyMatch(index -> index < 0 || index >= callable.parameters().size())) {
            throw new IllegalArgumentException("result origins require a resolved reference result and input indices");
        }
        if ((kind == Kind.INPUT_ALIAS && inputs.isEmpty())
                || (kind == Kind.DEPENDENT_VIEW && inputs.size() != 1)
                || ((kind == Kind.FRESH_ROOT || kind == Kind.NULL_ONLY) && !inputs.isEmpty())
                || (kind == Kind.NULL_ONLY && !nullable)) {
            throw new IllegalArgumentException("result origin kind and alternatives disagree");
        }
    }

    public enum Kind { NULL_ONLY, FRESH_ROOT, INPUT_ALIAS, DEPENDENT_VIEW }
}
