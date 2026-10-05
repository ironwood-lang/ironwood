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
class archive, which is mapped back). It fails on any unreviewed or stale
site, any other warning or any mandatory error, and on any `@SuppressUnfreed`
directive without a reviewed justification or whose variable name repeats in
its file.

| Pilot | Compile findings | Link findings | Unique sites | Mandatory errors |
| --- | --- | --- | --- | --- |
| frontend (`FrontendCapture`) | 0 | 0 | 0 | 0 |
| kernels (`KernelCapture`) | 0 | 0 | 0 | 0 |
| frontend failure fixture | 0 | 0 | 0 | 0 |
| ownership callback fixture | 0 | 0 | 0 | 0 |
| ownership failure fixture | 0 | 0 | 0 | 0 |

No command reports a missing-free finding. The port sources report nothing on
their own. The test adapters' intentional process-lifetime retention is
declared instead with five local suppressions, each reviewed
([review](qualification-evidence/unfreed-review.json)):

| Suppressed local | Retains | Why |
| --- | --- | --- |
| `FrontendCapture.iron` `ParseResult failed` | the shared sentinel for a parse that threw | the retained result list keeps it to process exit (D261) |
| `KernelCapture.iron` `OwnershipKernel ownership` | analyzer, tracker, store, input nodes and slots | nodes and versions are invocation-lived (D262) |
| `KernelCapture.iron` `EvidenceKernel evidence` | store, budget, colliding nodes | evidence payloads are invocation-lived (D262) |
| `KernelCapture.iron` `InputKernel inputs` | the span and type rejected IR borrows | inputs are invocation-lived, rejected ones too |
| `KernelCapture.iron` `AllocationInfo node` | the rejected freed input node | allocation nodes are invocation-lived |

The four kernel suppressions replace what M2.2 reported as four warnings at the
same declarations; nothing else changed. A suppression only silences the
missing-free diagnostic for that local: freeing it, or anything it retains,
still needs the full proof in every mode.

Warning counts do not measure retained volume. What the kernels actually keep
after retirement is measured exactly (OWNERSHIP.md): 961 or 1,281 allocations
per ownership run at 128 nodes, 128 per evidence run and 1 per effect run, and
it does not grow with size except through recorded events.

Across revisions: M2.1's frontend commands reported nothing; M2.2's kernel
adapter reported four sites; M2.3 declares them as reviewed suppressions,
so every pilot command now reports none.

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

## Measurement isolation

M0's budget procedure requires measurements without competing jobs. A helper
script from M2.1's closure reconciliation hung in a loop at 13:44 and, when
its call timed out, kept running orphaned at 100% of one core for 3.5 hours.
It overlapped every resource measurement of M2.1, M2.2 and the first M2.3
runs, and Spotlight indexing of each freshly copied evidence tree added load.
Those measurements are provisional and stay in their retained logs; M2.1's and
M2.2's records show them in brackets.

After stopping that process, this run re-measured every pilot in isolation:
the tree is staged under a `.noindex` directory, which Spotlight skips, and
each measurement group waits for a one-minute load average below 3 and logs
the load it started under (2.1 to 2.6 here; nothing else of ours ran). The
isolated values match the first M2.3 run closely, so the overlap changed no
conclusion.

| Workload | Records | Maximum wall | Maximum RSS | Maximum phase | Budget |
| --- | --- | --- | --- | --- | --- |
| 36 frontend workloads | 72 | 0.024 s | 17.5 MB | | 2 s, 1 GiB |
| frontend bundle in one invocation | 2 | 0.270 s | 138.7 MB | 10.5 ms | 2 s, 1 GiB |
| J0 on the bundle (reference) | 2 | 1.438 s | 414.9 MB | 93.6 ms | |
| ownership kernels | 48 | 0.210 s (cold first run; others 0.025 s) | 12.6 MB | 7.4 ms | 1 s, 512 MiB, 250 ms |
| evidence kernels | 6 | 0.024 s | 10.7 MB | 10.0 ms | 1 s, 512 MiB, 250 ms |
| effect kernels | 40 | 0.048 s | 3.2 MB | 30.9 ms | 1 s, 512 MiB, 500 ms |

