<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Release packaging precheck: 2026-11-10

Local validation of source version `0.6.2-beta`, before release preparation.
No tag, push, publication or self-hosting attempt was made. Compiler/runtime
implementation and qualified GC profile values are unchanged. See
[D331](DECISIONS.md#d331---require-qualified-release-inputs-and-independent-archive-checks).

| IDK target | Relocated normal smoke | GC9 distribution smoke | Local execution |
| --- | --- | --- | --- |
| macOS arm64 | Pass | Pass, 28 controls | Native Apple Silicon |
| Linux arm64 | Pass | Pass, 33 controls | ARM64 VM on matching hardware |
| Linux x86-64 | Pass | Pass, 33 controls | Rosetta translation |

The normal smoke covers bundled tool selection, SDK integrity across relocation,
Java Bridge callbacks, source/class/archive linking, native examples and projects,
TCP, authenticated proxies, TLS, wget and optimization levels O0 through O3.
GC9 additionally exercises explicit collection, managed-image admission and
deliberately missing or altered inputs. Linux checks retain the GLIBC 2.17
baseline, including execution through its loader in the GC9 smoke.
The supplementary macOS host package also passed its normal relocated smoke.
The full compiler suite was not rerun for these packaging changes.
The final x86 run exercised the payload-based disk reservation on the small VM;
macOS and ARM had already passed the same collection/refusal cases before that
reservation change. No case was removed or skipped.

Fixed release integration gaps:

- Prepare/select the GC SDK explicitly. IDK packaging rejects missing, empty or
  unverifiable inputs before rebuilding and bundles source, notices and manifests.
- Use fixed Linux container toolchain/cache paths and micromamba 2.10.0. Fresh
  sysroot installations reproduced both checked-in receipt hashes exactly.
  Pin Xcode 27.2 build 27B52 on the
  [documented `xcode-27` image](https://github.com/actions/runner-images/blob/main/images/macos/xcode-27-arm64-Readme.md).
- Clear development SDK/runtime overrides in IDK checks, verify bundled GC/TLS
  before and after relocation, and run both archive smokes again after download.
  Separate the accidentally joined TCP/proxy compile and link commands in both
  smoke drivers.
- Replace the fixed 30 GiB metadata-variant disk floor with a conservative bound
  on expanded payload, ZIP headers, native extraction and JVM scratch. Keep every
  mutation/refusal case and the existing native RSS/deadline guards. Regressions
  cover compressed payload expansion and the exact insufficient-space boundary.
- Exclude generated example/project output from packages. Audit completed Linux
  example binaries before removing temporary native-support copies to bound
  smoke-test disk usage. A paired regression admits GLIBC 2.17 and rejects 2.28.

Additional verification passed: 21 release Git fixtures, 14 JVM-option fixtures,
24 platform-workflow fixtures, nine release-packaging fixtures, current frozen
IronDocs, license checks and shell syntax checks. Actionlint 1.7.12 passed with
the official `xcode-27` label configured explicitly because its built-in label
list predates that hosted image.

Archive inspection verifies complete bundled GC inputs and zero generated
example/project `target/` paths. Artifacts and raw logs remain under ignored
`workspace/release-packaging-20261110/`.

| Archive target | Bytes | SHA-256 |
| --- | ---: | --- |
| macOS arm64 | 543381170 | `478aadb1e98e71903628f39cc8bb436b9b5c8de1f153a6617b20e74f68af1cdd` |
| Linux arm64 | 1022583788 | `2b17479cf851ad9937d2394555b81317da198ccaaa6e1f7969644092d2971186` |
| Linux x86-64 | 1043599513 | `48358c269fcb81015edd680d7f7781a66841959ab75ad3d8f243c84c22412a7a` |

The translated x86 run does not replace matching-hardware qualification. The
existing physical evidence remains in [GC9](GC9_LINUX_X86_64_DISTRIBUTION.md).
The release workflow performs fresh native x86 archive verification before
publication; no hosted job was started by this local precheck.
