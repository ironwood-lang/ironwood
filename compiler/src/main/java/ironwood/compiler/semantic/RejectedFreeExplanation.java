// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.diagnostic.DiagnosticNote;

import java.util.List;

/** Interim boundaries for eligible rejections until their evidence producers arrive. */
final class RejectedFreeExplanation {
    private static final String LIMITED = "ownership analysis was limited because of earlier errors; "
            + "fix those first and recompile; this rejection may be secondary";

    enum Missing {
        IDENTITY("the failed allocation-identity proof"),
        BORROW_OWNER("the borrowing owner's acquisition context"),
        DEFERRED_FREE("the earlier deferred-free registration"),
        RETAINING_OWNER("the selected retaining owner's operation"),
        DEFERRED_CALL("the pending call's captured operand"),
        YIELD("the pending yield's ownership context"),
        EARLIER_FREE("the earlier reclamation path"),
        SELECTED_REASON("the selected ownership reason's source operation"),
        ATTACHED_FIELD("the field attachment operation"),
        ARRAY_SLOT("the selected array-element store"),
        LOCAL_ALIAS("the current alias-producing binding"),
        DEFERRED_REGISTRATION("the first failed deferred-free ownership condition"),
        DUPLICATE_DEFER("the matching deferred-free registration"),
        FIELD_PROOF("the failed whole-class field-ownership proof"),
        LOOP_CARRIED("the incoming loop back-edge ownership path"),
        LOOP_RECLAMATION("the reclamation-blocking loop back edge"),
        OWNED_ELEMENT("the failed owned-element operation and cleanup context");

        private final String fact;

        Missing(String fact) {
            this.fact = fact;
        }
    }

    private RejectedFreeExplanation() {
    }

    static Diagnostic attach(Diagnostic primary, boolean enabled, boolean refinementCompleted,
                             Missing missing) {
        if (!enabled || !primary.isError() || primary.source() == null || primary.span() == null) {
            return primary;
        }
        String note = refinementCompleted
                ? "the compiler did not retain " + missing.fact + " needed to explain this rejection"
                : LIMITED;
        return primary.withNotes(List.of(new DiagnosticNote(note)));
    }
}
