<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M4.1 traversal, scratch paths and discovery

Status: passes ([D270](../../DECISIONS.md#d270---provide-exclusive-temporary-paths-real-paths-and-access-checks)).
B3's scratch, cleanup, real-path and access surface is in
`ironwood.nio.file`, and the port's `TreeDeletion` replaces the reverse-sorted
`Files.walk` cleanup of the M4 consumers. All sources are original and use the
default license; no OpenJDK source was consulted.

## Consumer mapping

| Java demand | Native treatment | First consumers |
| --- | --- | --- |
| `Files.createTempFile(Path, String, String)`, `createTempFile(String, String)` | the same fixed-arity calls | Main.createTemporaryLlvm (S4); IronJar.write and BridgeJarArchive.publish staging (M5/M6) |
| `Files.createTempDirectory(Path, String)`, `createTempDirectory(String)` | the same fixed-arity calls | NativeBackend.linkImage (M4.3 driver); BridgeNativeSupport.deliver, Bridge producers (S7) |
| `Files.deleteIfExists(Path)` | the same call | Main's temporary LLVM file, NativeBackend cleanup, IronJar and BridgeJarArchive staging |
| `Path.toRealPath()` | the same call, links followed | Main.resolvedOutputPath, LlvmToolchain.locateOnPath, IronDoc (M5.4) |
| `Files.isReadable(Path)`, `isExecutable(Path)` | the same calls | LlvmToolchain.validate and locateOnPath, MacNativeTools.validate, BridgeBuildTools |
| `Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS)` | `Files.readAttributesNoFollow(path)` | Bridge destination validation (M6) |
| `Files.walk(root)` sorted in reverse, then `deleteIfExists`/`delete` | `TreeDeletion.deleteQuietly` or `delete` | NativeBackend.deleteTree; BridgeNativeSupport.deliver and BridgeDistributionCommand.distribute cleanup |

The remaining `walk`/`list` consumers (IronJar.collect, StandardLibrary,
IronDoc and the Bridge inputs) belong to M5.x and M6.x and follow B3's
replacement table with `walkFileTree` or a directory stream.

## Behavioral contract review

1. **Overloads and arguments.** Java's temporary-file methods end in
   `FileAttribute<?>...`; a call without attributes binds the fixed-arity
   Ironwood overload with the same argument meaning, and a call with
   attributes fails to compile because FileAttribute is absent. Null
   directories throw NullPointerException; a call with a null first argument
   and two more selects the directory overload in both languages, and
   `createTempDirectory(null)` the prefix overload. Java's `toRealPath`
   without options binds the zero-argument method; `NOFOLLOW_LINKS` fails to
   compile. `deleteIfExists`, `isReadable` and `isExecutable` have one Java
   overload each.
2. **Native-model constraints.** The default directory follows Ironwood's
   documented `java.io.tmpdir` (nonempty `TMPDIR`, else `/tmp`) rather than
   Java's per-OS choice, a recorded convention of the native property subset.
   Names come from a secure source only (the STDLIB_ROADMAP requirement).
   No other constraint applies.
3. **Reduced surface.** Attribute and option overloads are omitted (visible
   at compilation). Java's no-follow attribute read is the distinctly named
   `readAttributesNoFollow`.
4. **Comparison with Java.** The fixture below runs every member on ordinary
   and boundary inputs with Java 21 as the reference, including separator,
   trailing-separator, NUL, Unicode and empty prefixes and suffixes, missing,
   non-directory, read-only, locked and linked directories, dangling and
   looping links, and all six `TMPDIR` settings.
5. **Provenance.** Independent implementations of the Java contracts.

## Results

| Check | Result |
| --- | --- |
| Java differential | 74 cases on a prepared tree print Java 21's lines byte for byte from class and archive links at `-O3`: deletion (files, links, dangling links, empty and non-empty directories, missing parents, a file as parent, a locked parent), temporary files and directories (names, `.tmp`, empty, Unicode and space, trailing separators, `X` runs, every rejected prefix and suffix, missing, non-directory, read-only, locked, dangling and linked directories, a relative directory), real paths (links, `..` after a link, `.`, `..`, the empty path, missing, dangling, looping, non-directory and locked components), access checks and no-follow attributes. The created entries' masked names, kinds and modes (0600 files, 0700 directories) equal Java's. |
| Default directory | With `TMPDIR` unset, empty, nonempty, with a trailing separator, relative and missing, both entries land in Ironwood's `java.io.tmpdir` and Java run with that `java.io.tmpdir` prints the same line; a missing directory fails with NoSuchFileException and no other directory is tried. |
| Allocation failure | Every limit from 0 to 23 unwinds to the live-allocation baseline and leaves the scratch directory empty, including the failure of the created String after native creation; limit 24 succeeds. |
| Native injection | `filesystem_services.c` (runtime under `-O3`): collisions resolved with fresh names for files and directories, exhaustion after 100 attempts, a failed close removing the file, a failed String allocation removing the entry, a stem beyond the stack buffer, trailing suffix separators, real paths through links and the empty path, allocation failure while resolving, access checks; on Linux also the `/dev/urandom` fallback, failure without any source and a non-UTF-8 real path (APFS rejects such names, which the macOS run asserts). |
| Typed IR and ownership | The four new operations and their runtime boundaries appear; each operation keeps its allocation effect in a destructor. In every unfreed mode inputs are freed after each call and results are freed once; double free, use after free, freeing a published result and freeing an alias are rejected, and an unfreed result of each new member is reported. |
| Tree deletion | On eight trees (nested, empty, links to a file and a directory, a missing root, a root link, a root file, a read-only and a locked subdirectory) the quiet and propagating policies leave what NativeBackend.deleteTree and Bridge staging cleanup leave, except the recorded quiet difference on a locked subtree; a propagating failure keeps the failing entry. |
| Unchanged consumers | The 16 U2, U5, filesystem, path, IronDocs and artifact-round-trip tests of the pre-change selection pass. Two programs that call none of the new members (`stdlib_file_mutations`, `stdlib_u5_directory_foundation`) keep identical function bodies at `-O3` against the pre-change compiler under [compare_functions.py](compare_functions.py). |

No lowering of existing code changed and no hot path was touched, so no
benchmark applies.

## Boundaries

- Java's per-user Darwin and Linux `/tmp` defaults are not reproduced; the
  exception of a failed creation names the directory, not a generated path.
- Ironwood's SimpleFileVisitor lacks `throws IOException` on `visitFile` and
  `preVisitDirectory`, so `TreeDeletion` implements FileVisitor directly.
- `new StringBuilder(String)` cannot be freed under the current analysis; the
  fixtures append to `new StringBuilder()` instead.
- A caught exception cannot be freed: each failure ignored by
  `TreeDeletion.deleteQuietly` keeps its exception.
- Linux evidence for these members is recorded with M4.2's host run.
