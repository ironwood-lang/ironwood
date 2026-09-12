# Source provenance

This ledger records the origin and licensing classification of Ironwood source.
The controlling rules are in [`LICENSE_MECHANICS`](LICENSE_MECHANICS).

## Current classifications

| Scope | Classification | License | Provenance |
| --- | --- | --- | --- |
| Bootstrap compiler | Original Ironwood | `MIT OR Apache-2.0` | Written for Ironwood |
| Native runtime | Original Ironwood | `MIT OR Apache-2.0` | Written for Ironwood |
| Core language and utility standard library | Original or independently implemented Ironwood | `MIT OR Apache-2.0` | Written for Ironwood using Java-shaped API and behavior goals |
| S0 `String`/`StringBuilder` and default `Object.toString()` reclamation slice | Independent Java-compatible implementation plus original compiler/runtime mechanisms | `MIT OR Apache-2.0` | API, ownership, and mechanism review documented in `STDLIB_S0_SOURCE_REVIEW.md`; no OpenJDK implementation body, comment, Javadoc, test, algorithm, or distinctive internal structure inspected or adapted |
| U1 text, conversion, math, diagnostics, and platform-service slice | Independent Java-compatible implementation plus original compiler/runtime mechanisms | `MIT OR Apache-2.0` | API, ownership, deliberate reductions, and mechanism review documented in `STDLIB_U1_SOURCE_REVIEW.md`; no OpenJDK implementation body, comment, Javadoc, test, algorithm, or distinctive internal structure inspected or adapted |
| U2 paths, whole-file I/O, and `minigrep` slice | Independent Java-compatible implementation plus original compiler/runtime mechanisms | `MIT OR Apache-2.0` | API, POSIX/runtime boundary, ownership, failure, and Rust Book behavioral-target review documented in `STDLIB_U2_SOURCE_REVIEW.md`; no OpenJDK implementation body, comment, Javadoc, test, algorithm, or distinctive internal structure inspected or adapted |
| Floating-point parsing follow-up | Independent Java-compatible implementation plus original compiler/runtime mechanisms | `MIT OR Apache-2.0` | Grammar, heap-scratch-free Ironwood path, rounding boundary, and native mechanism review documented in `STDLIB_FLOATING_PARSE_SOURCE_REVIEW.md`; no OpenJDK implementation body, comment, Javadoc, test, algorithm, or distinctive internal structure inspected or adapted |
| U3 streaming I/O and CLI | Independent Java-compatible implementation plus original compiler/runtime mechanisms | `MIT OR Apache-2.0` | Java 21 API behavioral references, closed-world borrow proof, native descriptor boundary, UTF-8, and failure design in `STDLIB_U3_SOURCE_REVIEW.md`; no OpenJDK implementation source inspected or adapted |
| `ironwood.lang.System` and `ironwood.io.PrintStream` | Independent Java-compatible implementation | `MIT OR Apache-2.0` | Ironwood design D044; OpenJDK API/mechanism review documented in `SYSTEM_OUTPUT_SOURCE_REVIEW.md`, with no implementation body, comment, Javadoc, test, or distinctive internal structure adapted |
| Public Throwable traces, native storage and compiler operations | Independent Java-compatible facade plus original Ironwood mechanisms | `MIT OR Apache-2.0` | Java 21 public contracts and differential probes, without OpenJDK implementation or test copying; capture, ownership and emergency audit in `STDLIB_STACK_TRACE_REVIEW.md` |
| `Throwable.toString()` and localized-message rendering | Independent Java-compatible implementation plus original compiler/runtime mechanisms | `MIT OR Apache-2.0` | Java 21 public behavior, ownership, and native mechanism review in `STDLIB_THROWABLE_REVIEW.md`; no OpenJDK implementation source or tests copied or adapted |
| `ByteArrayOutputStream.toString()` UTF-8 snapshots | Independent Java-compatible implementation plus original compiler/runtime mechanisms | `MIT OR Apache-2.0` | Public behavior, malformed-input comparisons, and direct result allocation in `STDLIB_BYTE_STREAM_REVIEW.md`; decoder uses first-party Ironwood streaming rules, with no OpenJDK implementation source or tests copied or adapted |
| Floating `String.valueOf` and StringBuilder append overloads | Independent Java-compatible implementation using existing Ironwood conversion | `MIT OR Apache-2.0` | Type-preserving overloads and ownership review in `STDLIB_FLOATING_TEXT_REVIEW.md`; no new decimal algorithm and no OpenJDK implementation source or tests copied or adapted |
| Everyday String and Character APIs | Independent facades and original compiler/runtime mechanisms, with separately derived Unicode helpers below | `MIT OR Apache-2.0` for the facade and native allocation boundary | Fixed en_US contract, Unicode provenance, ownership and tests in `STDLIB_STRING_REVIEW.md` |
| Instant and date/time exception facades | Independent Java-compatible implementation with original compiler ownership refinements; separately derived calendar helper below | `MIT OR Apache-2.0` | Public Java 21 APIs and observed behavior, primitive ISO parser/renderer, ownership and source review in `STDLIB_INSTANT_REVIEW.md` |
| Everyday StringBuilder editing and queries | Independent Java-compatible implementation with original compiler ownership refinements | `MIT OR Apache-2.0` | Java public contracts and observed behavior, direct UTF-16 buffer operations, and ownership review in `STDLIB_STRINGBUILDER_REVIEW.md`; no OpenJDK implementation source, comments or tests copied or adapted |
| D122 standard-library compatibility expansion | Independent Java-compatible facades and algorithms plus original compiler/runtime mechanisms | `MIT OR Apache-2.0` | Character, numeric, Math, core type, util, IO, NIO, file, ownership and exclusion review in `STDLIB_COMPATIBILITY_EXPANSION_REVIEW.md`; no OpenJDK implementation source, comments, Javadocs or tests copied or adapted; generated Unicode data remains classified separately below |
| `ironwood.pool`, `ironwood.ds`, and their migrated behavioral tests | Direct contribution by the original author and relicensed as Ironwood source | `MIT OR Apache-2.0` | Maintained as first-party Ironwood source; test intent audited at original pool revision `1ee68558593402cbbdbd94b8262643f8fcfc7a42` and data-structure revision `075921aa80923ef6da624da551649a1782f6f286` |
| `ironwood.testing` standard-library testing module | Original Ironwood | `MIT OR Apache-2.0` | Written for explicit closed-world native test registration; no JUnit implementation source adapted |
| `ironwood.bench`, `examples/bench` programs, and adapted benchmark tests | Direct contribution by the original author, with permission for neutral naming and native adaptation | `Apache-2.0` | Java implementation and test intent audited at revision `f64b520deacf014dcd1b7eeeabbfd7fde30efda6`; Bench, NanoBench, and the four benchmark programs are adapted; the map demonstration uses `ironwood.ds.IntMap`. Original copyright years 2015-2024; no C/C++ source imported. Native storage, API differences, and ownership are recorded in `BENCH.md` |

