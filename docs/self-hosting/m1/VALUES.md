<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M1.3 value helpers and port conventions

Base: c9630d3e (D257). D258 records the decision; the ledger is
[values-evidence/manifest.json](values-evidence/manifest.json). The remaining
M1.3 inventory items were classified call by call against the M0 handoff. Most
map to existing Ironwood APIs or to source rewrites; these four needed helpers
or explicit conventions.

## Helpers

| Helper | Replaces | Contract |
| --- | --- | --- |
| `CharEscapes.decodeSimple(char, boolean)` | `Character decodeSimpleEscape(char, boolean)` (Lexer.java:417, used at 382, 456, 539) | `b f n r t " \\` decode in every context and `'` only in character literals; any other character returns `ABSENT` (-1). No boxing or allocation; callers keep the unsupported-escape diagnostic and fallback append |
| `SnapshotInts` | immutable `List<Integer>` segment counts (TypeName, TypeReference, Parser) | Copies an `IntArrayList` builder or gives the empty sequence for null; `size`, `get` with bounds checks, wrapping `sum`, `anyNegative`; equality and hash as `List<Integer>` |
| `Lists.single(E)` | `List.of(item)` (CatchClause, PatternFlow) | Fresh immutable one-item list; null throws `NullPointerException`; the builder is freed |
| per-type static empty `SnapshotList` | `List.of()` (49 sites) | One process-lived constant per element type; never freed by holders. A generic shared instance would need an unchecked cast, which Ironwood rejects |

## Record values

Records become final classes with explicit `equals`/`hashCode`. Each component
compares as Java's generated record method does: `==` for primitives and for
classes without an equality override (allocation nodes, source files, events
and joins), value equality for records and Strings, null-safe for nullable
components, and logical `BitSet` equality for effect summaries. Compact
constructor checks keep their order and messages; independent copies stay
independent. The fixture exercises SourcePosition, SourceSpan, evidence Site
(source identity plus span value), Retention (identity pair) and Summary.

## Conventions

The port applies these rewrites without helpers: `Optional` to nullable
references with presence branches (the parser's two `orElseThrow` sites keep
`NoSuchElementException`), `OptionalInt` to an index with -1; streams,
`forEach`, lambdas and method references to ordered loops with the same
short-circuiting and empty results; fixed-arity `contiguousKinds` overloads; a
keyword `switch` with identifier fallback; primitive evidence counters;
declared types for `var`; explicit initial values for uninitialized locals;
allocation-list traversal for joined and restored states; and literal equality
for the operation factory's single foreign target instead of `String.matches`.

Eight M2.2 RECORD inventory rows name Bridge, binder, scope and borrow-analysis
records whose simple names coincide with selected ones; they are excluded from
the pilot rather than skipped silently.

## Evidence

| Check | Result |
| --- | --- |
| Java parity | `compiler_value_helpers` prints the same transcript as the Java 21 [reference](values-evidence/ValueHelpersReference.java) in four fresh JVMs: 15 decoded escapes plus a hash over all 131,072 inputs (via the real `Lexer.decodeSimpleEscape`), 64 generated int sequences, singleton behavior and record semantics (via the real `SourcePosition` and `SourceSpan`) |
| Ownership | Off/warn/error: freeing builders and results after use accepted; using a freed sequence or list rejected; the singleton's item conservatively stays live |
| Artifacts | Classes and archive at `-O3` with zero `--unfreed=warn` diagnostics; exit 42 |
| Allocation failure | `compiler_value_helpers_failure` drives text-block normalization, the singleton list and the int sequence through every allocation limit: 0-15 fail with no helper storage left, 16 onward complete. The sweep first found leaked builders at limits 2 and 6-9; both helpers now retire their builders with `defer` |
| IronDocs | Three types generated without diagnostics |
