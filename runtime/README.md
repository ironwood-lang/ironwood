# Runtime


GC5 migration-branch status: normal executables use the single exact managed
heap and explicit synchronous Memory.collect(). Source reclamation and managed
destruction are removed. Historical manual-heap descriptions below are superseded
by [the GC5 contract](../docs/MEMORY.md). Native bridge/shared-library output remains refused
until GC6. Packaging/distribution and other-target qualification remain GC9 work.
The selected GC SDK is staged at toolchain/ironwood-gc, with verified source,
licenses, root/owner ABIs and exact build identities.

The isolated bootstrap runtime exposes these C ABI functions:

- `ironwood_allocate`, which uses the completely zeroed collector domain, installs
  the compiler-provided type descriptor, and raises the compiler-provided
  immortal allocation-failure object when source allocation fails;
- `ironwood_allocate_array`, which receives a compiler-validated nonnegative
  element count, defends the total-size calculation, records the length and
  compiler-provided descriptor and element size/kind, zero-initializes the
  array elements, and uses the same catchable failure boundary;
- `ironwood_allocation_count`, which snapshots a process-wide count
  incremented exactly once after each successful object, array, or
  runtime-created String allocation;
- `ironwood_live_allocation_count`, which reads collection-time cached live allocations plus
  successful allocations since that collection; counter reads never collect;
- `ironwood_identity_hash_code`, which returns zero for null and otherwise a
  stable mixed allocation-identity hash without virtual dispatch;
- `ironwood_system_arraycopy`, which validates two exact-compatible arrays and
  their ranges, then copies primitive or reference slots with overlap-safe
  `memmove`;
- `ironwood_object_hash_code`, which returns a stable mixed identity hash;
- `ironwood_object_to_string`, which allocates the default qualified-type and
  hexadecimal-identity string;
- `ironwood_string_char_at`, `ironwood_string_equals`,
  `ironwood_string_hash_code`, `ironwood_string_copy`,
  `ironwood_string_from_chars`, `ironwood_string_from_char_range`, and
  `ironwood_string_from_range`, which implement unchecked UTF-16 code-unit
  access, Java content operations, and exact-size immutable copies below
  compiler-owned null/range checks;
- `ironwood_string_from_integer` and `ironwood_string_from_character`, which
  fill one fresh String directly from primitive values using bounded scalar
  state, with no temporary managed array or native heap scratch;
- `ironwood_string_concat`, which consumes compiler-emitted typed operands,
  computes and validates their exact converted UTF-16 length, and creates one
  immutable ordinary String without a hidden builder or backing allocation;
- `ironwood_process_arguments`, which excludes the executable name, decodes
  native UTF-8 arguments into immutable UTF-16 Strings, and returns the typed
  `String[]` passed to the source entry method;
- `ironwood_system_getenv`, which converts one validated environment value to a
  fresh UTF-16 String and releases native scratch storage, plus
  `ironwood_current_time_millis` and `ironwood_nano_time` for realtime and
  monotonic integer clocks;
- `ironwood_parse_float` and `ironwood_parse_double`, which normalize validated
  Java-shaped UTF-16 numeric text in bounded stack storage and return primitive
  IEEE values without managed or Ironwood-owned native heap scratch;
- `ironwood_file_read_all_bytes`, `ironwood_file_read_string`,
  `ironwood_file_write_bytes`, `ironwood_file_write_string`, `ironwood_file_write_chars`,
  `ironwood_file_kind`, and `ironwood_file_size`, which implement U2
  whole-file and metadata operations with strict UTF-8 conversion, exact
  language results, checked-error categorization, and closed native handles;
- the U2 lexical path helpers, which normalize, absolutize, inspect, and
  resolve POSIX path text without adding filesystem providers or runtime class
  loading;
- `ironwood_stream_open`, `ironwood_stream_read_byte/read_bytes`,
  `ironwood_stream_write_byte/write_bytes`, `ironwood_stream_available`, and
  `ironwood_stream_close`: original descriptor-only U3 boundaries. Open/read/write
  retry EINTR where appropriate; writes complete or report failure. Close is
  attempted once; source state prevents descriptor reuse on repeated close.
  There are no retained native heap buffers. UTF-8/buffering/lines live in source;
- `ironwood_stream_print_byte/print_bytes` share stdout/stderr's stdio error state
  with textual PrintStream output, preserving binary bytes and ordering;
- `ironwood_file_same` compares stat device/inode identity for cp's alias guard;
- `ironwood_directory_open`, `ironwood_directory_has_next`,
  `ironwood_directory_next`, and `ironwood_directory_close` retain one opaque
  POSIX directory handle with native lookahead and produce strict-UTF-8 entry
  names for the source-level closeable stream;
- `ironwood_file_read_attributes` returns one unpublished primitive metadata
  result for source-level basic file attributes, following links only when the
  typed call requests it;
