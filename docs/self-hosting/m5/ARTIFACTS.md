<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M5.3: artifact integration (D276)

Scope: B6's artifact services on the profiles D275 selected, with M4.1/M4.2's
filesystem services for staging and publication. They are prerequisites for
S6's ports of IronClass, IronJar, SourceSetLoader's class-path loading,
StandardLibrary's discovery, Main's class outputs and IronJarMain, not those
ports. The Java seed is unchanged.

| Port source | Role |
| --- | --- |
| [ZipStream.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/ZipStream.iron) | IronClass's container model: local headers in order (`ZipInputStream`) |
| [ZipArchive.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/ZipArchive.iron) | IronJar's container model: the central directory (`ZipFile`) |
| [IronClassArtifact.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/IronClassArtifact.iron) | IronClass.read and IronClass.write (STORED, direct write) |
| [IronJarArchive.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/IronJarArchive.iron) | IronJar.read, source and create (STORED, staged publication) |
| [TextList.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/TextList.iron), [ArchiveEntries.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/ArchiveEntries.iron), [FileCollector.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/FileCollector.iron) | Owned name lists, entries under construction, B3's `Files.walk` replacement |
| [Installation.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/Installation.iron), [LibraryRoots.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/LibraryRoots.iron), [LibraryTypes.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/LibraryTypes.iron) | StandardLibrary.discover's archive and class roots and owned types |

## Behavior against the Java baseline

Each profile keeps Java's checks, their order and their messages; the
[M5.1 contract](ARCHIVES.md) is the reference. The recorded differences:

| Case | Java baseline | Native services |
| --- | --- | --- |
| CRC or size mismatch in an IronJar entry | `ZipFile` returns the bytes unverified | container failure (D275 policy) |
| Malformed UTF-8 entry name in a class artifact | unchecked IllegalArgumentException escapes `IronClass.read` | container failure |
| Container-level defects | java.util.zip's messages | `invalid ZIP data in <archive>: ...` messages |
| Several missing indexed entries | the first in HashSet order is named (M0 hash origin H0075) | the first in index order is named |
| Traversal failure inside an archive input or library root | `Files.walk`'s stream throws UncheckedIOException | an IOException: creation reports it, discovery skips the root |
| Class artifact bytes | DEFLATED | STORED (D275); `.ironjar` bytes equal for equal payloads |

## Ownership and failure design

