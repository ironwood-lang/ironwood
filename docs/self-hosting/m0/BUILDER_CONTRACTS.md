<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Mutable text contracts and typed operands

The source ledger selects 15 exact declarations and 5,006 original calls across
46 files and 127 consumers. Every call has a pinned literal source range and
actual operand type from argument-facts. Java 21 release/Xlint/Werror compiles
the independent probe; four fresh pinned JVMs each pass 37 checks, including
the original DocModel.Member.anchor helper. No native helper is implemented.

| Exact declarations | Reviewed API and immediate caller contract |
| --- | --- |
| API0178; API0179 | Default construction owns empty mutable UTF-16 backing. String construction copies initial content and rejects null. Compiler prefix builders copy their source text, without retaining it as mutable backing. Retire builders after their last helper/output use; retain independently produced result text through its actual owner. Allocation failure and partially constructed cleanup require native fixtures. |
| API0180; API0181; API0187 | Append char as one UTF-16 unit, including an unpaired surrogate; signed int/long as decimal, including minimum values. Return the original receiver for chained calls, preserving left-to-right source evaluation. Native primitive append avoids creating a boxed temporary. No allocation-count claim is made before measurement. |
| API0185; API0186 | String append copies content; null appends the four units null. Bound output::append evaluates the receiver once at binding and would reject a null receiver before iteration, even for empty input. The actual receiver is the active output builder. LlvmEmitter.emitForeignDeclarations appends declarations.values() in producer order; that upstream order remains blocked at B1/B2 M3.3 before S4 ForeignDeclarationOrder. Snapshot output is independent of later builder mutation. |
| API0182; API0183 | Nine CharSequence operands are actual StringBuilders borrowed through append; copy their current UTF-16 content, preserve return-self, and retain no operand solely for appended content. The sole ranged call is MarkdownDoclet.canonical(reference,last,matcher.start()), with actual String input and UTF-16 bounds. Null sequence uses null text before range validation; invalid range fails before copying. Self append uses initial length and ranged self append preserves source indices. Matcher/bound provenance and failure cleanup stay B7 M5.4 before S6 MarkdownReferenceRanges. |
| API0184 | Thirteen Object arguments have nine Integer, two Path and two Character actual types. Integer means signed decimal or null text, never character conversion or implicit default-zero/unboxing failure. Lexer.scanString/decodeTextBlockEscapes test decoded != null before the Character append; use primitive char plus presence, preserving malformed diagnostics and fallback. These two sites are B7 M1.3 before M2.1 CookedEscapeUnits. Path rendering uses the fixed D087/D093 spelling/ownership contract; DiagnosticFormatter is B7 M3.3 before S4 DiagnosticPathText and NativeBackend is B3/B7 M4.1 before M4.3 RuntimeHeaderIdentity. Integer producers remain gated by their actual metadata/Bridge consumers. General Object append invokes toString once, propagates failure before content copying, and appends null for a null object. A rendered String is reclaimable only under its actual fresh/unescaped proof; borrowed or shared results are not freed by assumption. |
| API0188 | Only DocModel.Member.anchor uses appendCodePoint, inside its ASCII letter/digit/underscore guard. A private append((char)c) rewrite is exact for that branch, with no broad public API addition. The reference.codePoints iteration must still preserve a supplementary pair as one code point and an unpaired surrogate as its own unit; the other branch emits hexadecimal framing. This entire helper remains B7 M5.4 before S6 AnchorCodePointUnits, including null-reference failure. Native StringBuilder currently has no appendCodePoint member. |
| API0189 | Five BridgeOwnedCallbackNativeSources insert calls prepend cleanup/leave text at zero. Preserve last-added-first cleanup order, including listener release, guard leave, owner leave, string-input release and slot release. Invalid offset is checked before null text conversion; null inserts null. B7 M6.1 before M6.2/S7 BridgeCleanupPrependOrder, or earlier if an admitted semantic caller reaches this slice. A source string is borrowed only through copying. |
| API0190 | toString supplies independent text containing the live count, unaffected by subsequent append/truncation. Preserve content rather than adding an object-identity promise. Builders shared across emission helpers belong to the whole pass; do not retire one after an individual append helper. Result/backing and borrowed fields require native normal/failure retirement fixtures. |
| API0021; API0030 | IronDocOptions ignores Java void setLength(0), so native return-self setLength is compatible at this exact statement use. Negative lengths fail; growth adds NUL units; truncation changes the live count. Five CharSequence.isEmpty calls read actual Bridge cleanup StringBuilders without mutation or allocation. General signature substitution is not claimed. IronDoc is B7 M5.4 before S6 TokenReset; Bridge checks precede CleanupPrependOrder. |

Producer ordering, tie choices, renderer aliasing beyond the exact typed sites,
and native ownership/resource evidence remain unresolved before the named consumer.
The selected frontend retains its existing exact method/site boundary; this
broader declaration review does not admit later methods in a selected file.

# Flat TSV grammar and original evidence

ArgumentFacts writes unquoted tab-separated cells, escaping backslash and control
characters while preserving literal quotes. Its reader uses csv.QUOTE_NONE.
Qualification verifies all 460 original source hashes, 53,644 receiver/operand
ranges and all 30,979 external call keys. Constant String/char values use canonical
UTF-16 hex units; floating values use raw IEEE bits. Display text is advisory for
unpaired surrogates. Formal parameter fields are declared types, not instantiated
generic substitutions. Dynamic expressions have no inferred null, alias or
lifetime facts. Source-backed later obligations must resolve those facts before
admitting the consumer.

Five failed parsing attempts and their source/logs/raw tables are retained in
argument-parsing-attempts.tar.gz. Each raw capture contains all external receiver
keys. Default CSV quoting consumed 43 receiver records and 69 total records in
the new flat table; there was no javac traversal omission. The snapshot/two-pass
variants do not establish a missing-attribution diagnosis.

The original Inventory raw tables use the same flat grammar. Historical default
CSV reads changed expression fields in 56 calls, 47 syntax rows, six references
and 40 traversals, without changing row counts or IDs. Reconciled literal_source
already preserves the authoritative pinned UTF-16 source. Keep historical fields
and original bytes intact; new consumers use quote-preserving operands and literal
source. Python-derived discovery tables use CSV quoting and retain that grammar.
The focused quote control rejects applying the historical parser to new flat data.
