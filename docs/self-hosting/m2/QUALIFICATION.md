<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M2.3 scale and qualification

Base: 4ef94e5d (M2.2). This settles M2.3: every pilot command is built with
`--unfreed=warn` and classified, mandatory safety stays an error in every
mode, and the pilots are measured at geometrically increasing scale against
the M0 budgets. The ledger is
[qualification-evidence/manifest.json](qualification-evidence/manifest.json);
G1 is evaluated in [CHECKPOINT.md](CHECKPOINT.md).

## Missing-free findings

Each of the five pilot programs is compiled and linked with `--unfreed=warn`
against the whole port tree; all ten logs are retained.
`classify-m2-unfreed.py` keys findings by source site, so a site reported by
both the compile and the link counts once (a link names the source inside its
class archive, which is mapped back), and it fails on any unreviewed or stale
site, any other warning or any mandatory error.

| Pilot | Compile findings | Link findings | Unique sites | Mandatory errors |
| --- | --- | --- | --- | --- |
| frontend (`FrontendCapture`) | 0 | 0 | 0 | 0 |
| kernels (`KernelCapture`) | 4 | 4 | 4 | 0 |
| frontend failure fixture | 0 | 0 | 0 | 0 |
| ownership callback fixture | 0 | 0 | 0 | 0 |
| ownership failure fixture | 0 | 0 | 0 | 0 |

The eight reports are four sites in the test adapter, all intentional
invocation-lifetime retention; none is temporary cleanup work or a proof
limit ([review](qualification-evidence/unfreed-review.json)):

| Site | Retains | Why |
| --- | --- | --- |
| `KernelCapture.iron:170` `OwnershipKernel kernel` | analyzer, tracker, store, input nodes and slots | nodes and versions are invocation-lived (D262) |
| `KernelCapture.iron:576` `EvidenceKernel kernel` | store, budget, colliding nodes | evidence payloads are invocation-lived (D262) |
| `KernelCapture.iron:791` `InputKernel kernel` | the span and type rejected IR borrows | inputs are invocation-lived, rejected ones too |
| `KernelCapture.iron:793` `AllocationInfo node` | the rejected freed input node | allocation nodes are invocation-lived |

The port sources themselves report nothing. One local suppression exists:
`@SuppressUnfreed ParseResult failed` in `FrontendCapture.iron`, the shared
sentinel for a parse that threw, which the retained result list keeps to
process exit (D261).

Warning counts do not measure retained volume. What the kernels actually keep
after retirement is measured exactly (OWNERSHIP.md): 961 or 1,281 allocations
per ownership run at 128 nodes, 128 per evidence run and 1 per effect run, and
it does not grow with size except through recorded events.

Across revisions: M2.1's frontend commands reported nothing; M2.2 added the
kernel adapter's four sites; M2.3's adapter changes (counting recorded
events, an explicit evidence-limit check) only moved their lines.

## Mandatory safety

Missing-free settings never relax safety. The two unsafe corpus sources are
compiled (never executed) under each setting:

| Source | `off` | `warn` | `error` |
| --- | --- | --- | --- |
| `CapturedAliases` (frees state an anonymous class captured) | rejected | rejected | rejected |
| `LoopFormsUnsafe` (frees an array an enhanced `for` still iterates) | rejected | rejected | rejected |

Each pilot's paired controls hold in all three settings (the frontend,
ownership and callback control tests), and the ten pilot commands report zero
mandatory errors.

## Scale

The 47 selected kernel configurations rerun on the final adapter: all 94 runs
equal J0, record every expected explanation event, leave zero temporaries and
pass `check-pilot-budget.py`.

Native-only doublings beyond J0's 128 run the same kernels with their own
contract checks; maxima over two fresh repeats, with explanations recorded for
ownership and the observer on for effects:

| Size | Ownership: wall, RSS, phase | Evidence (colliding keys) | Effect chain | Effect cycle |
| --- | --- | --- | --- | --- |
| 128 | 0.020 s, 12.6 MB, 7.4 ms | 0.020 s, 10.7 MB, 9.9 ms | 0.074 s, 3.2 MB, 31 ms | 0.076 s, 3.2 MB, 32 ms |
| 256 | 0.040 s, 21.9 MB, 14.2 ms | 0.040 s, 18.8 MB, 28.7 ms | 0.194 s, 3.9 MB, 134 ms | 0.187 s, 3.9 MB, 125 ms |
| 512 | 0.077 s, 39.8 MB, 27.8 ms | 0.132 s, 34.3 MB, 89.1 ms | 0.570 s, 5.5 MB, 504 ms | 0.517 s, 5.5 MB, 503 ms |
| 1,024 | 0.077 s, 77.3 MB, 57.6 ms | 0.462 s, 67.5 MB, 420 ms | 2.055 s, 8.7 MB, 1,996 ms | 2.097 s, 8.7 MB, 2,044 ms |
| 2,048 | 0.187 s, 154.4 MB, 117 ms | | | |
| 4,096 | 0.352 s, 317.9 MB, 238 ms | | | |

Every run exits 0 and leaves zero outstanding temporaries. Ownership grows
linearly in time and memory (about 77 KB per node with 129 held versions) and
approaches the 250-ms phase cap at 4,096 nodes (236-238 ms). The forced-collision evidence
kernel grows superlinearly, because its value-keyed maps see colliding hashes,
exceeds the phase cap at 1,024 nodes, and above about 1,365 nodes its fixed
snapshot limit stops saving, which the kernel reports as a contract stop.
Effects grow quadratically: a chain or cycle needs one fixed-point round per
function, as in Java (the observer counts n + 1 rounds), so 512 functions
reach the 500-ms cap. These sizes are beyond the budgeted references; they
mark the practical limits, not budget failures.

The frontend bundle (the 125 units of SOURCE_BUNDLE.json, hashes rechecked) in
one invocation, doubled:

| Copies | Units | Wall | RSS | Phase |
| --- | --- | --- | --- | --- |
| 1 | 125 | 0.24-0.46 s | 138.7 MB | 10.6-10.8 ms |
| 2 | 250 | 0.46 s | 187.5 MB | 21.2-21.5 ms |
| 4 | 500 | 0.90-0.95 s | 316.9 MB | 41.9-45.5 ms |
| 8 | 1,000 | 1.89-1.94 s | 497.1 MB | 83.6-85.5 ms |
| 16 | 2,000 | reaches the 2-second cap | | |

These equal M2.1's measurements (0.24, 0.52, 0.95 and 1.88 s; 139 to 497 MB):
the closed-world link leaves the frontend binary unaffected by the M2.2
sources. The census leaves zero temporaries at every completed scale.

Stack: every selected configuration completes within 32 KiB (M2.2) and every
frontend case within 128 KiB (M2.1). The kernels' recursion depth is 64 frames
at every size; the doubled sizes were not bisected separately.

## Reproduce

[run-evidence.sh](qualification-evidence/run-evidence.sh) rebuilds a fresh
tree and runs every check above with the license audit. The tools are
`scripts/self-hosting/classify-m2-unfreed.py`, `measure-m2-kernels.py`
(`scale` mode) and `measure-m2-frontend.py`.
