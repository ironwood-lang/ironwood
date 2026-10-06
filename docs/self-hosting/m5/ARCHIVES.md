<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M5.1: the frozen archive contract

Scope: B6 9.1, 9.3 and 9.4's baseline facts, frozen before any native reader or
writer exists, so M5.2's profile decision and M5.3's services are measured
against recorded Java behavior rather than against a few samples. It records
what the Java baseline (J0 behavior at HEAD, JDK 21.0.1) writes and accepts; it
decides nothing about native policy. Evidence lives in
[archive-evidence](archive-evidence/): the corpus generator
[ArchiveCorpus.java](../../../compiler/src/test/java/ironwood/compiler/ArchiveCorpus.java)
with its byte-exact builder
[ZipBytes.java](../../../compiler/src/test/java/ironwood/compiler/ZipBytes.java),
the frozen verdict transcript [java-verdicts.txt](archive-evidence/java-verdicts.txt)
and the serialization fixtures in [fixtures](archive-evidence/fixtures/).

## Profiles

| Profile | Java writer | Java reader | Publication |
| --- | --- | --- | --- |
| `.ironclass` (IronClass) | ZipOutputStream, DEFLATED with data descriptors, time 0; entries in write order: `META-INF/IRONWOOD.MF`, `META-INF/types.tsv`, optional `META-INF/entry-point`, `source/<file name>` | `ZipInputStream` over the whole file: local headers only | direct write to the destination |
| `.ironjar` (IronJar) | ZipOutputStream, STORED with explicit size and CRC, time 0; entries strictly sorted by String order | `ZipFile`: the central directory, entry data read lazily | staged in the parent, moved into place |
| Bridge `.jar` (BridgeJarArchive) | ZipOutputStream, DEFLATED, time 0; `META-INF/MANIFEST.MF` first, then the other entries sorted | Java's JAR consumers | staged, verified, moved atomically |

## Metadata rules (observed bytes)

The [builder](../../../compiler/src/test/java/ironwood/compiler/ZipBytes.java)
reproduces both Java writer profiles byte for byte, which pins every field:

- **STORED entry** (`.ironjar`): local header version 10, flags `0x0800` (UTF-8
  names), method 0, DOS time `0x0000` and date `0x0021` (1980-01-01 00:00),
  CRC and both sizes in the header, the name, then the 9-byte extended
  timestamp extra field `55 54 05 00 01 00 00 00 00` (flags 1, modification
  time 0), then the data; no descriptor. Central record: made-by `0x000A`,
  version 10, the same flags, method, time, date, CRC, sizes and extra field,
  no comment, disk 0, internal and external attributes 0, the local offset.
