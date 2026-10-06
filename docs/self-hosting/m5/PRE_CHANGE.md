<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M5 pre-change review

Entry: G1 passed at adfa0515194209c1cd4fb2766f3097ea79f51573, M3 is complete
at bdf34a55a1981aa36297a2b6fcff720bc9cf159c (SHA-256 from M3.2, D267), and the
M4 filesystem checkpoint passed at 9aceda98 and was qualified on all three
hosts at ea19508cd62e2fe1e7dcfe56d991453ff2795dda. M5 delivers B5's public
CRC32, B6's archive codec, writer profiles and artifact services, and the S6
portions of B7 (documentation and CLI helpers). It prepares S6; it does not
port IronClass, IronJar, SourceSetLoader, StandardLibrary, IronDoc or
IronJarMain, and it claims neither S6 nor public ZIP/GZIP support. M6, every
S2+ stage and Bridge JAR production are out of scope. All work and commits are
local on `before-self-hosting`, without remote operations.

## Scope and first consumers

The M0 source-backed inventory (`m0/deferred/calls.json.gz`) assigns M5 three
gates. Each phase classifies every one of its patterns, as M3 and M4 did, into
an existing Ironwood API (A), a recorded port convention (B) or a delivered
helper (D), with phase-scoped rules so the M3 and M4 tables regenerate
unchanged.

| Gate | Calls | Patterns | Consumers |
| --- | --- | --- | --- |
| M5.1 | 2 | 2 | IronJar (`new CRC32()`, `getValue()`) |
| M5.2-M5.3 | 473 | 145 | IronClass, IronJar and its ClassPayload, SourceSetLoader's class-path loading, StandardLibrary's archive and class discovery, Main's artifact options |
| M5.4 | 874 | 165 | DocComment, DocModel, IronDoc, IronDocOptions, MarkdownDoclet, IronJarMain and its CommandLine |

| Phase | Delivery | First consumers |
| --- | --- | --- |
| M5.1 | Public `ironwood.util.zip.CRC32`; the frozen archive contract: legacy-input matrix, metadata rules, serialization fixtures and publication expectations, with fixture identities hashed by M3's `Sha256` | IronJar's STORED entries (S6), the native writers (M5.3) |
| M5.2 | Compiler-private raw DEFLATE decoder and ZIP container services; the reader and writer profile decision | IronClass and IronJar reading (S6), Bridge JAR writing (M6.2) |
| M5.3 | Compiler-private IronClass and IronJar profile services: reading, validation, nested payloads, writing, staging and publication; stdlib archive and class-root discovery | SourceSetLoader, StandardLibrary, Main, IronJarMain (S6) |
| M5.4 | Documentation text scans, entity decoding, the Java identifier-part predicate, the link callback, and the IronDoc traversal and discovery conventions | DocComment, MarkdownDoclet, DocModel, IronDoc, IronDocOptions, IronJarMain (S6) |

## Contracts to preserve

- B5: CRC32 is the ZIP checksum of the uncompressed bytes, exposed unsigned in
  a `long`; the public surface is exactly `CRC32()`, `reset()`, `update(int)`,
  `update(byte[])`, `update(byte[], int, int)` and `getValue()`, each with its
  Java 21 behavior for every admitted call, including widened arguments and
  subclass dispatch of the whole-array overload. `Checksum` and
  `update(ByteBuffer)` are omitted with negative compilation coverage. MD5 and
  SHA-256 stay compiler-private; no second digest facility is created.
- B6 9.1: the three profiles keep their distinctions. `IronClass.read` skips
  directories, rejects duplicates and decodes with replacement;
  `IronJar.read` validates paths, order, directories, nesting, the manifest
  and the index with strict UTF-8; Bridge's manifest goes first. All readers
  accept legacy DEFLATED entries, including DEFLATED payloads nested in a
  STORED `.ironjar` entry. A STORED writer never permits a STORED-only reader.
- B6 9.3: bounds are checked before any allocation from untrusted lengths;
  64-bit fields are parsed checked before narrowing; CRC and decoded length
  are verified; a new resource limit is an explicit accepted-input policy.
- B6 9.4: same-writer determinism for identical inputs, independently for the
  native writer; cross-writer byte equality is a separate recorded requirement.
