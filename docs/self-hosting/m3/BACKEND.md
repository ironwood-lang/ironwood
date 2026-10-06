<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M3.3 backend helpers and source-only inputs

Status: passes ([D268](../../DECISIONS.md#d268---provide-the-m33-backend-helpers),
[D269](../../DECISIONS.md#d269---give-the-source-only-route-explicit-installation-and-identity-inputs)).
These are the S4 backend prerequisites of the M0 inventory: MD5 GUID
generation, unsigned comparison and widening, binary slices, the remaining
emission and text helpers, and explicit installation and build identity
inputs for the source-only route, with the runtime-object cache omitted from
the native port. All sources are original and use the default license. The
MD5 constants are computed from their definition and match
floor(2^32 * |sin(i + 1)|) recomputed at 60-digit precision; no reference
implementation was consulted.

The Java baseline's backend, Main, CompilerVersion, StandardLibrary and
diagnostic sources are unchanged since J0 (6bde84df), so the references that
call them by reflection on the handoff classes exercise J0's code.

## Consumer mapping

| Java demand | Native treatment | First consumers (S4) |
| --- | --- | --- |
| `MessageDigest.getInstance("MD5")`, `Long.BYTES` GUID assembly | `Md5.linkageGuid`, allocation-free | OptimizedTraceMetadata.linkageGuid, LlvmEmitter TracePlan.linkageGuid |
| `Byte`/`Short.toUnsignedInt`, `Integer.toUnsignedLong`, `Arrays.compareUnsigned`, `Arrays.copyOfRange` | `Bytes.toUnsignedInt`, `toUnsignedLong`, `compareUnsigned`, bounded `slice` | SharedTraceOrder, LlvmEmitter.escapeBytes |
| `ByteBuffer.wrap(...).order(LITTLE_ENDIAN)` absolute reads | `Bytes.littleShort`, `littleInt`, `littleLong` | SharedTraceOrder.macho and elf |
| `new String(bytes, from, length, US_ASCII).equals(name)` | `Bytes.asciiEquals` | SharedTraceOrder.elf |
| `String.format(Locale.ROOT, "0x%016X" / "%.17e", ...)` | `LlvmText.hexBits`, `scientific` | LlvmEmitter.staticInitializer, floatingConstant |
| `Double.doubleToRawLongBits` | added to `ironwood.lang.Double` | LlvmEmitter.staticInitializer |
| `Integer.toHexString(...).toUpperCase(Locale.ROOT)` | `LlvmText.escapeBytes` | LlvmEmitter.escapeBytes |
| `ByteArrayOutputStream`, `Integer.parseInt(..., 16)`, per-unit `getBytes(UTF_8)` | `LlvmText.decodeSymbol` | OptimizedTraceMetadata.decodeSymbol |
| `Pattern`/`Matcher` target, declaration and FUNCTION patterns | `LlvmScan.specification`, `attach`, `removeRegisterDeclaration`, `nextFunction` | NativeTarget, OptimizedTraceMetadata.inject |
| `Properties.load`, `getProperty`, `containsKey`, `equals`, `stringPropertyNames` | `PropertiesText` | TlsDependency.read, BridgeNativeSupport.read |
| `features.matches("(?s).*#define\\s+...")` | `HeaderScan.defines` | TlsDependency.discover, BridgeNativeSupport.validate |
| `MessageDigest` SHA-256 of a file stream | `Sha256` (D267) over `Files.newInputStream` | TlsDependency.sha256 |
| Code-source location, `URL.toURI`, `Path.of(URI)` | `Installation.runtimeSource`, `librarySourceRoots` with `LibraryRoots` | RuntimeLibrary.discover, StandardLibrary.discover |
| `/ironwood/compiler/VERSION` resource | generated `BuildIdentity.version()` | CompilerVersion.current |

## Results

| Check | Result |
| --- | --- |
| MD5 | The RFC 1321 suite including one million `a`, every length 0-300, every byte value, twelve surrogate texts and 64 MiB streamed equal `MessageDigest` (2,596 lines); 2,018 GUIDs of fixed and seeded linkage names equal both OptimizedTraceMetadata.linkageGuid and TracePlan.linkageGuid. Splits, offsets, reset, a GUID amid partial input and range failures hold; construction costs four allocations, hashing and GUIDs none. |
| Binary helpers | Widening of every byte and of boundary and seeded shorts and ints, unsigned long order and byte-array order (null, prefix, 0x7f/0x80 and 2,000 seeded pairs), in-bounds slices, the ASCII comparison with bytes above 0x7f, every little-endian read from `Bytes` and from a wrapping `ByteBuffer`, and the stable trace-root sort of 600 groups with tied GUIDs and payloads equal Java 21 (6,100 lines). |
| LLVM text | Raw-bit spellings and floating constants of 100,030 values (boundaries, NaN payloads, widened floats, seeded bits and decimals), 2,257 byte escapes and 3,014 decoded symbols with valid, invalid and malformed-UTF-8 escapes equal the Java emitter's own methods (205,331 lines). |
| LLVM scans | On 663 corpus files (a Clang target probe, an emitted module and its O3 optimization, each with CRLF, CR, U+0085, U+2028 and U+2029 terminators, 45 adversarial texts and 600 seeded ones), target specifications and failures, attachments, the declaration removal and all 1,872 FUNCTION matches equal NativeTarget's methods and the Java patterns (7,176 lines). The corpus includes an escaped-backslash symbol where Java's backtracking stops at the first `"(`. |
| Properties | On 445 files (the pinned inventories in LF, CRLF and CR forms, a generated build.properties, 38 separator, whitespace, comment, duplicate, terminator and backslash cases and 400 seeded texts), sizes, sorted entries, `containsKey` and `equals` equal `Properties.load`; the 4 texts with a backslash are rejected. |
| Header scan | 13 fixed and 4,000 seeded texts give `String.matches`'s results for both patterns (1,270 matches). |
| Installation | Ten scenarios, run in each scenario's directory and environment, print the Java baseline's results from classes and archive links: checkout classes and jar, installed and bare layouts, an installed jar with the checkout as the current directory (two roots), valid, invalid, blank and relative overrides, and an unknown location. |
| Build identity | Generation is byte-identical across runs, honors IRONWOOD_VERSION, fails with build.sh's messages for an invalid or missing version, and the generated program prints `ironwoodc 0.6.1-beta`, equal to `CompilerVersion.current()`. |
| Runtime objects | In a fresh cache, one link inserts four entries with four distinct sources, so none was reused; a second link reuses all four; each cached object equals a direct compilation with its key's command, twice. |
| Failure | Every allocation limit from 0 to 194 unwinds to the live-allocation baseline; 195 succeeds. |
| Ownership | All modes: inputs are borrowed only during a call; digests, slices, texts, scan results, parses and discovered Paths retire; lent roots cannot be freed; use after free and double free are rejected. |

All fixtures compile and link with `--unfreed=warn` and no diagnostics, from
classes and archive at `-O3`. The test sources compile with
`-Xlint:all -Werror`.

`Double.doubleToRawLongBits` changes no lowering: it returns the existing
`rawBits` intrinsic. At `-O3` a loop that mixes the raw bits of a `double[]`
inlines it into an integer load (`ldr x12, [x9, x11, lsl #3]` on arm64) with no
call or conversion. No hot path changed, so no benchmark applies.

## Boundaries and S4 obligations

- `stringPropertyNames()` iterates in Java hash order. TlsDependency and
  BridgeNativeSupport report the first failing checksum in that order; their
  S4 ports must reproduce it or sort and record the difference.
- `LlvmScan` and `decodeSymbol` return fresh Strings where Java returns the
  same object; `decodeSymbol("@\"")` returns an empty name where Java throws,
  and no FUNCTION match has that form.
- The launcher passes a canonical location. Java's class path entry is
  canonical, so `/tmp` resolves to `/private/tmp` on macOS.
- The shell driver of the source-only route owns the temporary LLVM file, the
  optimization report alias check and SDKROOT; native forms are M4.1-M4.3.
- The native OptimizedTraceMetadata.inject, SharedTraceOrder, NativeTarget
  and TlsDependency ports, and the SelectiveInlining comparison, are S4's
  exit; these helpers do not port them.
