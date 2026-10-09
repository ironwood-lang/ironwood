<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M5.2: codec and writer decision (D275)

Scope: B6 9.2's reader and writer choice, made against the frozen
[M5.1 contract](ARCHIVES.md) before any canonical writer fixture changes. It
delivers the compiler-private decoder
[Inflate.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/Inflate.iron)
and the STORED writer
[ZipWriter.iron](../../../compiler/src/main/ironwood/ironwood/compiler/port/ZipWriter.iron);
M5.3 builds the container readers and profiles on them. The Java bootstrap is
unchanged.

## Options and evidence

| Option | Native work | Result |
| --- | --- | --- |
| (a) Ironwood inflate, STORED writers | An original RFC 1951 decoder; a writer that only stores | Chosen: the decoder admits every Java-admitted stream in the corpus and rejects every malformed one; STORED output equals Java's STORED bytes and opens in every Java reader and JAR tool tried |
| (b) Pinned zlib codec | A typed runtime boundary, a codec home with SHA-256 manifests like TLS's, build and notice work; the existing zlib 1.3.1 Bridge support pin is the candidate | Not selected, so no release, flags, license or distribution review is opened; no new dependency |
| (c) Ironwood inflate and deflate | Match search, block selection and encoding | Not justified: no consumer needs compressed native output |

The selected profiles, recorded in D275:

- **Readers** accept STORED and DEFLATED entries in every profile, including
  DEFLATED class payloads nested in STORED `.ironjar` entries, through each
  profile's Java container model, with four explicit native policies: CRC and
  size verified for every entry read, a malformed name reported as an artifact
  error, the array range as an explicit size limit, and native container
  messages beside Java's exact profile messages.
- **Writers** produce STORED entries in Java's STORED spelling for
  `.ironclass`, `.ironjar` and Bridge JARs, each profile in its existing
  order.

## Decoder

Written from RFC 1951 alone; no zlib or OpenJDK source was consulted. A decoder
object owns five Huffman codes (literal/length, distance, code-length, and the
two fixed codes built once), each with a 9-bit lookup table and the canonical
symbol order for longer codes and for an incomplete code's gaps. It reads a
64-bit bit buffer from an input range passed down every call, never stored in
a field, and decodes into an owned scratch array that keeps the largest size
seen: a fixed-size decode copies into the caller's array and allocates nothing
once the scratch is large enough; an unknown-size decode allocates only its
exact result. It reports the stream's exact input length (through the byte
holding the final block's last bit), which a sequential reader needs to find a
data descriptor.

It admits exactly zlib's code sets: a complete code-length code; a
literal/length or distance code that is complete, or a single code of length
1; an empty distance code; a literal/length code that gives the end-of-block
symbol a length; at most 286 literal/length and 30 distance codes. It rejects
block type 3, stored lengths that disagree, oversubscribed and other
incomplete codes, a leading or overflowing repeat, literal/length codes 286
and 287 and distance codes 30 and 31 in use, a distance before the start of
the output, truncated input and output past the declared size.

## Writer

Entries are added in the caller's order (each profile supplies its order and
distinct names) and assembled in one owned buffer; `finish()` writes the
central directory and the end records and returns a fresh copy. Every field
follows the [metadata rules](ARCHIVES.md#metadata-rules-observed-bytes); the
checksum is the public CRC32 (D274). From 65,535 entries it writes Java's ZIP64
end record and locator, with `0xFFFF` counts in the end record (Java's
threshold, observed at 65,534, 65,535 and 65,536 entries). Archives are
limited to the array range, so ZIP64 sizes and offsets never arise.

## Byte, identity and size effects

- A native `.ironjar` from the same `.ironclass` payloads equals Java's archive
  byte for byte: the writer reproduces the frozen `lib.ironjar`.
- Native `.ironclass` and Bridge JAR bytes differ from the Java bootstrap's
  DEFLATED output; decoded entries, names, order and timestamps agree, and
  cross-writer byte equality is not required for these profiles. An archive
  embedding native class artifacts differs from one embedding Java's, and so
  does any identity computed over those bytes; M6.2 records the Bridge cases.
- STORED output, measured over the standard library: 252 class artifacts grow
  from 431,739 to 1,312,492 bytes (3.0 times); the archive from 887,851 to
  about 1,768,604 bytes (2.0 times). Writing and reading STORED entries does
  no compression work, and readers hold whole archives in memory either way.
  No timing claim is made.

## Verification

| Test | Result |
| --- | --- |
| `M5.2 inflate matches Java 21's Inflater on legacy and malformed streams` | [compiler_inflate.iron](../../../integration-tests/cases/compiler_inflate.iron) prints [InflateCorpus.java](inflate-evidence/InflateCorpus.java)'s 194 verdicts from class and archive links: 160 Deflater streams (eight inputs including empty, runs, a 256-byte cycle, random bytes, 300 KB of text and repeated 40 KB windows; levels 0, 1, 2, 4, 6 and 9; default, filtered and Huffman-only; sync and full flushes), four trailing, truncated and empty inputs, and 30 hand-built blocks; 171 accepted with Java's bytes and consumed lengths, 23 rejected. Natively each accepted stream also decodes at an offset inside other bytes and into an exact-size array, fails with a size one byte off either way and for every prefix up to 300 bytes and every 1,009th after, and a repeated fixed-size decode allocates nothing |
| `M5.2 STORED writer equals Java's bytes and opens in Java readers and JAR tools` | [compiler_zip_writer.iron](../../../integration-tests/cases/compiler_zip_writer.iron) reproduces `lib.ironjar` from its decoded entries; equals Java's STORED bytes for `p/A.ironclass`'s entries, Unicode, empty and all-byte entries, an archive with no entries and 65,534, 65,535 and 65,536 entries; the STORED class artifact reads back through `IronClass.read`; a manifest-first STORED JAR opens in `JarFile` and `JarInputStream`, lists with `jar tf`, runs with `java -jar` and loads through `URLClassLoader` |
| `M5.2 inflate and ZIP writer borrow inputs and own their results` | six controls in each unfreed mode: inputs freed after decoding and adding, results freed by the caller; use after free and double free rejected |
| `M5.2 inflate and ZIP writer unwind every allocation failure` | [compiler_codec_failure.iron](../../../integration-tests/cases/compiler_codec_failure.iron) unwinds every limit to its baseline |

The 30 hand-built blocks: an empty and a 65,535-byte stored block, stored
lengths that disagree, a stored block cut short, block type 3, a non-final
block alone; fixed blocks with 258-byte overlapping copies, every length 3-258
and every distance code, a 258-byte copy from 32,768 bytes back, a distance
before the output, literal/length codes 286 and 287, distance codes 30 and 31,
and five consecutive blocks; dynamic blocks that are basic, have an incomplete
literal code, only an end-of-block code (and its unused half), an
oversubscribed code, no end-of-block length, an empty distance code (unused and
used), 287 literal or 31 distance codes, a leading and an overflowing repeat,
an incomplete code-length code, 14- and 15-bit codes, and a cut header.

## Boundaries

- Decoding is one-shot over an in-memory range: every consumer holds the whole
  entry, so B6's incremental-input case is covered by offset independence and
  truncated prefixes, not by a decoder that accepts input in pieces. A
  consumer that streams would need that extension.
- The decoder is not tuned the way zlib is; it serves only legacy DEFLATED
  input, which native writers do not produce.
- No compressor exists; native artifacts are larger, as measured above.
