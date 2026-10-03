// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.BridgeObjectAdmission;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrType;

import java.util.Optional;

/** Private ABI choices derived from the admitted result origins, never a replacement lifetime analysis. */
final class BridgeRootCalls {
    private BridgeRootCalls() {}

    static boolean rooted(BridgeObjectAdmission admission, IrType type) {
        return admission.roots().map(proof -> proof.protocol().constructedRootTypes().contains(BridgeGenericDomain.storage(type))
                || proof.protocol().borrowedResultTypes().contains(BridgeGenericDomain.storage(type))).orElse(false);
    }

    static Optional<IrType> reservation(BridgeObjectAdmission admission, BridgeCallableId callable) {
        if (admission.roots().isEmpty()) return Optional.empty();
        var protocol = admission.roots().orElseThrow().protocol();
        if (callable.kind() == IrCallableKind.CONSTRUCTOR) {
            var type = IrType.reference(callable.owner());
            return protocol.constructedRootTypes().contains(type) ? Optional.of(type) : Optional.empty();
        }
        var origin = protocol.resultOrigins().get(callable);
        return origin != null && origin.kind() == BridgeResultOriginContract.Kind.FRESH_ROOT ? Optional.of(BridgeGenericDomain.storage(callable.result())) : Optional.empty();
    }

    static boolean receiverState(BridgeObjectAdmission admission, BridgeCallableId callable, boolean instance) {
        if (!instance || !rooted(admission, IrType.reference(callable.owner()))) return false;
        if (BridgeRootRetentionSources.slots(admission, callable).stream().anyMatch(slot -> slot.holderInput() == 0 || slot.valueInputs().contains(0))) return true;
        if (!rooted(admission, callable.result())) return false;
        var origin = admission.roots().orElseThrow().protocol().resultOrigins().get(callable);
        return origin != null && origin.inputs().contains(0);
    }

    static String documentation(BridgeObjectAdmission admission, BridgeCallableId callable) {
        if (admission.roots().isEmpty()) return "";
        if (callable.kind() == IrCallableKind.CONSTRUCTOR && reservation(admission, callable).isPresent()) {
            return "Creates an owning native root. Call free() when its lifetime is complete and it is eligible.";
        }
        if (!rooted(admission, callable.result())) return "";
        var origin = admission.roots().orElseThrow().protocol().resultOrigins().get(callable);
        if (origin == null) throw new IllegalArgumentException("root result lacks admitted origin documentation");
        return switch (origin.kind()) {
            case FRESH_ROOT -> "Returns a new owning native root" + (origin.nullable() ? " or null" : "") + ". Call free() when eligible.";
            case INPUT_ALIAS -> "Returns an existing native object identity, preserving its owning or borrowed role; may return null.";
            case DEPENDENT_VIEW -> "Returns a borrowed view sharing its input root's lifetime; may return null.";
            case NULL_ONLY -> "Returns null.";
        };
    }
}