Archive bytes and caller arrays are only ever parameters. Readers and the
collector work inside their constructors, whose rollback releases a partly
built object on any failure, including allocation failure. Three conservative
rejections shaped the code without an analysis change: a field-element store
whose value or index comes from a call leaves the field's ownership uncertain
(the value is computed into a local first); an array used as a copy
destination cannot be freed in the copying frame (copies come from
`Bytes.slice` or the decoder's fresh result); and a String array parameter
exposes its elements to every analyzed call (inputs are `TextList` owners).

The allocation-failure sweep found a gap in `Files.walkFileTree`: an
allocation failure while a directory's stream or traversal-loop check was being
built skipped the IOException handler and kept that directory's attributes, two
allocations per directory (limits 5/6, 12/13 and 24/25 of a bare walk over the
fixture classes). The walk now releases them before the failure propagates
(D276), and `M5.3 file tree walks unwind allocation failure while opening
directories` sweeps a tree with nested directories and a directory link in
both link modes.

## Verification

| Test | Result |
| --- | --- |
| `M5.3 native readers reproduce the Java verdicts on the archive corpus` | the 144 variants regenerated from the M5.1 generator equal the frozen transcript; [compiler_archive_reader.iron](../../../integration-tests/cases/compiler_archive_reader.iron) prints Java's verdicts, profile messages exactly and container failures as failures, apart from the five recorded policy cases, from class and archive links |
| `M5.3 native readers decode Java-written class artifacts and archives` | all 252 Java-built standard-library class artifacts and the four frozen ones give Java's sorted types, entry point, source path and source digest; the standard-library archive (279 entries, DEFLATED payloads in STORED entries) and the frozen archive list and decode every payload as Java does |
| `M5.3 Java readers decode native-written class artifacts and archives` | [compiler_archive_writer.iron](../../../integration-tests/cases/compiler_archive_writer.iron) rewrites all 252 class artifacts: each equals Java's STORED spelling of its entries and reads back through `IronClass.read`; the native archive of Java's class directory with the archive's 25 license files equals Java's `ironwood-stdlib.ironjar` byte for byte; the native archive of the native class directory reads back through `IronJar.read` with every source equal |
| `M5.3 native archives round-trip and stay deterministic` | two directory builds and one from the four class files in reverse order all equal the frozen `lib.ironjar`; a native class artifact reads back natively with its type, entry point and source |
| `M5.3 native archive creation reports Java's diagnostics` | 14 invalid creations (no inputs, missing, nested, non-class and misnamed inputs, an empty directory, invalid and undeclared canonical paths, a malformed class, a duplicate type, missing, directory, class-extension and duplicate licenses) print `IronJar.create`'s message and publish nothing |
| `M5.3 staged publication replaces archives and leaves nothing behind` | an earlier archive is replaced; a failed creation and a failed move onto a non-empty directory keep the destination; missing parents are created; no `.ironjar-*.tmp` stage remains; class artifacts are written directly, replacing an earlier file |
| `M5.3 archive services borrow inputs and own their results` | seven controls in every unfreed mode: lent sources cannot be freed, payloads outlive their archive, use after free and double free are rejected, `TextList` inputs stay freeable |
| `M5.3 archive services unwind every allocation failure without leftovers` | [compiler_archive_failure.iron](../../../integration-tests/cases/compiler_archive_failure.iron) unwinds every one of its 769 allocation limits to the baseline, with no staged archive left |
| `M5.3 library discovery matches Java's archive class and source roots` | [compiler_library_discovery.iron](../../../integration-tests/cases/compiler_library_discovery.iron) equals [LibraryReference.java](artifact-evidence/LibraryReference.java) in eight layouts (checkout, installed from outside and inside the checkout, set, empty and blank `IRONWOOD_STDLIB_HOME`, an unreadable archive, an unknown location): roots, 267 owned types in the checkout, `package-info` names kept, blank names skipped |
| `M5.3 file tree walks unwind allocation failure while opening directories` | [stdlib_walk_failure.iron](../../../integration-tests/cases/stdlib_walk_failure.iron) unwinds every limit in both link modes |

Existing consumers rerun and passing: the three `Ironwood archives` tests,
`caller-owned library results survive source class archive and tree-shaking
round trips`, both M5.1 contract tests, `M4.2 publication moves match Java 21
on one file system across artifacts`, `M4.1 filesystem services unwind every
allocation failure without leftovers`, `compiler tree deletion matches the
Java cleanup policies across artifacts`, the three U5 traversal tests,
`compiler installation inputs match Java discovery across layouts and
artifacts`, `compiler backend helpers unwind every allocation failure`,
`compiler port generics spell reference bounds` and the IronDocs test.

## Peak memory

Native readers and writers hold whole archives in memory. Peak resident set
sizes on macOS arm64 (`/usr/bin/time -l`, `-O3` links of the reader and
writer drivers, inputs staged under a `.noindex` directory):

| Workload | Input | Peak RSS |
| --- | --- | --- |
| Read one small class artifact | 0.6 KB | 1.9 MB |
| List the 65,545-entry ZIP64 archive and decode its two payloads | 9.6 MB | 24.1 MB |
| Read an archive whose license entry is 128 MiB (STORED) | 128.0 MiB | 136.3 MB |
| Read a class artifact with a 64 MiB DEFLATED source | 7.0 MB | 345.3 MB |
| Create an archive with a 128 MiB license | 128.0 MiB | 404.9 MB |

A large DEFLATED source is held as the decoder's scratch, its exact copy and
the UTF-16 text; archive creation holds the license bytes, the entry contents
and the assembled archive. Standard-library inputs are a few megabytes.

## What S6 still does

Port IronClass.write's AST derivation (declared types, kind and entry-point
detection), SourceSetLoader.locateClass and StandardLibrary.locate onto these
services and the port's SourceFile, IronJarMain's command line, and Main's
class outputs; compare each with the Java tools on their own fixtures.
