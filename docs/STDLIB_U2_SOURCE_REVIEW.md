# U2 files and minigrep source review

This review records the API, licensing, ownership, failure, and architecture
boundary for the U2 file-capable command-line slice. The governing policies are
[`LICENSE_MECHANICS`](../LICENSE_MECHANICS) and
[`OPENJDK_PORTING.md`](OPENJDK_PORTING.md).

## Classification and provenance

The U2 library facades, compiler IR and analysis, POSIX runtime mechanisms,
tests, project, scripts, and documentation are original or independently
implemented Ironwood work under
`SPDX-License-Identifier: MIT OR Apache-2.0`.

Java SE 21 public names, signatures, documented behavior, and independently
observed results were the compatibility target. No OpenJDK implementation
body, comment, Javadoc, test, algorithm, or distinctive internal structure was
inspected, copied, translated, or adapted. U2 therefore adds no
OpenJDK-derived source or third-party notice.

The [official Rust Book Chapter 12 application](https://doc.rust-lang.org/book/ch12-00-an-io-project.html)
was reviewed as the behavioral target for `projects/minigrep`: two positional
arguments, literal line search, `IGNORE_CASE`, stdout/stderr separation, and
recoverable argument or file errors. The Ironwood source and tests are
independently structured and do not copy Rust implementation text.

## Supported surface

- checked `IOException`, `EOFException`, and `FileNotFoundException`;
- `Path`, `Paths`, and an internal POSIX `UnixPath` implementation with lexical
  construction, roots, parents, file names, resolution, sibling resolution,
  normalization, absolute conversion, equality, hashing, and ordering;
- `InvalidPathException`, `FileSystemException`, and
  `NoSuchFileException` with their Java-shaped accessors;
- `Files.readAllBytes`, UTF-8 `readString`, `write`, UTF-8 `writeString`,
  `exists`, `isRegularFile`, `isDirectory`, and `size`; and
- the commented `projects/minigrep` application with exact literal search,
  LF/CRLF/final-line handling, supplementary Unicode preservation, optional
  ASCII case-insensitive search, and distinct 0/1/64/74 statuses.

Fixed-arity `Path.of(String)`, `Paths.get(String)`, `Files.write(Path, byte[])`,
and `Files.writeString(Path, CharSequence)` preserve the ordinary Java call
shape without hidden varargs arrays. This follows Ironwood's accepted varargs
exclusion.

## Deliberate boundaries

U2 is a whole-file, POSIX-host tranche for macOS and Linux. It does not yet
provide streams, directory mutation/traversal, temporary files, file times,
permissions, option enums, charset overloads, filesystem providers, or an
`ironwood.io.File` legacy facade. Symbolic-link queries use the host `stat`
default and therefore follow links.

`readString` accepts strict UTF-8 and maps malformed input to a checked
`FileSystemException`; `writeString` rejects unpaired UTF-16 surrogates before
opening or truncating the destination.
Ironwood arrays use signed 32-bit lengths, so whole files larger than that
representable range fail as `File is too large`. Paths are UTF-16 lexical
values whose native spelling is UTF-8; a current working directory that cannot
be represented as valid UTF-8 is rejected rather than silently redirected.

The teaching application's `IGNORE_CASE` mode folds ASCII letters only. Exact
mode preserves arbitrary UTF-16 text, including supplementary characters.
Locale-sensitive and full Unicode case folding remain outside U2, as do regex
semantics and GNU grep options.

## Mechanism and ownership boundary

| Scope | U2 treatment |
| --- | --- |
| Public library facade | Independently implemented Java-shaped validation, error mapping, and checked-exception surface. |
| Path storage | Each non-null result allocates its wrapper and one normalized String directly inside that owner. Fused resolution/absolute conversion avoid intermediate paths and Strings. The destructor and failed-construction rollback reclaim owned storage. |
| Whole-file reads | Bounded native chunks fill one unpublished caller-owned `byte[]` or String directly. The result may resize before publication; file size is a hint, not an assumed snapshot. Malformed UTF-8 returns no partial language object. |
| Whole-file writes and metadata | Immutable String input and byte arrays are borrowed. Arbitrary `CharSequence` input requires one `char[]` snapshot, reclaimed on success, observation failure, malformed text, native failure, and OOM. Validation precedes destination truncation. |
| Compiler/backend | One typed file/path IR family survives exception CFGs, specialization, pruning, loose classes, and archives before lowering to the isolated C ABI. |
| `minigrep` | The program owns and frees its path, file text, environment result, and each printed substring on successful execution. Process-ending error paths retain no open native resource. |

The compiler records audited non-retention contracts for the closed
`Path`/`Files` facade because an abstract `Path.toString()` call alone cannot
prove that native I/O only borrows the receiver. Fresh read and path results
remain visible to ordinary safe-`free` analysis across source, loose class,
archive, and link boundaries.

The third allocation-audit repair stage replaces full-file native staging with
direct result storage, stdio streams with file descriptors, and input-sized
lexical-path scratch arrays with two linear scalar-state passes. Native UTF-8
path spelling and current-directory lookup normally use 4 KiB stack buffers;
longer spellings retain an explicitly tested, reclaimed heap fallback rather
than imposing a new path-length limit. Read/write chunks are bounded at 8 KiB.
Result resizing and optional compaction may call `realloc`, but operate on the
one owned result, not a separate scratch buffer. Failed compaction preserves
the already valid result.

Every open, read, close, malformed-input, file-too-large, and allocation-failure
path closes the descriptor and reclaims any unpublished result or fallback
buffer before returning/throwing. Tests instrument Ironwood's own native
allocator and descriptor calls, including short/interrupted I/O, unknown sizes,
failed growth, and long-path/cwd fallbacks. Managed-count tests cover zero-helper
writes, stable mutable snapshots, two-allocation path results, and both
constructor allocation-failure boundaries. Host libc internals are outside the
instrumented allocation claim. These remain original Ironwood mechanisms; no
OpenJDK implementation was imported.

## Verification boundary

U2 completion requires typed-IR and LLVM inspection; path, byte, UTF-8,
missing-file, directory, permission (where enforceable), malformed, empty,
large-file, and supplementary-Unicode behavior; allocation rollback; native
`-O0` through `-O3`; loose-class and `.ironjar` linking; host-package and IDK
smoke paths; the license gate; and the runnable `projects/minigrep` scripts.
