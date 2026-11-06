// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.BridgeObjectAdmission;
import ironwood.compiler.ir.IrType;

import java.util.Optional;

/** Private ABI choices derived from the admitted result origins, never a replacement lifetime analysis. */
final class BridgeRootCalls {
    private BridgeRootCalls() {}

    static boolean rooted(BridgeObjectAdmission admission, IrType type) {
        return admission.roots().map(proof -> proof.protocol().constructedRootTypes().contains(BridgeGenericDomain.storage(type))).orElse(false);
    }

    static Optional<IrType> reservation(BridgeObjectAdmission admission, BridgeCallableId callable) {
        return rooted(admission, callable.result()) ? Optional.of(BridgeGenericDomain.storage(callable.result())) : Optional.empty();
    }

    static String documentation(BridgeObjectAdmission admission, BridgeCallableId callable) {
        return reservation(admission, callable).isPresent()
                ? "Returns an independent foreign anchor; close its RootHandle when finished." : "";
    }
}