- B3 publication: IronJar stages in the destination's parent and publishes with
  `moveReplacing`, deleting the stage on every exit; `IronClass.write` keeps its
  direct write (adding staging would be a separate improvement); no fsync
  (commit 8d847b37). Traversal rewrites follow B3's table.
- B7 10.1 and the tag scan: preserve the UTF-16 `char` scan with the
  identifier-part predicate at the first position, Java 21 `\R` and unflagged
  `\s`, limit semantics, and every caller's diagnostics.
- D132/D133, mandatory memory safety in every unfreed mode, and the D261/D262
  port forms (creation-array owners, no service getters, invocation-lived
  shared graphs).

## Material choices

Each choice records its options and the default M5 adopts, subject to the
evidence named.

**CRC32 implementation.** Options: (a) a bytewise table; (b) slice-by-8 tables;
(c) a hardware instruction through a new compiler intrinsic. (c) needs typed
IR, LLVM lowering and a runtime fallback (x86-64 has no instruction for this
polynomial), which is out of proportion for this slice. Choose (b): original
code from the CRC-32/ISO-HDLC definition (reflected polynomial `0xEDB88320`,
initial and final value `0xFFFFFFFF`), with eight 256-entry tables computed at
class initialization, process-lived like `Sha256`'s round constants. The class
is not final, as Java's is not; `update(byte[])` calls `update(b, 0, b.length)`
dynamically, as the Java `Checksum` default does, so overrides observe it.
Range failures throw `ArrayIndexOutOfBoundsException` with Java's
`Range [off, off + len) out of bounds for length n` text and a null array
throws NullPointerException, as Java 21 does (observed). Updates allocate
nothing and borrow the array for the call.

**Raw DEFLATE decoding.** Options: (a) original Ironwood inflate; (b) a pinned
zlib through a typed runtime boundary; (c) Ironwood inflate and deflate. Choose
(a) for reading in every profile, written from RFC 1951 alone: stored, fixed
and dynamic blocks, canonical Huffman tables with completeness and
over-subscription checks, lengths 3-258, distances 1-32768 within the produced
output, and exact end-of-stream handling. Decoding writes into one output array
sized from checked metadata, or a growable output bounded by the entry policy
when a sequential read has no size yet; decoder tables are owned by one
reusable decoder. No zlib is linked or imported for the archive codec.

**Container models.** The IronClass profile reads local headers sequentially,
as `ZipInputStream` does: entries end at the first non-local signature, STORED
entries need sizes, DEFLATED entries may use a data descriptor with or without
its signature, CRC and sizes are verified. The IronJar profile reads the
central directory, as `ZipFile` does, with ZIP64 end records and extra fields,
locates data through each local header and also verifies CRC and size, a
deliberate strictness recorded in the matrix because Java's `ZipFile` returns
a mismatched entry unverified (observed for STORED and DEFLATED entries).
Encrypted entries, other methods, and a descriptor on a STORED entry fail, as in
Java. An entry whose decoded size cannot fit an array fails with an explicit
size-policy message. Container-level messages are native-specific and name
the artifact; profile-level messages keep Java's exact text.

**Writer profiles.** Options for native `.ironclass` and Bridge JAR output: (a)
STORED, (b) DEFLATED through a pinned zlib, (c) DEFLATED through an Ironwood
deflater. Choose (a), recorded as a writer-profile decision before any
canonical fixture changes, if M5.2's evidence shows Java readers, the `jar`
tool and JAR class loading accept it and the artifact-size, IO and memory
effects are acceptable. If (a) fails a B6 contract, M5 stops with the evidence
for the human's choice of (b) or (c); (b) or any new dependency is never adopted
without that decision. Each entry repeats the bytes Java's ZipOutputStream
writes for a STORED entry with explicit size, CRC and time 0 (version 10, UTF-8
flag, DOS date 1980-01-01, the 9-byte extended timestamp, no descriptor, zero
attributes, no comments), so a native `.ironjar` built from the same payloads
can equal Java's byte for byte. The Java bootstrap's writers are unchanged;
native `.ironclass` bytes therefore differ from Java's, which changes the
enclosing archive bytes and any identity computed over them.

**Publication.** Native `.ironjar` output stages with
`createTempFile(parent, ".ironjar-", ".tmp")`, publishes with `moveReplacing`,
and deletes the stage on every exit; native `.ironclass` output keeps the
baseline's direct write. Bridge's `moveAtomicReplacing` belongs to M6.2.