All 74 frontend and 94 kernel records pass `check-pilot-budget.py`. The 36
workloads again equal their frozen J0 captures, and their census leaves only
the two truncated workloads' abandoned subtrees (25 and 29 allocations); the
kernels equal J0, record every expected explanation event and leave zero
temporaries.

## Scale

Native-only doublings beyond J0's 128 run the same kernels with their own
contract checks; maxima over two fresh repeats, with explanations recorded for
ownership and the observer on for effects:

| Size | Ownership: wall, RSS, phase | Evidence (colliding keys) | Effect chain | Effect cycle |
| --- | --- | --- | --- | --- |
| 128 | 0.024 s, 12.6 MB, 7.3 ms | 0.024 s, 10.7 MB, 10.0 ms | 0.048 s, 3.2 MB, 32 ms | 0.048 s, 3.1 MB, 31 ms |
| 256 | 0.024 s, 21.9 MB, 13.9 ms | 0.048 s, 18.8 MB, 27.8 ms | 0.150 s, 3.9 MB, 125 ms | 0.150 s, 3.9 MB, 127 ms |
| 512 | 0.048 s, 39.8 MB, 28.7 ms | 0.150 s, 34.3 MB, 88.2 ms | 0.506 s, 5.5 MB, 492 ms | 0.557 s, 5.5 MB, 507 ms |
| 1,024 | 0.090 s, 77.3 MB, 57.4 ms | 0.450 s, 67.4 MB, 405 ms | 2.032 s, 8.7 MB, 1,977 ms | 1.989 s, 8.7 MB, 1,963 ms |
| 2,048 | 0.208 s, 154.4 MB, 121 ms | | | |
| 4,096 | 0.330 s, 317.8 MB, 238 ms | | | |

Every run exits 0 and leaves zero outstanding temporaries. Ownership grows
linearly in time and memory (about 77 KB per node with 129 held versions) and
approaches the 250-ms phase cap at 4,096 nodes (236-238 ms). The
forced-collision evidence kernel grows superlinearly, because its value-keyed
maps see colliding hashes, exceeds the phase cap at 1,024 nodes (389-405 ms),
and above about 1,365 nodes its fixed snapshot limit stops saving, which the
kernel reports as a contract stop. Effects grow quadratically: a chain or cycle
needs one fixed-point round per function, as in Java (the observer counts
n + 1 rounds), so 512 functions reach the 500-ms cap (490-507 ms). These sizes
are beyond the budgeted references; they mark the practical limits, not budget
failures.

The frontend bundle (the 125 units of SOURCE_BUNDLE.json, hashes rechecked) in
one invocation, doubled:

| Copies | Units | Wall | RSS | Phase |
| --- | --- | --- | --- | --- |
| 1 | 125 | 0.21-0.26 s | 138.7-142.9 MB | 10.5-10.6 ms |
| 2 | 250 | 0.44-0.45 s | 187.5 MB | 20.8-21.3 ms |
| 4 | 500 | 0.92-0.93 s | 316.9 MB | 42.3-43.6 ms |
| 8 | 1,000 | 1.87-1.92 s | 497.0-497.8 MB | 82.2-83.7 ms |
| 16 | 2,000 | reaches the 2-second cap | | |

Growth is linear and matches M2.1's provisional values (0.24, 0.52, 0.95 and
1.88 s; 139 to 497 MB): the closed-world link leaves the frontend binary
unaffected by the M2.2 sources. The census leaves zero temporaries at every
completed scale.

Stack: every selected configuration completes within 32 KiB (M2.2) and every
frontend case within 128 KiB (M2.1); stack limits are pass-or-fail and do not
depend on load. The kernels' recursion depth is 64 frames at every size; the
doubled sizes were not bisected separately.

## Reproduce

[run-evidence.sh](qualification-evidence/run-evidence.sh) rebuilds a fresh
tree and runs every check above with the license audit. The tools are
`scripts/self-hosting/classify-m2-unfreed.py`, `measure-m2-kernels.py`
(`resources` and `scale` modes), `check-m2-kernel-budgets.py`,
`m2-frontend-differential.py`, `measure-m2-frontend.py` and
`check-m2-frontend-budgets.py`.
