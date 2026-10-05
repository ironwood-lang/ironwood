<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M1.1 ArrayList storage slice

Status: ArrayList slice passes; M1.1/M1 checkpoint remains in progress.
Contracts: independent ordered mutable membership, borrowed non-null items,
source iterator continuation, exact-sized private storage and failure rollback.
D248 records API/proof choices. No JVM compatibility facade or hot lowering change.
Original M0 identities, evidence and budgets remain unchanged.

Host: qualified macOS arm64; Java 21.0.1 at
/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home;
LLVM 23 from the existing toolchain discovery. Initial PATH selected Java 8,
which was rejected before building; subsequent commands explicitly select the
retained JDK. The default-capacity prototype passed at 3/3/5/7 allocations for
8/32/128/512 items. Exact-sized storage now uses 3/3/3/3 allocations: result,
iterator and array. The source's unused capacity is deliberately omitted.

Exact commands (JAVA_HOME as above):

```sh
./scripts/test.sh --test 'independent list copies preserve membership iterator and allocation scaling' --test 'independent list copy failures reclaim partial construction' --test 'independent list copies preserve destination and nested payload loans'
./scripts/test.sh --test 'independent list copy proofs survive source class and archive reconstruction' --test 'independent list copies preserve destination and nested payload loans' --test 'data structure releases allow caller item reclamation' --test 'data structure release proofs reject surviving aliases' --test 'owned buffer fields require a fresh unescaped factory result'
```

Results: 3/3 and 5/5 pass. Both script invocations rebuild the standard library
under its unchanged strict policy and run the license audit. Native O3 checks
verify ordered membership, independent mutation, iterator continuation, empty
copies, capacity policy and zero net live allocations after retirement.
OOM injection checks all 16 allocations in the 256-item construction/copy fixture:
limits 0-15 exit 42 with zero net live allocations; limit 16 and no limit exit 43.
The earlier geometric-growth fixture's limit-19 success was initially asserted
as OOM and failed; its retained log documents that test expectation error.
The final exact-sized implementation and boundary expectations pass.

Lifetime checks accept source retirement followed by copy retirement or clear,
with later item destruction. They reject early item destruction while either
list remains, source self-items, live nested lists and publishing subclass get.
Mandatory errors remain errors under off/warn/error. An ordinary named delegate
gets the same body-derived proof. Source and compiled class/archive consumers
reconstruct the loans and preserve the same O3 allocation counts and cleanup.

Full development logs are retained under ignored workspace/m1. Durable compressed
logs, command identities, helper hashes and IronDocs verification are recorded
with the commit's evidence manifest. Port helper consumers are built with
explicit --unfreed=warn at compile and link; these library tests preserve the
ordinary public CLI defaults. No suppression is introduced. This slice does
not qualify private snapshot constructors, map/set copies, M2 budgets or G1.

Read-guard review additionally found that non-publication alone cannot prove
unchanged membership. The three indexed-read proof consumers now share an exact
private primitive bounds-guard body check with no normal-path mutation, call or
virtual dispatch. Focused changed-guard/getter controls include size mutation,
self-item replacement, retained receiver, publishing helper and virtual override.
They pass with the accepted, self/nested and publishing-getter pairs.

```sh
./scripts/test.sh --test 'independent list copy read proofs reject guard mutation and retention' --test 'independent list copies preserve destination and nested payload loans' --test 'data structure release proofs reject surviving aliases'
./scripts/test.sh --test 'independent list copy proofs survive source class and archive reconstruction' --test 'independent list copy failures reclaim partial construction' --test 'data structure caller loans survive allocation failures' --test 'owning pools reclaim all values and retain allocation-free reuse'
./bin/irondoc -d workspace/m1/list-api -sourcepath stdlib/src/main/ironwood stdlib/src/main/ironwood/ironwood/ds/ArrayList.iron
```

Results: 3/3 and 4/4 tests pass; focused IronDocs generation passes with one type.
The remaining private snapshot constructor proof is a separate pending increment.