- `ironwood_file_create_temp_file` and `ironwood_file_create_temp_directory`
  create an entry exclusively (mode 0600 or 0700) under a name with a secure
  random decimal value (`arc4random_buf`, or the raw `getrandom` syscall with a
  `/dev/urandom` fallback on Linux) and remove it if the result String cannot
  be allocated; `ironwood_file_real_path` resolves with `realpath` into a stack
  buffer; `ironwood_file_access` is an advisory `access` check (M4.1, D270);
- `ironwood_file_move_atomic`, `ironwood_file_move_replacing` and
  `ironwood_file_move_exclusive` keep three publication guarantees apart:
  `rename`, `rename` with a regular-file copy only across file systems, and
  `renamex_np(RENAME_EXCL)` or the raw `renameat2(RENAME_NOREPLACE)` syscall
  with an explicit unsupported result, never a check-then-rename (M4.2, D271);
- `ironwood_process_run` launches a program by absolute path with `fork` and
  `execv`, encoding every argument before forking, redirecting the merged
  output to a file the parent opens before forking, reporting pre-exec failures through a
  close-on-exec pipe and always reaping the child (M4.3, D272);
- `ironwood_print_stream_write`, `ironwood_print_stream_flush`, and
  `ironwood_print_stream_check_error`, which select the compiler-emitted
  stdout/stderr channel and synchronously write String or primitive payloads;
- legacy `ironwood_stdout_println`, retained for the earlier typed instruction
  boundary while current PrintStream calls use the generalized stream ABI;
- `ironwood_throw`, which accepts a compiler-validated non-null language object,
  wraps it in a private
  `_Unwind_Exception`, and starts native propagation;
- `ironwood_exception_take`, which transfers a landed language object and
  destroys its native wrapper; and
- `ironwood_exception_caught`, which releases the bounded emergency failure
  state after the implicit allocation error reaches a source catch; and
- `ironwood_exception_add_secondary`,
  `ironwood_exception_secondary_count`, and
  `ironwood_exception_secondary_at`, which retain and expose ordered exceptions
  raised by `finally` while an earlier primary exception is propagating,
  flattening completed nested-cleanup sequences onto that original primary;
- `ironwood_trace_register`, which receives immutable source-site and optimized
  function-address metadata at executable startup or bridge image bootstrap.
  On macOS, registration locates the image containing the source-site table,
  so a shared image uses its own pseudo-probe section. Stack capture uses native
  unwinding and LLVM pseudo-probe decoding only on the exception path; and
- `ironwood_uncaught_exception`, which reports the concrete qualified type,
  optional message, and captured source trace for the primary and each direct
  secondary exception in order, then exits with status 1.

The internal Java Bridge fixture path uses `ironwood_bridge_snapshot_failure`
under a separately protected typed entry region, after ordinary catch cleanup.
It copies the exception type and up to 32 immutable source-site pointers into
adapter-owned stack storage, reporting unavailable/truncated traces explicitly.
It performs no allocation or native source getter call. The image must remain
loaded while the adapter consumes those pointers. The private transport in
`include/ironwood_bridge.h` is not a public API or a complete exception translator.
The private `ironwood_bridge_copy_string` helper copies a caller-owned UTF-16
buffer into one ordinary String allocation. Only a protected typed entry invokes
it, supplying the image's implicit allocation-failure context. Length -1 denotes
null; other lengths come from checked JNI transport. The compiler must prove
each copied argument temporary before generating its nonthrowing raw release.
Exceeding the native UTF-8 byte-length representation raises catchable OOM before
allocating the copy; it does not take the existing internal abort-only helper path.
Fault-injection and emergency-state accessors exist only in ignored test copies
of the runtime, never in production payloads.

Feature 105 implements bounded catchable source-allocation exhaustion using one
compiler-emitted immortal `OutOfMemoryError` and runtime-private emergency
delivery storage. Object, array, `Object.toString()`, `Throwable.toString()`, String
copy/character/range construction, `ByteArrayOutputStream` UTF-8 snapshots,
`String.fromChars(...)`,
`String.fromRange(...)`, integer/character formatting, environment conversion,
and dynamic String concatenation boundaries pass that immortal object to the
runtime. A failed size calculation or native allocation raises it without an
ordinary allocation and without incrementing `System.allocationCount()`.
Runtime-private storage guarantees the exact failing frame, up to 63 additional
frames plus an explicit truncation marker for the emergency singleton, and one
primary/secondary
association while the implicit error is active. A second source allocation
failure before that occurrence reaches a catch terminates deterministically.
Pre-entry argument construction and runtime-private bookkeeping remain outside
the catchable contract.

