# Runtime

The isolated bootstrap runtime exposes these C ABI functions:

- `ironwood_allocate`, which zero-initializes storage with `calloc`, installs
  the compiler-provided type descriptor, and raises the compiler-provided
  immortal allocation-failure object when source allocation fails;
- `ironwood_allocate_array`, which receives a compiler-validated nonnegative
  element count, defends the total-size calculation, records the length and
  compiler-provided descriptor and element size/kind, zero-initializes the
  array elements, and uses the same catchable failure boundary;
- `ironwood_allocation_count`, which atomically snapshots a process-wide count
  incremented exactly once after each successful object, array, or
  runtime-created String allocation;
- `ironwood_live_allocation_count`, which atomically snapshots the number of
  currently live ordinary allocations and decreases on every non-null raw
  deallocation;
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
- `ironwood_deallocate`, which performs the raw release after a
  compiler-validated destructor or rollback path reaches the runtime boundary;
- `ironwood_destructor_failed`, which terminates deterministically if an
  exception crosses the backend's no-unwind destructor boundary;
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
  function-address metadata once at process startup; stack capture uses native
  unwinding and LLVM pseudo-probe decoding only on the exception path; and
- `ironwood_uncaught_exception`, which reports the concrete qualified type,
  optional message, and captured source trace for the primary and each direct
  secondary exception in order, then exits with status 1.

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

Objects carry one descriptor pointer before their base-first instance fields.
The descriptor contains closed-world dispatch and membership metadata plus
optional destructor and constructor-rollback entries.
The allocator installs that descriptor in otherwise zeroed storage before the
synthesized or declared root-to-leaf constructor chain runs. An exception
thrown by construction therefore cannot publish a partially constructed
result. Ordinary language objects are retained until a validated
source `free` destroys and releases them or the process exits. If a constructor
throws before producing a result, compiler-emitted rollback visits proven-owned
fields in reverse layout order, normally destroys completed owned children, and
raw-deallocates the receiver without running that incomplete receiver's source
destructor. Constructor analysis rejects publication of in-progress `this`, so
the rolled-back receiver cannot remain observable. Thrown objects remain ordinary
allocations after their short-lived native unwind wrappers are destroyed. The
ordinary Throwable constructor snapshots the active trace into runtime-private
C storage through virtual `fillInStackTrace`; throwing or rethrowing that object
preserves the snapshot. Explicit refresh releases the old frame buffer. A
private slot on Throwable owns metadata and secondary association nodes, which
its destructor and constructor rollback release. Trace nodes do not increment
`System.allocationCount()` and cannot be directly targeted by source `free`.
If capture allocation fails, propagation still works and printing uses
`<trace unavailable>`. Emergency allocation failures reuse bounded static
storage. D121 changes capture/storage without adding frame instrumentation to
normal calls or adding work to ordinary object destruction.
Arrays carry the same descriptor pointer as class instances, followed by a
native length, element-size/kind header, and their contiguous elements. Their compiler-emitted
descriptors provide `Object` membership and inherited Object dispatch.
Compiler-emitted literal strings are immutable, immortal objects with UTF-16
code-unit and exact UTF-8 byte lengths plus trailing UTF-16 units; they are not
allocator results. Runtime-created strings use the same one-allocation tail.
Standard output combines surrogate pairs and emits U+FFFD for unmatched units.

Ironwood deliberately has no garbage collector. Compiler-emitted
`ironwood.destroy` selects the most-derived descriptor entry, runs the
derived-to-root source destructor chain, and then calls the raw deallocator.
Arrays bypass source destructors. Compiler-emitted `ironwood.rollback` selects
the constructor-rollback entry after a failed construction and preserves the
original exception object. Runtime-owned unwind
wrappers continue to be released internally because they are not Ironwood
objects. Ordered exception associations and captured traces are held in
runtime-private metadata owned through Throwable's reserved private slot;
they expose no public native handle and do not affect Ironwood allocation counts. `System.allocationCount()` remains cumulative;
`System.liveAllocationCount()` provides the current-live diagnostic used by
reclamation and rollback tests. If source code omits `free`, ordinary
allocations remain allocated until process termination and may exhaust memory.
The current header, allocation boundary, unwind wrapper, and exception metadata
are not a stable external ABI.

## Fixed-convention String transformations

D117's direct-result repeat, literal replace, String-array join and case
conversion allocate one String without heap scratch. Casing uses fixed en_US
Unicode 15.0 behavior through `src/ironwood_case.c` and generated data in
`src/ironwood_case_data.h`. Those two files are GPLv2 with the Classpath
Exception; other runtime files retain their own licenses. The helper is compiled
separately and unreachable code/data are discarded at link time. No Locale
objects, environment locale discovery or normalization subsystem are present.
See `docs/STDLIB_STRING_REVIEW.md` for generation, ownership and provenance.
