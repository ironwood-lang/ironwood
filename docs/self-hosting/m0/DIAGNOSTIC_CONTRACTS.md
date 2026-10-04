<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Frontend diagnostic value closure

Scope: original Diagnostic and DiagnosticNote, reached by Lexer/Parser/result
validation and retained structural output. DiagnosticFormatter is a later driver
consumer and is not part of the selected in-memory frontend value boundary.
All external resolved calls in these two source files are joined to the rows
below; neither has a hash origin or captured local state. The immediate static
Diagnostic::isError method reference is addressed explicitly despite capturing
no variable. This record adds no public API or native implementation.

| Exact discovery declarations | Source-reviewed behavior and replacement | Owner, phase, first consumer and fixture |
| --- | --- | --- |
| API0073 IllegalArgumentException(String); API0147 String.isBlank() | Validate primary/note message before later fields. Null or blank messages throw IllegalArgumentException, including Java whitespace. Existing members suffice. No global-location fallback may bypass validation. Exception retains its message. | B7 M1.3, frontend diagnostic construction; blank/null and constructor failure order |
| API0517 Objects.requireNonNull(T,String) | Severity then notes are required, in that order. Explicit private checks preserve NullPointerException and the field-name message. Check notes even when a primary lacks source/span and will discard related notes. No callback or retained helper allocation. | B7 M1.3, diagnostic constructor; global invalid notes and severity failures |
| API0441 List.copyOf(Collection); API0442 List.of() | Owned immutable shallow note membership, ordered structural sequence equality, null list/element rejection. Borrow note objects and their source/span until output retirement. Global primaries discard notes when either source or span is missing, after the required copy validation. Empty factories are immutable; withNotes constructs a new value without mutating the old one. | B1/B7 M1.1/M1.3, diagnostic result closure; independent builder mutation, equal spans/distinct source identities, immutable/null notes |
| API0365 Collection.stream(); API0666 Stream.anyMatch(Predicate) | hasErrors uses sequential encounter order with immediate static isError; returns on the first error. Replace by a direct loop and early return, retaining no predicate/stream state. Null collection fails; null elements fail only if reached, so an earlier error must bypass a later null. All compiler-produced lists have non-null diagnostics, but preserve the helper's full admitted behavior. | B7 M1.3, LexResult/ParseResult/error gate; empty/warnings/first-error and reached/unreached null |

Diagnostic records compare message text, SourceFile identity, SourceSpan value,
severity and ordered notes. DiagnosticNote uses the same source/span equality
distinction but requires both location fields present or both absent. A primary
Diagnostic permits one absent location field and discards notes; do not impose
the note's stronger constructor rule on it. Located warning notes are valid.
The invocation owns new value objects and note backing storage. Shallow retirement
does not reclaim borrowed source or note objects still observed elsewhere.

The focused original-seed probe exercises constructor/null/failure ordering,
copy independence, source/span equality and first-error short circuit. The
[qualification record](diagnostic-probe/qualification.json) retains 27 checks
matching in four fresh Java processes under the pinned profile. All seven exact
patterns/twelve calls pass the source/attribution guard. This is not a resource result
or proof of native retirement. Selected malformed-source captures remain separate
canonical evidence. Record equality and private loop/copy rewrites join the
frontend/AST M1 helper closure; formatter path/text rendering remains before S4.