- **DEFLATED entry** (`.ironclass`, Bridge): version and made-by 20, flags
  `0x0808`; the local CRC and sizes are 0 and a signed 16-byte descriptor
  (`50 4B 07 08`, CRC, 32-bit compressed and uncompressed sizes) follows the
  raw DEFLATE data (Java's default level and strategy); the central record
  carries the real CRC and sizes and the same extra field.
- **Zero time** is independent of the time zone: Java's `setTime(0)` is before
  1980 in every zone, so it writes the 1980-01-01 DOS value plus the extended
  timestamp's modification time 0.
- **End records**: one end of central directory record with the counts, size
  and offset and no comment. With 65,535 entries or more (observed with 65,540
  STORED entries) Java writes a 56-byte ZIP64 end record (record size 44,
  made-by and version 45, disks 0, both counts, size, offset), a 20-byte
  locator (disk 0, the record's offset, one disk) and an end record whose
  counts are `0xFFFF` and whose size and offset keep their 32-bit values.
  Archives past 4 GiB were not exercised.
- **Text**: every name and text entry is UTF-8. `IRONWOOD.MF` is exactly
  `Ironwood-Class-Format: 1\n` or `Ironwood-Jar-Format: 1\n`; `types.tsv` has
  LF-terminated `<canonical>\t<class|interface>` (class) or
  `<canonical>\t<path>.ironclass` (archive) lines; `entry-point` is the
  canonical name and LF. A class artifact's source entry is
  `source/<file name>` with the unit's exact content; the absolute source path
  is not recorded, so artifacts are location-independent.
- **Order and duplicates**: IronJar sorts entries with String order (`$` before
  `.`, so `p/A$Nested.ironclass` precedes `p/A.ironclass`) and rejects
  duplicates before writing; IronClass writes its fixed order.

## Accepted-input matrix

[java-verdicts.txt](archive-evidence/java-verdicts.txt) records the Java
readers' verdict on 144 generated variants: 65 class artifacts read with
`IronClass.read` and 79 archives read with `IronJar.read` and then
`IronJar.source` for each indexed type (decoded sources are identified by
their SHA-256 prefix; corpus paths are shown under `<root>`). Container-level
messages are java.util.zip's; profile-level messages are the compiler's. By
feature:

| Feature | IronClass (`ZipInputStream`) | IronJar (`ZipFile`) |
| --- | --- | --- |
| STORED with sizes; DEFLATED with a signed or unsigned descriptor or with sizes; mixed methods | accepted | accepted |
| DEFLATE at levels 0, 1, 6 and 9, Huffman-only and filtered strategies, a 143,145-byte multi-block source, empty STORED and DEFLATED entries | accepted | accepted, also as DEFLATED payloads nested in STORED entries |
| Entry and archive comments, unknown and Info-ZIP extra fields, Unix made-by, DOS times, external attributes | accepted | accepted |
| UTF-8 names with or without the UTF-8 flag | accepted, decoded as UTF-8 | accepted, decoded as UTF-8 |
| Malformed UTF-8 name | `IllegalArgumentException` escapes `IronClass.read` | rejected when opened |
| Directory entry | skipped | rejected as an unsafe path (empty last component) |
| Duplicate name | `duplicate entry` | `duplicate archive entry` |
| ZIP64 end records; 65,540 entries | ignored | accepted |
| ZIP64 local extra with `0xFFFFFFFF` sizes | accepted | accepted (local sizes unused) |
| ZIP64 central extra with sizes and offset | not read | accepted |
| 8-byte descriptor on a small entry | rejected: Java reads a 32-bit descriptor below 4 GiB | not read |
| Descriptor flag on a STORED entry | rejected | accepted (central sizes govern) |
| Unsupported method (12), encryption flag | rejected | rejected when opened, even for an unread entry |
| CRC mismatch | rejected | **accepted unverified**, entry and index alike |
| Size mismatch | rejected | central sizes govern: STORED data is cut or extended, then the profile rejects the index text |
| Local name or method differs from the central record | local fields govern | central fields govern; local name ignored |
| Wrong local offset | not applicable | rejected when that entry is read |
| End-record count differs from the records | not read | ignored |
| Central directory missing (end record of size 0) | accepted: the directory is never read | no entries: missing manifest |
| Prefix bytes before the first local header | no entries: missing manifest | accepted: offsets are relative to the located directory |
| Trailing bytes after the end record | accepted | accepted |
| Invalid DEFLATE block; bytes after the final block | rejected | rejected when that entry is read |
| Truncated file (end record cut) | rejected (`Unexpected end of ZLIB input stream`) | rejected with missing manifest: the backward search reached a STORED payload's own end record |
| Empty file; end record only | missing manifest | `zip file is empty`; missing manifest |

Profile rules frozen by the same transcript, with their exact messages:

- **IronClass**: the manifest must equal its format line (CRLF or a malformed
  byte fails); `types.tsv` is required and iterated with `String.lines()` (LF,
  CR and CRLF), blank lines skipped, each line exactly two non-blank tab fields
  with kind `class` or `interface`, no duplicate type, at least one type;
  `entry-point` is trimmed and optional but must name a declared type (an
  interface is accepted); exactly one entry starts with `source/` and ends with
  `.iron`, nested folders allowed; text decodes with U+FFFD replacement, so a
  malformed source is accepted with replacements; unknown entries are ignored;
  canonical names are not validated (`p.Été` is accepted).
- **IronJar**: every name passes the unsafe-path check (empty, leading `/`,
  `\`, NUL, `:`, empty, `.` or `..` components) before the order check, then
  strictly sorted, no directories, no `.ironjar` anywhere; the manifest and
  index decode as strict UTF-8; the index is LF-terminated without CR, sorted,
  nonempty, two fields per line, ASCII Java-identifier components
  (`$` allowed), each mapping to `<canonical with />.ironclass`; every
  non-metadata entry is an indexed `.ironclass` or lives under
  `META-INF/LICENSES/` without the class extension; every indexed entry exists.
  Payloads are read lazily: a malformed or mismatched payload fails only its
  `source` lookup.
- **Declared types** are unordered: `declaredTypes()` iterates a `Map.copyOf`
  whose order varies between JVM runs, so no consumer may depend on it.
- **Missing indexed entries** are checked in the order of a HashSet of their
  paths, so with several missing the one Java names follows String hashes (M0
  hash origin H0075; the corpus's `jar.indexed-missing-two` names `q/C` before
  `p/B`).

M5.3 regenerated the transcript once: two variants' file names had collided
(`a\b` and `a:b` both became `unsafe_a%b`), so names now escape `%`, `/`,
`\`, `:` and NUL distinctly, and the two-missing-entries variant was added.
Every verdict other than those file names is unchanged.

## Serialization fixtures

[fixtures](archive-evidence/fixtures/) holds two sources, the four class
artifacts the Java compiler writes for them (`p.A` with its entry point,
`p.A$Nested`, the interface `p.Shape`, and `q.C`), a license file and the
archive IronJarMain builds from the class directory with that license. Their
SHA-256 identities are in [fixtures.sha256](archive-evidence/fixtures/fixtures.sha256).
The test `M5.1 Java archive writers keep the frozen serialization fixtures`
checks the identities with Java's digest and with the compiler port's
`Sha256` (D267) through
[archive_fixture_identity.iron](../../../integration-tests/cases/archive_fixture_identity.iron),
so no second digest facility is introduced; that Java's readers decode the
fixtures to their sources; that a fresh compile writes the same entries,
metadata and decoded data (compressed bytes may come from another zlib build);
and that IronJarMain rebuilds `lib.ironjar` byte for byte from the directory
and from the four files in reverse order.

## Publication expectations

- **IronClass.write** creates the parent directories and streams the archive
  into the destination itself (`Files.newOutputStream`, truncating). A failure
  part-way leaves a partial file; there is no staging guarantee to preserve.
- **IronJar.create** validates every input and builds every entry before
  writing, creates the parent directories, stages in
  `createTempFile(parent, ".ironjar-", ".tmp")`, moves with `ATOMIC_MOVE` and
  `REPLACE_EXISTING`, falls back to a replacing move only on
  AtomicMoveNotSupportedException, and deletes the stage unless it moved. A
  failure before the move keeps the earlier archive.
- **BridgeJarArchive.publish** validates names and the versioned manifest,
  refuses a destination that exists and is not a regular file, stages
  `.ironwood-bridge-*.jar` in the parent, reopens the stage to verify the entry
  count, sizes and SHA-256 content digests, moves atomically with no fallback,
  and deletes the stage on every exit.
- No publisher syncs to storage (commit 8d847b37).

## What M5.2 and M5.3 decide against this contract

M5.2 selects the native reader and writer profiles: whether native readers
keep each model's verdicts, where they deliberately differ (for example
verifying CRCs that `ZipFile` does not, or reporting a malformed name that
escapes `IronClass.read`), and whether native writers keep DEFLATED output.
M5.3 then compares native readers with this transcript and these fixtures.