**Traversal and order.** IronJar's directory inputs, StandardLibrary's type
discovery and IronDoc's source selection use `walkFileTree` visitors with the
existing filters, `Files.isRegularFile` following links as Java's filter does,
and `walkFileTree(directory, depth, false, visitor)` for IronDoc's depth-one
selection. Java's `sorted()` over Paths orders unsigned UTF-8 bytes, not UTF-16
(observed: U+FFFF sorts before U+1F600 as a Path and after it as a String), so
Path-order sorts compare encoded bytes.

**Documentation scans and identifier data.** The regex and split sites become
purpose-specific scans with Java's semantics (`\R` including CRLF, VT, FF,
U+0085, U+2028 and U+2029; unflagged `\s` as space, HT, LF, VT, FF and CR;
`$` before a final line terminator; ASCII-only `(?i)`). The identifier-part
predicate is a range table of Java 21's `Character.isJavaIdentifierPart`
observed for every code point with JDK 21.0.1, the project's established
treatment of observed Character data as default-licensed observations
(STDLIB_STRING_REVIEW), carrying the Unicode 15.0 notice; a checked generator
regenerates it and a test fails when table and JDK disagree. No OpenJDK
source is consulted.

**Discovery.** D269 left archive and class library roots to S6 facilities;
`Installation` gains them with StandardLibrary's order and deduplication.

## Shared machinery and safety

| Changed machinery | Consumers to protect | Paired evidence |
| --- | --- | --- |
| New public `ironwood.util.zip` package and CRC32 | Closed-world pruning (unused code removed), IronDocs for every public member, borrow dispatch through overridden methods | A caller frees its array after an ordinary update; freeing after an update through a retaining override, including whole-array delegation, is rejected; omitted members fail to compile |
| Compiler-private archive, decoder and text helpers | Port reference-bound audit, D163 owners, Files borrowing classification, the M4 publication moves | Inputs freed after each call; results freed by their owner; use after free, double free and retained loans rejected; allocation failures unwind |
| No compiler, IR, runtime or lowering change is planned | Every existing consumer | If one becomes necessary it is recorded here with paired regressions before it is used |

All new sources are original under the default license except the generated
identifier table's Unicode notice; RFC 1951, the PKWARE APPNOTE, the CRC-32
definition and Java 21's public specifications and observed behavior are the
references. No OpenJDK or zlib source is consulted or translated.

## Focused verification selection

New registrations: CRC32 against Java 21 (vectors, every byte, lengths,
incremental and one-shot, offsets, reset, repeated reads, widened and negative
integers, null and range failures with Java's types and text, subclass
dispatch), its ownership pairs in every unfreed mode, omitted-member compile
failures, allocation behavior and the IronDocs check; inflate against Java's
Deflater over every level and strategy and hand-built stored, fixed and dynamic
blocks, overlapping copies, window and block boundaries, and malformed streams
with Java's verdicts; the archive corpus through both container models; Java
writer to native reader and native writer to Java reader for each profile,
native round trips, nested DEFLATED payloads, ZIP64 counts, malformed
artifacts with Java's diagnostics, repeat and reordered-input determinism,
cross-writer `.ironjar` equality, staged-publication failures and allocation
sweeps; the documentation scans, entities and the identifier predicate against
Java over generated corpora and all 65,536 units.

Existing consumers to rerun: `Ironwood archives create list and reproduce
exact bytes`, `Ironwood archives reject malformed paths indexes and payloads`,
`Ironwood archives resolve class-path types lazily`, `caller-owned library
results survive source class archive and tree-shaking round trips`, `IronDocs
comments, CLI, links, and reproducible library documentation`, `compiler
SHA-256 matches Java digests across artifacts`, `borrow dispatch uses exact
overloads defaults and receiver flow`, `borrow dispatch rejects retaining and
unknown receiver flows`, `U5 file tree traversal enforces borrowed visitor
callbacks`, `M4.2 publication moves match Java 21 on one file system across
artifacts` and `compiler port generics spell reference bounds`.

Hosts: this macOS arm64 machine qualifies every phase; the native M5 tests
also run on Linux x86-64 (`estonia`) and Linux arm64 (`miami`). Run
`git diff --check` for every change and `./scripts/check-licenses.sh` for
source changes. Never run an unfiltered suite. Unsafe programs are
compile-only.

Status: initial review recorded before implementation. Each increment below
adds the review made as its phase advances.
