# S0 String reclamation source and provenance review

This review records the API, licensing, ownership, and architecture boundary
for the S0 porting and reclamation foundation before its implementation. The
governing policies are [`LICENSE_MECHANICS`](../LICENSE_MECHANICS) and
[`OPENJDK_PORTING.md`](OPENJDK_PORTING.md).

## Supported slice

S0 adds only this independently implemented Java 21-shaped `String` surface:

- `String substring(int beginIndex)`;
- `String substring(int beginIndex, int endIndex)`; and
- `char[] toCharArray()`.

Every successful call returns a fresh ordinary allocation owned by the caller.
Even an empty or whole-value substring is a distinct ordinary `String`; this
keeps the result contract uniform and makes `free` eligibility visible to the
compiler. Indices use UTF-16 code units. Invalid ranges throw
`StringIndexOutOfBoundsException`. `toCharArray()` returns a fresh array and
never exposes String storage.

The same allocation-result proof applies to the already supported
`StringBuilder.toString()` snapshot. S0 makes that returned `String`
caller-reclaimable and makes a fresh `StringBuilder` reclaimable after its
borrowed inputs and snapshots are no longer observable. Its existing
destructor owns and reclaims the backing `char[]`; failed construction rolls
back that completed child before reclaiming the incomplete builder.

S0 also classifies the existing default `Object.toString()` intrinsic as an
allocation-producing operation. Its runtime-created identity String is fresh,
caller-owned, and eligible for `free` when no alias remains. Overrides keep
their ordinary analyzed return contracts.

This is deliberately not U1 completion. String constructors, search,
prefix/suffix tests, `contains`, `concat`, `subSequence`, `getChars`, primitive
`valueOf`, comparison, output overloads, and `System.err` remain in U1.

## Provenance decision

`stdlib/src/main/ironwood/ironwood/lang/String.iron` and
`StringBuilder.iron` are independent Java-compatible Ironwood implementations
under `SPDX-License-Identifier: MIT OR Apache-2.0`. Public Java-shaped names and
documented Java 21 behavior are compatibility targets. The small validation and
copy loops are written independently. No OpenJDK implementation body, comment,
Javadoc, test, algorithm, or distinctive internal structure is inspected,
translated, or adapted for S0.

The compiler, typed IR, runtime ABI, tests, examples, build scripts, and
documentation involved in the slice are original Ironwood work under the same
dual license. S0 introduces no OpenJDK-derived or other third-party source, so
the derived-source ledger and `THIRD_PARTY_NOTICES.md` require no new entry.

## File and mechanism classification

| Scope | Classification | S0 treatment |
| --- | --- | --- |
| `ironwood.lang.String` public methods | Independent Java-compatible | Validate Java-shaped UTF-16 ranges and return caller-owned results without exposing source storage. |
| `ironwood.lang.StringBuilder` public snapshot/destructor behavior | Independent Java-compatible facade with Ironwood reclamation semantics | Preserve the existing facade; prove the snapshot fresh and the backing array compiler-owned. |
| `String.fromChars(char[], int)` and String tail representation | Original Ironwood compiler/runtime mechanism | Keep the private typed intrinsic and narrow runtime ABI; it copies the supplied code units into one exact-size ordinary String allocation. |
| `String.fromRange(String, int, int)` | Original Ironwood compiler/runtime mechanism | Use a private typed intrinsic and narrow runtime ABI to copy a validated UTF-16 range directly into one exact-size ordinary String allocation, with no scratch array. |
| default `Object.toString()` allocation result | Original Ironwood compiler/runtime mechanism with Java-compatible observable form | Preserve the existing typed intrinsic and recognize its returned String as caller-owned fresh storage. |
| symbolic return and call-escape summaries | Original Ironwood compiler mechanism | Propagate fresh-result identity through the String intrinsic and through wrappers; distinguish a returned receiver/argument from an outward non-return escape. |
| destructor and constructor rollback lowering | Original Ironwood compiler/runtime mechanism | Reuse the accepted D083 substrate; clear and destroy proven-owned children, then reclaim the wrapper or incomplete receiver. |
| `.ironclass`, `.ironjar`, source path, class path, pruning, and separate link | Original Ironwood artifact/compiler mechanism | Reconstruct source at final closed-world link so allocation and effect summaries are identical across delivery forms. |
| compiler/native/package tests and the example | Original Ironwood tests and example | Author new cases from the contract in this review without adapting upstream tests. |
| license and source packaging | Original Ironwood packaging mechanism | Continue shipping standard-library source, all required license texts, notices, and this review in host packages and IDKs. |

## Allocation and failure audit

| Allocation | Ownership | Normal completion | Exceptional completion |
| --- | --- | --- | --- |
| `substring` result `String` | Caller-owned | The range intrinsic creates one exact-size allocation, returned with fresh identity and eligible for `free`. | Validation occurs before allocation; a failed allocation returns no result and creates no scratch allocation or partial source-visible state. |
| `toCharArray` result | Caller-owned | Returned with fresh array identity and may be passed to `free`. | A failed allocation returns no array. |
| `StringBuilder.toString` result | Caller-owned | Returned with fresh String identity; it does not borrow builder storage. | A failed snapshot allocation leaves the builder and backing array owned by the caller. |
| default `Object.toString` result | Caller-owned | Returned with fresh String identity and may be passed to `free`. | A failed result allocation leaves the receiver unchanged and returns no String. |
| `StringBuilder.value` | Builder-owned child | Cleared and destroyed by the builder destructor. | Constructor rollback destroys a completed backing array in reverse field order without invoking the incomplete builder destructor. |
| input Strings and `CharSequence` values | Borrowed | Observed only during the call and never retained. | Remain caller-owned if a call throws. |
| literal Strings and standard singletons | Immortal | Never returned as an S0 allocating result and never eligible for `free`. | Not applicable. |

The proof remains conservative: aliases, publication, unknown origins, a
borrowed helper, double free, and post-free observation are rejected. S0 adds
no ownership annotation, lifetime parameter, raw pointer, or unsafe escape
hatch to application source.

## Verification boundary

Completion requires positive and negative semantic tests, typed-IR and LLVM
inspection, live-allocation checks for normal destruction and failed
construction, native `-O0` through `-O3`, source-path/classpath/archive
reconstruction, tree shaking, separate compile/link, host-package smoke tests,
the license gate, and a runnable reclamation-focused text example.
