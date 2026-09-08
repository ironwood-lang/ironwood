# Porting OpenJDK library code

This guide defines the engineering and licensing workflow for translating
selected OpenJDK standard-library implementations into Ironwood. Read
[`LICENSE_MECHANICS`](LICENSE_MECHANICS) before beginning any port.

The goal is familiar Java-shaped APIs implemented for Ironwood's native,
closed-world model. It is not to reproduce JVM internals.

## Choose the implementation category first

Before reading implementation source in depth, choose and record one category:

1. **Original Ironwood implementation.** Designed without adapting OpenJDK
   implementation source. License: `MIT OR Apache-2.0`.
2. **Independent compatible implementation.** Uses public behavior or
   specifications as its target but does not copy implementation bodies,
   comments, Javadocs, tests, or distinctive internal structure. License:
   `MIT OR Apache-2.0`.
3. **OpenJDK-derived implementation.** Translates or substantially adapts an
   OpenJDK source file or algorithm. License:
   `GPL-2.0-only WITH Classpath-exception-2.0`, but only when the exact
   upstream file expressly carries the Classpath Exception.

Do not begin with OpenJDK implementation source and later describe the result
as independent merely because the syntax changed or an AI performed the
translation.

## Behavioral contract review

Apply this review to every implementation category before choosing a reduced
surface or publishing a Java-shaped method:

1. Identify the Java behavior selected by each overload, including primitive
   widening, inherited defaults, runtime argument types, and input grammar.
   Fixed arity does not narrow the semantics of the arguments it accepts.
   Lossless numeric widening can still change type-dependent text: the
   [T5 review](STDLIB_FLOATING_TEXT_REVIEW.md) shows why a double overload cannot
   substitute for float formatting. Compare the related overloads and consumers.
2. Separate native-model constraints from implementation effort. Record the
   specific constraint when compatibility conflicts with Ironwood. A single
   application's needs do not define the contract of a general library API.
   Before expanding a broad dependency, evaluate fixed conventions as well as
   implementation and omission. Obtain an explicit product decision for material
   semantic differences; D117's fixed en_US convention is recorded in the
   [String review](STDLIB_STRING_REVIEW.md).
3. For a reduced surface, make the unsupported boundary visible during
   compilation, omit the member, or use a distinctly named Ironwood helper.
   Do not accept Java-valid calls and rely on documentation to explain later
   unsupported-input exceptions or silent approximations.
4. Compare ordinary and boundary cases with Java, including inputs beyond the
   motivating application. Add negative compilation coverage for intentional
   omissions. Application acceptance and ownership tests supplement these
   contract checks; they do not replace them. For APIs interpreting strings,
   test valid and invalid grammar boundaries so an unsupported construct cannot
   be consumed as a supported one, as happened with the zero-padding flag in
   the [T2 review](STDLIB_FORMATTING_REVIEW.md). For inherited behavior, test
   ordinary consumers and subclass overrides: a missing override can leave a
   Java-shaped call compiling through a semantically wrong base method. The
   [T3 review](STDLIB_THROWABLE_REVIEW.md) covers exception printing,
   concatenation, and builder append, independently of uncaught diagnostics.
   Check analogous types for inconsistent defaults, such as the BAOS/StringWriter
   asymmetry in the [T4 review](STDLIB_BYTE_STREAM_REVIEW.md). Shared encodings
   do not imply identical malformed-input policy: BAOS replaces while Files
   rejects malformed UTF-8.
5. Choose derivation or independent implementation against the same behavioral
   target. Review suitable Classpath-covered OpenJDK helpers for complex grammar
   and numeric algorithms when that reduces correctness risk. Copying source
   does not remove the need to audit native dependencies and ownership.

D113 records the T1 lesson: D097 implemented two application display patterns
as `String.format` overloads. Primitive widening made many Java calls
applicable, while the parser rejected literal text, flags, and other conversions
only at runtime. The repair removes that name until its contract can be
supported and retains distinctly named numeric-field helpers. This is a
compatibility gap
made explicit, not an implementation of Java Formatter.

## Pre-port checklist

For an OpenJDK-derived port:

