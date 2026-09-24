// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.diagnostic.DiagnosticNote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Renders only a selected call effect backed by the final analyzer instance. */
final class SummaryCallExplanation {
    private static final int MAX_HOPS = 4;
    private static final int MAX_NOTES = 8;

    private SummaryCallExplanation() {
    }

    static List<DiagnosticNote> notes(EscapeSummaryAnalyzer summaries,
                                      RejectedFreeEvidence.Event event) {
        RejectedFreeEvidence.Call call = event.call();
        if (call == null) return List.of();
        List<DiagnosticNote> notes = new ArrayList<>();
        String destination = methodName(summaries, call.method());
        String role = call.fact().role() == -1 ? "receiver"
                : "argument " + (call.fact().role() + 1);
        String first = call.possibleTarget() ? "this call can pass the allocation as " + role
                + " to possible target '" + destination + "'"
                : "this call passes the allocation as " + role + " to '" + destination + "'";
        notes.add(new DiagnosticNote(first, event.source(), event.span()));
        if (call.noEntryPoint()) {
            notes.add(new DiagnosticNote("this compilation has no entry point to narrow receiver "
                    + "inputs; compatible retaining targets remain possible"));
        }

        SummaryWitnessEvidence.Witness witness = call.witness();
        if (witness == null || !witness.method().equals(call.method())
                || !witness.fact().equals(call.fact())) {
            notes.add(new DiagnosticNote(missingRoot(summaries, call)));
            return List.copyOf(notes);
        }
        Set<SummaryWitnessEvidence.Witness> visited = Collections.newSetFromMap(
                new IdentityHashMap<>());
        int hops = 0;
        while (witness != null && hops < MAX_HOPS && notes.size() < MAX_NOTES) {
            if (!visited.add(witness) || !summaries.supportsFinalWitness(witness)) {
                notes.add(new DiagnosticNote("a recorded call dependency does not match the final summary; "
                        + "further source evidence is unavailable"));
                break;
            }
            SummaryWitnessEvidence.Witness next = witness.dependency();
            if (next != null && !summaries.supportsFinalWitness(next)) {
                notes.add(new DiagnosticNote("a recorded callee effect is not supported by its final "
                        + "summary; further source evidence is unavailable",
                        witness.source(), witness.span()));
                break;
            }
            notes.add(note(summaries, witness, next));
            witness = next;
            hops++;
        }
        if (witness != null && hops == MAX_HOPS && notes.size() < MAX_NOTES) {
            notes.add(new DiagnosticNote("further call evidence was omitted after four summary hops"));
        }
        return List.copyOf(notes);
    }

    private static String missingRoot(EscapeSummaryAnalyzer summaries,
                                      RejectedFreeEvidence.Call call) {
        SummaryWitnessEvidence store = summaries.witnessEvidence();
        if (store != null && store.invocationStopped()) {
            return "the invocation evidence storage limit was reached; "
                    + "the final callee source path was omitted";
        }
        if (store != null && store.methodTruncated(call.method())) {
            return "the callee summary evidence limit was reached; "
                    + "the final source path was omitted";
        }
        return "the compiler did not retain a final callee source path for this call effect";
    }

    private static DiagnosticNote note(EscapeSummaryAnalyzer summaries,
                                       SummaryWitnessEvidence.Witness witness,
                                       SummaryWitnessEvidence.Witness next) {
        String method = methodName(summaries, witness.method());
        String reason = witness.reason();
        String message;
        if (next != null) {
            String target = methodName(summaries, next.method());
            String role = next.fact().role() == -1 ? "receiver"
                    : "argument " + (next.fact().role() + 1);
            message = "'" + method + "' can pass that reference to '" + target
                    + "' as " + role;
        } else if (reason.startsWith("static field '") || reason.startsWith("field '")) {
            message = "'" + method + "' can store that reference in " + reason;
        } else if (reason.equals("array element") || reason.equals("array initializer")) {
            message = "'" + method + "' can store that reference in an array element";
        } else if (reason.equals("return") || reason.equals("return alias")) {
            message = "'" + method + "' returns that reference here";
        } else if (reason.startsWith("call '") || reason.equals("unresolved call")) {
            message = "'" + method + "' has a call effect here; its callee source path "
                    + "was not retained";
        } else {
            message = "'" + method + "' has this final call effect here: " + reason;
        }
        return witness.source() == null || witness.span() == null
                ? new DiagnosticNote(message)
                : new DiagnosticNote(message, witness.source(), witness.span());
    }

    private static String methodName(EscapeSummaryAnalyzer summaries, String linkage) {
        CallableSymbol callable = summaries.callable(linkage);
        if (callable == null) return linkage;
        String owner = callable.ownerType();
        return owner.substring(owner.lastIndexOf('.') + 1) + "." + callable.sourceName();
    }
}
