# ByteArrayOutputStream rendering compatibility review

## T4: deferred text conversion inherits identity rendering

**Status:** Fixed under D115. The no-argument `toString()` now returns a fresh
UTF-8 snapshot of the written bytes, including through Object consumers.
Charset-taking and deprecated high-byte overloads remain absent.

### Evidence and cause

Before the repair, `ByteArrayOutputStream` supplied writes, array snapshots,
reset, size, and no-op close, but no `toString()` override. At the preceding T3
commit `37caa2b`, a native `-O3` reproduction writing bytes 72 and 105 then
printing the stream produced `ironwood.io.ByteArrayOutputStream@f4ad8780`.
The hash is allocation-dependent; the expected captured text was `Hi`.

The U3 source review grouped BAOS text conversion with omitted charset support;
STDLIB.md listed BAOS text conversions as deferred. StringWriter already
provided a fresh text snapshot. The declared omission therefore hid an
inconsistent inherited behavior: the no-argument method remained callable
through Object and returned a different kind of information.

This was a scope and contract-review mistake. Deferring charset machinery was
reasonable, but treating the no-argument override as unavailable was wrong.
Existing byte snapshots and application checks did not exercise the familiar
text-capture idiom. An omission list must account for inherited call resolution,
and analogous byte/character APIs deserve comparison. Documentation alone did
not enforce the intended boundary.

### Exact contract and implementation choice

The [Java 21 BAOS contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/ByteArrayOutputStream.html#toString())
decodes the written contents using the default charset and replaces malformed
sequences. Java's [default charset](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/charset/Charset.html#defaultCharset())
is normally UTF-8 but can be changed by an implementation. Ironwood explicitly
uses fixed UTF-8 for its native text APIs. Alternate default-charset configuration
and explicit charset overloads remain unsupported.

The report's suggested comparison to Files needs qualification:
[`Files.readString`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#readString(java.nio.file.Path))
rejects malformed UTF-8, whereas BAOS replaces it. Reusing strict file decoding
would trade silent wrong text for a new failure. The older argv/environment
decoder also differs: it replaces each invalid byte, rather than consuming a
malformed sequence prefix. Neither decoder can be substituted blindly.

The independent implementation remains small and appropriate. A source override
passes the private buffer and written count to a typed compiler operation. A
native decoder measures the result, allocates one exact-size String, then fills
its UTF-16 units. It borrows storage and creates no byte snapshot, char array,
reader wrapper, or builder. The sequence rules adapt the first-party incremental
InputStreamReader logic. No OpenJDK implementation source or tests were copied
or adapted; source remains `MIT OR Apache-2.0`.

Results are immutable and caller-owned, including for empty buffers. Reset,
growth, later writes, close, and stream reclamation cannot change a prior
snapshot. Allocation failure leaves the buffer intact. Existing D089/D111
ownership metadata lets printing, concatenation, and builder append reclaim
fresh text without changing ordinary subclass dispatch or borrowed overrides.

### Verification and prevention

The focused differential test compares exact UTF-16 units, String length, and
UTF-8 byte length against Java for 71,977 vectors: empty and valid Unicode text,
all single-byte and two-byte inputs, structured longer sequence boundaries, and
deterministic random inputs. This covers NUL, BOM preservation, supplementary
characters, invalid continuations, overlong encodings, surrogate encodings,
out-of-range scalars, and truncated prefixes. Every conversion checks one
allocation and reclaims the result before reusing the stream.

Separate native coverage checks the reported `Hi` output, print/println,
concatenation, builder append, StringWriter consistency, subclass dispatch,
snapshot independence, empty streams, reset, growth, and use after close.
Typed coverage checks fresh ownership, safe stream reclamation before its
snapshot, destructor allocation rejection, and compile-time rejection of the
omitted charset-name overload. An exhausted allocation budget verifies that a
failed snapshot leaves the stream usable and reclaimable. Fixtures compile to
separate class inputs and link at `-O3` with the bundled standard-library archive.

The required [behavioral review](OPENJDK_PORTING.md#behavioral-contract-review)
now names analogous-type comparisons and malformed-input policy alongside the
inherited-default checks added for T3. `AGENTS.md` already requires that review.
The lesson is to choose implementation scope around observable Java calls,
then choose provenance against that contract. Copying an entire class is not a
substitute for this review, and independent implementation is not permission to
discard its familiar behavior.