1. Select a specific OpenJDK release or immutable commit.
2. Record the full commit hash, repository-relative source path, and upstream
   URL.
3. Read the exact file header and verify that it expressly designates the file
   as subject to the Classpath Exception.
4. Preserve that complete header. Do not replace it with an SPDX line.
5. Add the Ironwood derivation block from `LICENSE_MECHANICS` immediately after
   the upstream header.
6. Add the file to `docs/SOURCE_PROVENANCE.md` in the same change.
7. Determine which portions are portable algorithms, JVM-specific mechanisms,
   or unsupported APIs before translating code.

If the upstream file lacks the Classpath Exception, has additional notices, or
has unclear provenance, stop. Do not import it until the licensing has been
reviewed and documented.

## Technical classification

Classify every relevant member before implementation:

| Category | Ironwood treatment |
| --- | --- |
| Pure portable algorithm | Translate under the derived-file rules, or implement independently without consulting the body |
| JVM intrinsic | Replace with compiler-owned typed IR, LLVM lowering, or a small runtime boundary |
| Native method | Implement against an explicit Ironwood platform/runtime abstraction |
| Reflection, dynamic loading, or VM internals | Redesign, reduce, or omit |
| GC/finalization/reference-queue behavior | Omit or replace only when Ironwood has an explicit deterministic semantic |
| Serialization-only surface | Omit until Ironwood deliberately supports it |

Do not reproduce hidden JVM machinery to preserve an implementation detail.
Public behavior should remain Java-familiar where it does not conflict with
Ironwood's language rules.

## Reclamation and failure audit

OpenJDK code normally assumes a tracing collector. Before translating a class,
classify every reference field and allocation as owned, borrowed, transferred,
shared, immortal, or intentionally retained. Do not infer ownership merely from
privacy. Record whether a factory returns a fresh-or-null value, whether a
container retains an argument, and whether a pool checkout transfers a retained
element to the caller.

Add an Ironwood destructor only for compiler-proven-owned terminal storage.
Destructor code must remain allocation-free, must not let an exception escape,
must not publish or resurrect `this`, and must clear an owned field before
destroying the child allocation. Never translate Java finalization or cleaner
behavior into a destructor: external resources continue to use an explicit
close protocol.

Audit constructor failure separately. Compiler-generated rollback reclaims the
receiver and proven-owned fields in reverse layout order without invoking source
destructors, but it can do so only while in-progress `this` has not been
published. A port that exposes `this` during construction must be redesigned or
rejected rather than weakening rollback safety.

## Source structure

Licensing does not change public package names. A derived type may still be
`ironwood.lang.Math`, `ironwood.io.File`, or another normal standard-library
type.

Where practical, separate differently sourced mechanisms into different files:

- an independently written public facade under the default license;
- internal OpenJDK-derived algorithm helpers under GPLv2 with the Classpath
  Exception; and
- original compiler/runtime/native helpers under the default license.

Do not force an artificial split that hides derivation. If substantial derived
implementation is present in a file, classify the complete file as derived.

## Tests and documentation

OpenJDK tests, comments, and Javadocs are source material too. Copying or
translating them requires the same provenance and licensing analysis as
implementation code. Prefer new Ironwood tests written from documented
behavior and edge cases.

Record intentional incompatibilities, removed JVM dependencies, native
substitutions, and supported edge cases in the standard-library documentation.
Behavioral tests must cover every supported optimization level when the method
can be affected by native lowering.

## Required completion checks

Before considering a port complete:

- the source header and provenance ledger agree;
- all retained upstream notices are present;
- release and archive packaging carry the applicable license texts;
- corresponding Ironwood source is included with compiled library artifacts;
- `./scripts/check-licenses.sh` passes;
- relevant compiler and native behavior tests pass; and
- destructor, ownership-transfer, and failed-construction rollback behavior is
  covered when the port allocates owned storage; and
- unsupported JVM behavior is documented rather than silently approximated.

The authoritative upstream license text is the
[OpenJDK license](https://raw.githubusercontent.com/openjdk/jdk/master/LICENSE).
Inspect each selected source file separately; the repository-wide license does
not prove that a particular file has the Classpath Exception.
