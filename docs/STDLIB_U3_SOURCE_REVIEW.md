# U3 streaming API, provenance, and ownership review

Implementation baseline recorded before source changes. U3 uses independently
implemented Java-compatible facades and original typed compiler/runtime
mechanisms, under `MIT OR Apache-2.0`. No OpenJDK implementation source is
translated or adapted. The small buffering loops and incremental UTF-8 state
machines are designed for retained storage and explicit reclamation; importing
the JVM's stream/charset machinery would not simplify this boundary.

Behavioral references are the Java 21 API specifications for
[InputStream](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/InputStream.html),
[InputStreamReader](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/InputStreamReader.html),
[BufferedReader](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/BufferedReader.html), and
[ByteArrayOutputStream](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/ByteArrayOutputStream.html).
Tests are independently written, with Java 21 differential checks for shared
behavior. Java is not an oracle for Ironwood ownership or standard-stream close.

## Source classification and retained state

Every changed source below is independently implemented or an original Ironwood
mechanism under the default dual license. The behavioral references above do
not authorize copying implementation bodies, comments, or tests.

| Source group | Implementation choice | Retained state and results |
| --- | --- | --- |
| Closeable, InputStream, OutputStream | Small Java-shaped contracts and loops | No retained helper state in byte bases. |
| Reader, Writer | Small Java-shaped contracts and scalar bridges | One lazy reusable char slot for subclass scalar bridging. |
| FileInputStream, FileOutputStream | Ironwood descriptor lifecycle over typed native operations | One descriptor; no retained pathname or native buffer. |
| BufferedInputStream, BufferedOutputStream | Independent bounded bulk buffering | One owned byte buffer; borrowed underlying object. |
| ByteArrayInputStream | Independent indexed array view | Borrows the source array; no copy. |
| ByteArrayOutputStream | Independent growth and copying | Owned reusable buffer; fresh array snapshots and, under D115, fresh UTF-8 String snapshots. writeTo observes the original size, including self-copy. |
| StringReader, StringWriter | Independent indexed input and growing output | Reader borrows text; writer owns reusable char storage and returns fresh String snapshots. |
| InputStreamReader, OutputStreamWriter | Independent incremental UTF-8 state machines | Owned byte buffer and scalar pending state; borrowed ordinary source/sink, owned file child for Path construction. |
| BufferedReader, BufferedWriter | Independent character buffering and line framing | Owned character buffer; reader also retains reusable line storage and returns fresh lines. |
| StandardInputStream, System, PrintStream | Original immortal-object and native standard-stream boundary | Process-owned singletons; no per-call managed scratch for binary I/O. |
| Files, StreamSupport, FileNotFoundException | Small factories, validation, exception ownership, and private intrinsic bridge | Factories return owned graphs; open exceptions own copied messages. |
| Typed IR, analysis, backend, runtime | Original closed-world proof and POSIX boundary | Compiler-only borrow edges; bounded native stack scratch, reclaimed fallback for long paths. |

## API and deliberate boundaries

Implement `Closeable`, abstract byte and character stream bases, file byte
streams, buffered byte and character streams, byte-array and String streams,
UTF-8 input/output adapters, four `Files.new*` factories, and immortal
`System.in`. Reader core includes scalar/array reads, skip, ready, close;
Writer core includes scalar/array/String writes, flush, close. BufferedReader
adds readLine; BufferedWriter adds newLine. In-memory output supports size/reset
and independent snapshots. PrintStream becomes an OutputStream with binary
writes and flushing close. getEncoding returns borrowed "UTF8" while open and null after close.
PrintWriter stays deferred: U3 does not require a second
printing/error-state abstraction. Charset overloads, mark/reset, random access,
networking, and floating formatting are outside this slice.

Reads return unsigned bytes or UTF-16 units, -1 at EOF, and 0 for an empty
request. Positive-length reads may be partial. D124 replaces U3's original
one-scalar generic InputStream boundary with Java's filling default. Failure on
the first scalar read propagates; a later IOException returns the partial byte
count. The base cannot reclaim a subclass-supplied throwable, which retains
ordinary exception-object ownership. Built-in streams retain their bulk
overrides. `Reader.read()` throws if a subclass reports zero for a
positive-length request, enforcing the subclass progress contract. Native reads
retry EINTR; writes
complete the requested range or
throw, possibly after partial external output. Available/ready report buffered
or immediately available input, never total
input length. An encoded prefix can still require further bytes to complete a
UTF-16 unit; ready is not an EOF test.

UTF-8 adapters replace malformed input with U+FFFD and unpaired output surrogates
with ASCII '?', carrying incomplete sequences/pairs across calls. File buffered
factories use strict UTF-8, matching Files' existing checked malformed-input
contract. LF, CR, and CRLF terminate readLine; the returned line omits terminators.
BufferedWriter.newLine writes LF on supported POSIX hosts. Flush drains retained
output without fsync; closing output drains then closes even if draining fails.
Double close is harmless; file/wrapper/StringReader I/O after close throws
IOException. Byte-array streams and StringWriter remain usable after no-op close.
Standard-stream close is a no-op (output close flushes), including when reached
through a wrapper. Wrapper close still closes that wrapper.