U2 file/path calls keep native path encodings, bounded I/O chunks, and host error
state behind this boundary. Native scratch storage is released and descriptors
are closed before success, mapped I/O failure, malformed UTF-8, oversized-file,
or language-allocation failure returns to generated code. Whole-file reads
produce one caller-owned `byte[]` or String; writes and metadata calls borrow
their language arguments and retain no source reference. String writes validate
UTF-16 before opening the destination, preventing malformed input from
truncating or partially replacing an existing file.

Ordinary path encodings and current-directory lookup use 4 KiB stack buffers;
longer host spellings use a reclaimed heap fallback. Lexical normalization uses
two linear passes with scalar state, and fused sibling/absolute resolution
creates only the final String. Reads fill one unpublished result directly from
8 KiB chunks, grow it when needed, and compact it opportunistically before
publication. Allocation counts record that one language object, not its native
resizes; no full-file scratch copy or stdio buffer is allocated by this path.
Unknown/changing sizes, partial/interrupted I/O, malformed UTF-8, and failed
allocation/close paths retain deterministic cleanup.

Floating parsing keeps its String borrowed and performs no successful-path
managed allocation; Ironwood's parser and runtime bridge issue no `malloc` or
`realloc` call. The source facade owns malformed-input exceptions; the native
boundary receives only fully validated numeric text.

`IRONWOOD_ALLOCATION_LIMIT=<n>` is a runtime-private diagnostic hook. It permits
`n` successful catchable source allocation boundaries and then fails later
ones; pre-entry allocations neither consume the limit nor fail because of it.
The compiler suite and `examples/allocationfailure` use `0`, `1`, and `2` to
exercise exact failure order. This variable is not an Ironwood source API,
memory quota, or recovery guarantee.

Feature 100 safety checks are compiler-owned rather than C runtime entry points.
Typed null, array-bounds, and nonnegative-length predicates branch to ordinary
exception allocation and the existing throw/unwind path, making the failures
catchable while keeping successful access on generated-code paths.

The native backend compiles this runtime into a separate object for every link.
Ordinary functions execute no stack-trace bookkeeping instructions. LLVM
pseudo probes contribute read-only binary metadata only, and native unwind plus
metadata decoding begins only when a Throwable captures a trace. On Linux the
backend maps the probe section with `llvm-objcopy`; Mach-O maps LLVM's probe
section directly.
The compiler now emits object type descriptors, type-membership tables, unified
class/interface dispatch tables, qualified type names, a null-safe `instanceof`
helper, and LLVM landing pads directly in the program's LLVM module. Nominal
catch selection remains generated closed-world code; the C runtime has no class
registry, runtime loading, reflection, or mutable registration.

Automatic static initialization is likewise compiler-emitted rather than a C
runtime registry. Each retained type receives private state and failure globals
plus an ensure routine that invokes typed-IR prerequisite and `<clinit>` code.
The mechanism is allocation-count neutral; source allocations performed by an
initializer remain ordinary Ironwood allocations. An initialization failure
stores and rethrows the exact language exception object. Entry initialization
participates in the same on-demand native unwind and pseudo-probe decoding as
other source calls.

Objects carry one dynamic type pointer before their base-first fields. The
actual type metadata embeds the engine descriptor and compiler-derived layout,
followed by dispatch and membership data. Deprecated destructor/rollback slots
remain inert ABI scaffolding and are always null. The allocator installs the
dynamic type into completely zeroed storage before constructors run. Published
failed constructions remain valid managed graphs. Throwable's reserved Object
field points to exact managed metadata; trace buffers and secondary nodes use
internal sidecar layouts. Immutable image literals and emergency cells have
registered exact native bases; only reachable emergency graphs retain children.
Array and String tails preserve their existing physical source/runtime layouts.

Normal allocation, native ownership and exact collection follow
[MEMORY.md](../docs/MEMORY.md). There are no runtime managed frees, source
rollback/destructor helpers or trace-release lowering. Native exception wrappers
and native acquisition scratch retain explicit native cleanup. Trace metadata,
secondary nodes and trace buffers are internal managed sidecars, excluded from
language allocation counters. Source Throwable APIs and optimized trace rendering
remain. The private runtime ABI is bound to the GC5 SDK and compiler inputs.

## Fixed-convention String transformations

D117's direct-result repeat, literal replace, String-array join and case
conversion allocate one String without heap scratch. Casing uses fixed en_US
Unicode 15.0 behavior through `src/ironwood_case.c` and generated data in
`src/ironwood_case_data.h`. Those two files are GPLv2 with the Classpath
Exception; other runtime files retain their own licenses. The helper is compiled
separately and unreachable code/data are discarded at link time. No Locale
objects, environment locale discovery or normalization subsystem are present.
See `docs/STDLIB_STRING_REVIEW.md` for generation, ownership and provenance.


## TCP component

