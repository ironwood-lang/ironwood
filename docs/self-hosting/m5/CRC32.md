<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M5.1: public CRC32 (D274)

Scope: B5's public CRC32 slice, the `ironwood.util.zip.CRC32` that IronJar's
STORED entries and the M5.2-M5.3 archive services consume (M0 inventory
patterns API0682 `CRC32()` and API0683 `getValue()`, plus API0684
`Checksum.update(byte[])`, recorded with M5.2-M5.3). Source:
[CRC32.iron](../../../stdlib/src/main/ironwood/ironwood/util/zip/CRC32.iron).
It is one slice of STDLIB_ROADMAP item 5, not its ZIP or GZIP APIs, and the
compiler's MD5 and SHA-256 stay private.

## Behavioral contract review

Reference: the [Java 21 CRC32](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/zip/CRC32.html)
and [Checksum](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/zip/Checksum.html)
contracts and observed JDK 21.0.1 behavior.

| Java-valid call | Java 21 | Ironwood |
| --- | --- | --- |
| `new CRC32()`, `reset()` | value 0 | same |
| `update(int)` with an int, or a byte, short or char widened to int | adds the low eight bits; sign and higher bits ignored | same; long, float and double arguments are invalid in both |
| `update(byte[])` | `Checksum` default: `update(b, 0, b.length)`, dynamically dispatched; null throws NullPointerException | declared on CRC32 with the same delegation; null throws NullPointerException (no helpful-message text) |
| `update(byte[], int, int)` | NullPointerException for null; ArrayIndexOutOfBoundsException `Range [off, off + len) out of bounds for length n` when off or len is negative or off > length - len, before any byte is added; never calls `update(int)` | same type, text, order and state |
| `getValue()` | unsigned 32-bit value in a long; repeated reads equal; later updates continue | same |
| `update(ByteBuffer)` | adds the buffer's remaining bytes | omitted: compile error |
| `Checksum` as a type | CRC32 implements it | omitted: compile error |
| Subclassing | CRC32 is not final; overrides of the range update observe whole-array updates | same |

Inherited Object behavior follows Ironwood's Object, as for every library class.

## Implementation

Original code from the CRC-32/ISO-HDLC definition: reflected polynomial
`0xEDB88320`, initial and final value `0xFFFFFFFF`. The range update processes
eight bytes per step with eight 256-entry tables (2,048 ints) built by the
class initializer: one process-lived allocation on first use. No OpenJDK or zlib
source was consulted.

At `-O3` the first version kept sixteen bounds checks per eight bytes: eight on
the input and eight on the static table, whose length LLVM cannot see, and
Java's wrapping arithmetic keeps LLVM from deriving the input ranges. The
shipped loop tests the table length once per call (never true) and reads each
group's last byte first; the optimized loop then has one input bounds check per
group and none on the tables (inspected with
`opt '-passes=default<O3>' -inline-threshold=1000 -enable-partial-inlining`
on the emitted module). No timing claim is made.

## Ownership

Updates only read the array. With no analysis change, every unfreed mode
accepts freeing the array after `update(data)`, `update(data, 0, 4)` and
`update(data[0])`, also in a program that declares a retaining subclass, and
rejects (`allocation escapes through argument 1 of method 'update'`) freeing
it after an update through a subclass that stores the array, reached directly,
through whole-array delegation and through a CRC32-typed reference. Use after
free and double free of the checksum are rejected.

## Verification

| Test | Result |
| --- | --- |
| `M5.1 CRC32 matches Java 21 across artifacts` | the 675-line transcript of [stdlib_crc32.iron](../../../integration-tests/cases/stdlib_crc32.iron) equals [Crc32Reference.java](crc32-evidence/Crc32Reference.java) from class and archive links at `-O3`; native-only checks pass: the first use allocates the object and the tables, updates, reads and resets allocate nothing, and the 16 allocations of the nine caught failures are the only ones left |
| `M5.1 CRC32 borrows its arrays and rejects retaining overrides` | seven controls in each of the three unfreed modes |
| `M5.1 CRC32 omits the Checksum interface and buffer updates` | importing or naming `Checksum`, `update(ByteBuffer)`, `update(long)` and an int-typed `getValue()` fail to compile |
| `M5.1 CRC32 unwinds every allocation failure` | [stdlib_crc32_failure.iron](../../../integration-tests/cases/stdlib_crc32_failure.iron) unwinds every limit, keeping at most the tables |

The transcript covers the `123456789` check value `0xCBF43926`, every length
0-300 and byte value, ten widened and negative integers plus byte, short and
char arguments, 81 offset and length pairs, every two-piece split of 300 bytes,
repeated reads, reset, 64 MiB streamed through one 64-KiB buffer, null and seven
range failures with the value unchanged afterwards, and three subclasses: a
counting range override (whole-array updates reach it with offset 0), a
whole-array override (ranges unaffected) and a byte override (never called by
range updates).

Rerun on macOS arm64:

```sh
./scripts/test.sh --test 'M5.1 CRC32 matches Java 21 across artifacts' \
  --test 'M5.1 CRC32 borrows its arrays and rejects retaining overrides' \
  --test 'M5.1 CRC32 omits the Checksum interface and buffer updates' \
  --test 'M5.1 CRC32 unwinds every allocation failure' \
  --test 'IronDocs comments, CLI, links, and reproducible library documentation'
```

The handoff run repeats these on Linux x86-64 and Linux arm64.