## Ownership and native boundary

Close releases descriptors, never managed objects or retained arrays. Free
invokes source destructors for proven-owned buffers, never closes descriptors.
Ordinary wrapper constructors borrow their underlying object and cascade close;
the caller closes/frees the outer wrapper before freeing the borrowed object.
ByteArrayInputStream and StringReader borrow their source. Output snapshots and
readLine results are fresh caller-owned allocations. BufferedReader retains and
reuses line-building storage. No per-read/write helper allocation is intended.

File factories build owned graphs inside constructors, with all buffers
allocated before opening the descriptor as the last fallible acquisition.
Separate private owned and borrowed fields preserve compiler-visible provenance;
destructors reclaim only owned fields. Path factory constructors are explicit
Ironwood extensions where needed to build the graph without ownership transfer.
Failed construction rolls back only managed state and cannot strand a descriptor.

Typed stream IR exposes open/read/write/available/close and standard output byte
operations through a private standard-library bridge. Native code handles only
POSIX I/O, range-safe array access at the boundary, error codes and bounded path
spelling using U2 helpers. File descriptors never enter the public API. No native
heap buffer is retained. Blocking streams are single-threaded; concurrency and
cross-platform release claims remain outside host verification.

The byte-array constructors validate offset/length rather than silently clipping
an oversized range; empty reads consistently return zero, including at EOF.
Negative byte skip returns zero; StringReader supports backward skip within its
text. BAOS's charset-taking toString overloads, StringWriter.getBuffer,
Appendable/CharBuffer overloads, channels/descriptors, mark/reset, and public
charset objects are omitted. U3 originally supplied only toByteArray and
StringWriter's fresh toString snapshots. D115 adds the no-argument BAOS override
after the [T4 review](STDLIB_BYTE_STREAM_REVIEW.md) found that its deferral left
Object's identity rendering callable. The new private typed boundary decodes
the written prefix into one exact-size String, replacing malformed UTF-8
without helper buffers. Writer/Reader default scalar bridges lazily retain one char slot;
built-in overrides use scalar state without allocating that slot.

D122 subsequently implements mark/reset, the live StringWriter buffer,
data/random-access streams, and other selected IO compatibility members. See
`STDLIB_COMPATIBILITY_EXPANSION_REVIEW.md` for their current contracts.

`Files.isSameFile` is a focused U3 addition: binary cp must reject an existing
source alias before truncating its destination. It compares host device/inode
identity, following symlinks. This does not introduce traversal, mutation APIs,
or a general random-access surface. Streaming file opens report checked
FileNotFoundException (including permission/directory open failures); read/write/
post-close failures report IOException. FileNotFoundException owns its copied
message, so a failed open never retains a caller's path text.

## Compiler proof refinements

Non-reference-returning calls can borrow retained storage when every possible
implementation of the resolved call proves non-retention. D096 refines D094's
initial broad join using typed overload/default selection and receiver flow;
unrelated retaining implementations do not by themselves invalidate a borrow.
Primitive arraycopy
cannot introduce reference aliases. Reference results still use symbolic return
analysis. Unknown or retaining overrides remain conservative diagnostics.

Constructor-retained arguments gain an explicit proof edge only when retained
solely in a private, encapsulated field. Freeing the wrapper releases that edge;
closing does not. Publishing a wrapper publishes its dependencies. Snapshotting
and merging the edges preserves the constraint through exceptions and cleanup.
Constructor delegation propagates retained fields; unresolved delegation or
superconstructor arguments remain conservative. Fresh factory results now join
the same ownership snapshots as source new, fixing repeated generated finally
copies without allowing a real double free.

## Required verification

Native O0-O3 behavior, partial/interrupted I/O, malformed/chunk-boundary Unicode,
flush/close failures, failed constructors, caller reclamation and alias rejection,
steady-state allocation counts, descriptor stress, source/class/archive/separate
link reconstruction, dead-code elimination, host package/IDK smoke, full suite,
license audit, and useful commented cat/wc/cp/interactive applications.

Short-circuit and conditional expressions use their typed CFG ownership joins:
unchanged allocations remain reclaimable, while conditional publication,
live aliases, incompatible identities, and partial frees remain rejected.
Fresh factory results participate in those joins and generated finally snapshots
on the same basis as source allocations.

A constructor's possible publication of a caller argument is recorded before
its exceptional edge. Throwing after publication cannot make that argument
reclaimable in a catch block. Receiver-only borrows are installed after success;
failed non-publishing construction rolls back the wrapper and leaves its caller's
argument available for reclamation.