The fixed en_US casing helper, generated tables and Instant calendar helper
below are OpenJDK-derived.
Other files retain their existing file-specific licenses.

## OpenJDK-derived file ledger

Every OpenJDK-derived file must add one row in the same commit that introduces
the file. Use the full immutable upstream commit hash, not only a branch or
release name.

| Ironwood path | Upstream path | OpenJDK commit | Upstream header | Port date | Ironwood changes |
| --- | --- | --- | --- | --- | --- |
| `stdlib/src/main/ironwood/ironwood/time/InstantCalendar.iron` | `src/java.base/share/classes/java/time/LocalDate.java` | `060c4f7589e7f13febd402f4dac3320f4c032b08` (jdk21u, 21.0.1+12) | Complete Oracle and original JSR-310 notices with Classpath Exception retained | 2026-09-06 | Primitive epoch-day conversions only; packed date result, no LocalDate allocation or Temporal machinery; caller validates the Instant range |
| `runtime/src/ironwood_case.c` | `src/java.base/share/classes/java/lang/ConditionalSpecialCasing.java`; `src/java.base/share/classes/sun/text/RuleBasedBreakIterator.java` | `060c4f7589e7f13febd402f4dac3320f4c032b08` (jdk21u, 21.0.1+12) | Complete Oracle and Taligent/IBM headers with Classpath Exception retained | 2026-09-06 | Fixed en_US only; native UTF-16, bounded stack state, no locale objects, collections, normalization or heap scratch |
| `runtime/src/ironwood_case_data.h` | Above helpers; `src/java.base/share/classes/sun/text/resources/BreakIteratorRules.java`; observed Character/String case, property, digit, and numeric-value mappings | `060c4f7589e7f13febd402f4dac3320f4c032b08`; generated using JDK 21.0.1+12 | Helper/rule headers including IBM notices retained; Unicode 15.0 notice in `LICENSES/Unicode-15.0.txt` | 2026-09-06 | Generated simple/full case mappings, Character properties/numeric data and English word-boundary data; generator and reproduction in `STDLIB_STRING_REVIEW.md` |

## Other third-party source ledger

Add third-party implementation source here before committing it. Record the
exact source, immutable version, license expression, retained notices, and the
reason it is appropriate for Ironwood.

| Ironwood path | Upstream project and path | Version or commit | License | Notes |
| --- | --- | --- | --- | --- |
| _No separately sourced implementation files currently recorded_ | | | | |

Bundled IDK toolchain packages are binary dependencies rather than Ironwood
implementation source. Each IDK contains a generated
`THIRD-PARTY-PACKAGES.tsv` with package versions, declared licenses, and source
locations.