Original `src/ironwood_tcp.c` implements the POSIX TCP and resolver boundary.
Successful/status results use the low signed 32 bits of an int64 value; a
captured errno occupies the high 32 bits. EOF and would-block stay distinct.
Native functions return status instead of raising language exceptions. Bulk
operations use the caller array payload directly; scalar I/O uses one stack
byte. Endpoint queries fill compiler-selected primitive fields, with no public
raw descriptor or managed-layout dependency. TCP I/O allocates no heap payload
storage and retains no caller buffer. OS lookup owns an addrinfo list until
explicit release. Hostname encoding uses a bounded stack buffer, with an owned
heap fallback for long names. Result iteration writes primitive address/scope
fields through typed output pointers and a private sequential cursor.

Attempts and readiness waits are separate. Source uses one monotonic deadline
for timed connect/read/accept and retries, while ordinary untimed receive/send
need no clock, poll or descriptor-control call. CLOEXEC, dual-stack capability,
SIGPIPE policy and blocking-mode changes happen during setup/configuration.
Close consumes the descriptor once and is never retried on EINTR. This component
has no OpenSSL headers/types or proxy/TLS protocol parser. A typed
recv(MSG_PEEK) primitive lets source-level CONNECT negotiation inspect bounded
headers while leaving tunnel payload in the kernel; ordinary established I/O
does not call it. Named IPv6 literal scopes use
flat snapshots from original `src/ironwood_host.c`. Its getifaddrs capture owns
one index with in-place sorting and exports primitive fields through typed
pointers. Live flags/MTU use ioctl; hardware snapshots handle native lengths.
ICMP and TCP port-7 fallback use bounded stack storage and one monotonic deadline;
TCP refusal is reachable. Query storage and descriptors have explicit release
on every exit. Test-only syscall replacements never enter the runtime.
See `docs/STDLIB_N1_VERIFICATION.md`, `docs/NETWORKING_M2_VERIFICATION.md`,
`docs/NETWORKING_M4_VERIFICATION.md` and
`docs/NETWORKING_M3_VERIFICATION.md` for diagnostic interposition, fault injection,
allocation ledgers, platform coverage and optimized machine-code evidence.


## Optional TLS adapter

`src/ironwood_tls.c` is compiled only when post-pruning `IrTlsInstruction`
operations select it. Its opaque interface in `include/ironwood_tls.h` exposes no
OpenSSL types to shared runtime code. Each connection owns an isolated library
context, builtin provider, verification context, SSL object and BIO method.
The BIO borrows the TCP descriptor and caller buffers. TLS state is released
before source-level transport close, including partial setup and OOM paths.

The adapter loads only the embedded pinned CA export or the supplied replacement
PEM bundle, with mandatory peer/identity verification and no ambient trust,
revocation fetch, session reuse or early data. Record I/O allocates no adapter
payload buffers. Pinned OpenSSL's WPACKET framing makes native allocations
per record, measured separately from Ironwood managed allocations. Reusable
OpenSSL error/process metadata is distinct from connection-owned state.
See [the TLS guide](../docs/TLS.md) and [M5 evidence](../docs/NETWORKING_M5_VERIFICATION.md).

## Isolated GC1 engine preparation

`src/ironwood_gc_engine.c` and `include/ironwood_gc_engine.h` are a standalone
qualification adapter, selected only by `scripts/prepare-gc.py` and
`scripts/build-gc-fixture.py`. NativeBackend does not compile/link this component
for ordinary programs. Its four fixed fixture kinds are not production layouts.
Prepared SDKs retain the pinned source, notices, headers, canonical configuration
identity and actual payload hashes. Downloads happen only during explicit
preparation; fixture links verify the SDK offline. See
[GC1 setup and evidence](../docs/GC1_ENGINE_PREPARATION.md).

## Experimental runtime lifetimes

`src/ironwood_gc_lifetimes.c` implements GC4's exact memory-only exception,
secondary, trace and carrier blocks, scoped native roots, unwind wrappers and
bounded emergency ownership. It is linked only by `scripts/test-gc-lifetimes.py`
with the verified GC2 SDK and GC3 reader. Root ABI 2 requires runtime-owner ABI 1;
ABI 1 still refuses exception/foreign owners. Native qualification covers macOS
arm64 and [Linux x86-64](../docs/GC4_LINUX_X86_64_QUALIFICATION.md). Linux arm64
remains refused. Ordinary production runtime allocation and generation are unchanged.

Caller-provided native acquisition records reserve before acquisition, arm on
success and transfer ownership at commit. Explicit failure cleanup releases
remaining native acquisitions in reverse order without allocation or collection.
Collection never runs these releases, source destructors, finalizers or JNI
cleanup. Anchor closure releases a root, never a source resource. Full generated
bridge lifetimes, JNI retirement and suspended JVM crossings remain GC6 work.
See [GC4 contracts, evidence and limits](../docs/GC4_RUNTIME_LIFETIMES.md).
