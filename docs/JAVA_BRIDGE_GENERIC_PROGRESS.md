<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge reference generics progress and evidence

P7d0-P7d2 are implemented and qualified on all three targets. The
[final P7d2 candidate](#final-p7d2-candidate) records current evidence and
measurements. D238 subsequently retained JNI after P7e0 and authorized
[P7f combined qualification](JAVA_BRIDGE_P7_QUALIFICATION.md). Chronological
notes retain each earlier checkpoint's original boundary.

## P7d0 checkpoint

The maintainer authorized only P7d0 on 2026-09-29. Canonical checkout and both
required origin URLs verified; clean `java-bridge` baseline `5b635489`. Existing
branch-only/local-commit instructions override the default main/push workflow.
P7d1/P7d2 generic facade admission and all later phases remain outside this task.

## Pre-change review

Read the plan, contribution/license rules, generic type contracts and regression
lessons. The current bridge records generic booleans and exact member IrTypes,
but drops full ordered declaration bounds and applied declaring-owner views.
Native callable identities alone cannot distinguish all source contracts with
the same ABI. Preserve native root identities separately from complete source
signature identities; resolving either confers no lifetime permission.

Affected consumers: semantic API projection, source/class/archive reconstruction,
export surface selection and subsequent generation/admission. Keep mandatory P0
reclamation/retention proofs and conservative unknown effects unchanged. No
lowering/runtime/transport or hot-path changes are planned, so timing and remote
hardware qualification are not needed for this compiler-only foundation.

Implementation: immutable resolved type-parameter/bound inventories, exact
receiver/declaring-owner and member identities, and final-program-bound signature
proofs with explicit revalidation. Production export selection consumes these
facts while continuing to reject every unsupported generic API. Do not generate
generic Java declarations or authorize erased hints as native layout evidence.

Paired verification: implicit versus explicit Object bounds; secondary,
dependent and recursive bounds; class versus method variable shadowing;
distinct concrete applications, wildcards, overloads and inherited signatures;
unchanged reconstruction versus altered bounds/signatures/body and absent facts.
Preserve all public constructors/setters/methods in the inventory even when they
would make a future generic export inadmissible. Java raw/wildcard/unchecked
views are client semantics, not an Ironwood source relaxation or export permit.

Focused selection: new generic metadata/proof parity and producer-refusal tests;
existing API projection, public signature closure, native root reconstruction,
generation identity, enum and object API checks; paired generic safe/unsafe
semantic checks; one existing scalar producer test for executable regression.
Use pinned Java 21/LLVM 23, `scripts/test.sh --test` selectors, license audit and
`git diff --check`. No unfiltered suite or hosted job. Revisit this selection if
implementation extends beyond these metadata consumers.

## Checkpoints

P7d0 complete. Implementation/tests committed locally as `bbfe2569` on
`java-bridge`; the documentation checkpoint records D235 and this evidence.
No P7d1 admission or work beyond this checkpoint.

The foundation now preserves ordered bounds, declaration erasure separately
from substituted bounds, implicit-bound primitive eligibility, exact applied
receiver/declaring-owner views, scoped variables and complete member signatures.
Export selection resolves source-signature proofs before native root selection.
Full receiver comparison prevents an inherited generic scalar method from being
mistaken for an exact native target merely because its explicit parameters match.

The secondary-bound regression confirmed identical lowered programs can describe
different source contracts. API facts now match only the final program instance
that produced them; reconstruction projects fresh facts and re-resolves stable
source identities. Resolved signatures compare the complete identity and target
against those fresh facts. This strengthens metadata validation without changing
P0 lifetime analysis or native ABI/lowering. Extend adjacent selection to listener
proxy and byte-view proof consumers of the stricter fact matching.

Initial API checks passed. Generic signature/proof and complete class-directory,
individual-class and archive parity checks passed after correcting test setup:
public fixture declarations need separate source files; an individual Api class
loads only its dependency closure, so whole-inventory parity must supply all
individual declarations. No compiler safety rule was relaxed.

The adjacent regression run exposed an empty-enum regression: removing the
inventory's native candidate also removed evidence used by the existing
Java-only identity protocol. Preserve that candidate for separately proved
dispatch, and require the exact applied receiver in direct-call signature
resolution instead. Enum inventory and generic receiver-negative checks pass.

The mixed API test also contained an obsolete pre-P7b expectation that a null
primitive-array result must fail. D233 already admits that case. Preserve it as
a positive native-root/reconstruction check and retain rejection coverage with
an object-array result. The full mixed API check now passes without changing
array admission or enum dispatch implementation.

## Verification and stop point

macOS ARM64, Temurin `21.0.12.1+1`, Homebrew LLVM `23.1.0`. All 16 selected
checks have passing evidence; only affected checks were rerun after corrections.
Run the following focused selection
with the pinned toolchain from the checkout root:

```sh
export JAVA_HOME="$PWD/workspace/java-bridge/jdks/temurin-21-macos-arm64/jdk-21.0.12.1+1/Contents/Home"
export PATH="$JAVA_HOME/bin:/opt/homebrew/opt/llvm/bin:$PATH"
./scripts/test.sh \
  --test 'Java Bridge generic signatures preserve bounds owners and proof identity' \
  --test 'Java Bridge generic metadata reconstructs without admitting generic exports' \
  --test 'Java Bridge API projection preserves resolved inherited surface and safety' \
  --test 'Java Bridge public surface rejects incomplete capabilities and signature closure' \
  --test 'Java Bridge roots and ABI preserve resolved identities' \
  --test 'Java Bridge model survives source class and archive reconstruction' \
  --test 'Java Bridge library roots survive production optimization and artifact reconstruction' \
  --test 'Java Bridge enum inventory preserves named constants and synthesized roles' \
  --test 'Java Bridge concrete object signatures preserve closure and reconstruction gates' \
  --test 'Java Bridge mixed object enum signatures preserve complete public closure' \
  --test 'Java Bridge identities distinguish API program producer build and payload' \
  --test 'Java Bridge listener proxies participate in mandatory source safety and artifact reconstruction' \
  --test 'Java Bridge byte-view proofs preserve typed bounds confinement and artifact parity' \
  --test 'generic calls preserve conservative safe-free summaries' \
  --test 'wildcard conversions preserve safe-free allocation identity' \
  --test 'Java Bridge producer publishes paired jars with source parity and failure preservation'
```

Local evidence under ignored `workspace/java-bridge/`:

- `p7d0-final-tests.log`: initial 16-check run, 14 passed and two enum failures.
- `p7d0-regressions.log`: five of six affected checks passed after separating
  candidate targets from direct bindings; mixed API's stale array expectation
  remained. Includes the production paired-jar/failed-output-preservation check.
- `p7d0-final-revalidation.log`: expanded fresh-fact positive and changed-body
  negative signature check passed; records the obsolete array fixture failure.
- `p7d0-mixed-api.log`: corrected mixed API check passed, including its full
  source/class/archive, enum lifetime and conservative unknown-effect checks.
- `scripts/check-licenses.sh` passed; staged and working `git diff --check`
  passed. No packaging behavior changed. Existing library-root and scalar
  producer checks exercised production lowering and paired artifacts.

No unresolved P7d0 blocker or validation remains. No native ABI/runtime/hot-path
change, additional platform qualification or new performance claim is made.
No Estonia run, installation, push or publication was needed. Generic classes
and methods remain rejected by the producer. P7d1/P7d2 admission, P7e and P7f
remain pending; stop here as requested.

## P7d1 pre-change review, 2026-09-29

The maintainer now authorizes P7d1 only. Canonical checkout/remotes verified;
clean `java-bridge` baseline `44ee1531`. Preserve D235 metadata, reference-generic
shared native layouts, mandatory P0 lifetime/retention proofs, conservative
unknown effects and D132/D133. P7d2 construction/mutation stays rejected.

Trace exact concrete applications through semantic API facts, public roots,
entry generation, Java generic declarations, JNI descriptors/conversion,
identity caches, root ownership and artifact identities. Java unchecked casts
must not select native layouts. Investigate finite producer-bound view dispatch
while keeping generic method bodies/layouts shared. Reuse source analysis for
any generated adapters; do not transplant lifetime summaries across changed IR.

Required pairs: two factory-produced final reference applications versus unknown
or primitive applications; private constructors/read-only type-dependent
results versus public constructors/setters/generic methods; ordinary/raw/wildcard
Java reads and failed unchecked casts; null, identity, inherited identity,
borrowed ownership/retention and exceptional cleanup; unsafe frees and unknown
effects still reject. Reconstruct source, individual classes and archives.
Focused selection will include new generic producer/proof cases, existing P7d0
tests, object result-origin/root-retention/permanent proofs and generic safe-free
regressions. Inspect O3 code and allocation/timing evidence for the added native
path. Run license and diff checks; no unfiltered suite.

Initial implementation preserves one shared native class/body for reference
applications. The actual result's native class selects its Java facade on a
cache miss; warmed hits use the existing address/identity-cache path. No client
type hint selects a native layout. Source-wide exact allocations establish
finite variable domains only for final, read-only classes with inaccessible
constructors; unresolved allocations disable that proof. Deallocation scans
remain conservative. Final native-link checks carry these facts only through
the existing fixed generated-entry and link transformations.

Private generic factories require proved confined construction before their
products can become owning roots. All reference applications share native
storage identity, while exact source/API identity remains separate. Published
families and their returned concrete values still require the full D192
non-reclamation proof; no automatic reclamation is introduced. The change now
touches construction-fact propagation, non-reclamation and root-retention
consumers, so their paired safe/unsafe regressions are required as planned.

Initial two-application producer test passed at O0/O3 across source, class
directory, individual classes and archive inputs (`p7d1/initial-tests.log`,
producer evidence `generics/producer/run-3027019797703943133`, both under ignored
`workspace/java-bridge/`). This includes Java raw/wildcard/unchecked views,
identity, null, native exceptions, owning-root free and zero warmed native
allocations. Borrowed views, further fault/negative checks and qualification
remain in progress. No completion or performance claim yet.

P7d1 implementation checkpoint: source, class-directory, individual-class and
archive producer consumers pass at O0/O3, including a final-bounded getter,
borrowed generic boxes, raw/wildcard casts, overloads, identity and native OOM
children. Published generic families pass in pure-permanent and mixed surfaces;
a reachable owned-family destructor correctly prevents permanent admission.
Cold conversion uses an existing native type ID, not a new layout tag. Warmed
calls retain the existing address/identity-cache path.

The borrowed fixture initially attempted to own a factory result that ordinary
source ownership could not prove. It now constructs the package-private box
directly, allowing the existing field-ownership proof. No ownership rule changed.
An old permanent-proof fixture still expected primitive-array ABI refusal from
before P7b. Its negative now uses a reference array; existing primitive-array
coverage remains. Generic ABI descriptors can describe reference variables and
applications, but are not producer conversion capabilities. The model test
records that distinction and retains primitive projection/array negatives.

Shared proof regressions passed in `p7d1/expanded.log` (nine checks); that run
found an unchecked generated cache cast for permanent generic factories. The
producer now marks only its necessary generated casts, and the corrected full
producer test passes in `p7d1/producer-final.log`. Native OOM leaves existing
facades live and existing output survives rejected public construction. A
final expanded run is pending for that last producer-negative addition.

The new `examples/java-bridge/generics` compile/link/run passes with checked JNI;
its matched getter runner has passed Mac functional smoke and preserved payload
hashes, bytecode and disassembly under `p7d1/mac-smoke`. Linux ARM64/x86-64
qualification, full allocation/timing measurements and final documentation are
pending. Estonia has existing suitable images/JDKs/SDKs; use only the authorized
validation folder without installations. Stop after P7d1, before P7d2.

Final Mac focused selection: the two new generic tests and both P7d0 tests,
root results/retention, public API closure, ordinary generic safe-free/cast
checks, and existing mixed-retention producer have passing evidence across
`p7d1/final-focused.log` and `p7d1/producer-preservation.log`. The latter reruns
only failures: CLI fallback now preserves the precise generic refusal, and a
second obsolete primitive-array negative in the mixed producer now uses a
reference array. All three rerun checks passed. Source/class/archive public
constructor refusals preserve the existing output file. License audit and diff
checks passed. P7d1 qualification remains pending; no P7d2 work is authorized.

## P7d1 qualification, 2026-09-29

Implementation commit `3317b285`; example/runner snapshot `38fd8260`;
matched benchmark correction `ddcb04bf`. Production compiler/runtime code is
unchanged after `3317b285`. D236 and the usage guide specify the supported
boundary. No P7d2 construction/mutation or later phase was implemented.

| Platform | Focused qualification | Additional JVM replay children |
| --- | --- | ---: |
| macOS ARM64 | 20 distinct focused checks passed, including source/class/archive producer parity, shared proofs, ordinary generic safety and object generation identity | 20 passed on Java 22/23 |
| Linux ARM64, local Colima | Four generic metadata/proof/producer checks passed | 20 passed on Java 22/23 |
| Physical Linux x86-64, Estonia | Same four checks passed | 20 passed on Java 22/23 |

All use Temurin 21.0.12.1+1 producers and LLVM 23.1.0; replay JVMs are
22.0.2+9 and 23.0.2+7. Linux uses the existing pinned glibc 2.17 support SDK.
No tools were installed. No translated x86 run is presented as hardware evidence.
The Mac check of Java 24 refusal passed before native extraction; its temporary
directory remained empty. Native failures/OOM execute in checked-JNI children.
The producer itself compiles generated Java using `--release 21 -Xlint:all
-Werror`, checks archives and retains source/license companions. License audits
and diff checks passed. No unfiltered suite was run.

The generic producer covers source O0, class-directory/individual-class/archive
O3 with equal generation/API/program identities, two concrete applications,
final bounds, overloads, nulls, inherited identity, raw/upper/lower/unbounded
wildcards, failed unchecked client casts and continued calls. Owning boxes free
exactly their root allocations; borrowed boxes invalidate with their owner and
cannot free independently. Permanent families have no free method. OOM repeats
failed creation without leaking native storage or invalidating existing values.
Unsafe frees under all missing-free modes, public constructors, generic methods,
unknown production/effects, generic inputs and primitive/array projections stay
rejected. Source/class/archive constructor refusals preserve existing output.

### Matched getter measurements

Both bridge paths perform one native reference getter and a Java identity check.
Both have static factory storage and the same class-initialization guard. Three
independent JVMs per path alternate order; each has five warmup and seven
measured batches of two million calls. Values below are median batch-average
latency and corresponding throughput, not percentile latency or a confidence
interval. Estonia uses isolated CPU 1, the existing powersave governor and turbo
behavior. ARM64 is virtualized. Mac runs are functional smoke only.

| Target | Generic `Box<Quote>.get()` | Nongeneric `Plain.get()` |
| --- | ---: | ---: |
| Linux x86-64 | 11.65 ns/call; 85.87 million calls/s | 11.26 ns/call; 88.82 million calls/s |
| Linux ARM64 | 4.81 ns/call; 207.86 million calls/s | 4.74 ns/call; 210.99 million calls/s |

All 42 measured batches per target allocate **zero Java bytes and zero native
objects**. Native getter/JNI adapter instruction sequences match, apart from
symbols and addresses. Both protected getters load the same field offset after
the existing initialization guard. Java bytecode keeps the same root check and
identity lookup; generic erasure places the ordinary Quote cast in the caller.
The two-load native type-ID helper and finite switch execute only on a cache
miss, outside warmed reads. First-call initialization/conversion cost is logged
separately. No native layout tag or generic-specific per-call state was added.

The small measured differences remain numerical evidence for maintainer review,
not a demonstrated statistically significant regression or a zero-cost claim.
Ranges are 10.63-20.95 versus 10.63-20.52 ns on Estonia, and 4.68-6.22 versus
4.65-5.99 ns on ARM64. These compare generic/nongeneric bridge paths, not pure
Ironwood versus Java application throughput. No official OrderBook code changed.

An initial control lacked static factory storage and therefore lacked the native
initialization guard. Its 11.05 versus 10.26 ns Estonia result is retained under
`performance/` but superseded by `performance-matched/`. Inspection identified
the mismatch; `ddcb04bf` corrected only the benchmark and its documentation.
No production optimization or safety change was made to improve the numbers.

### Reproduction and evidence identities

Ignored local evidence root: `workspace/java-bridge/p7d1/`. Frozen input
`input-38fd8260.tar.gz` has SHA-256
`ed3aacd4714d892de89fbe2e3c0e6d0eb5f16d125372781a82b06168e9589622`.
Its `validation/contents.sha256` verifies the full source tree, compiler/test
classes and libraries. Compiler jar SHA-256 is
`7b7b0e25b92c18fe49a77c48b17b8dade32f25c82af195f8242067792d0dc57b`.
The separately verified benchmark supplement has SHA-256
`f7df8400788b09231f699b0ec9e1d711e7af7c60740036b6d7886e7d56b61c77`;
it carries its revision, patch and exact changed-file hashes.

Linux ARM64 results are in `linux-arm64/work/{evidence,validation}`. Estonia
work and payloads are retained under
`~/temp/java-bridge/p7d1-38fd8260/work/`; copied evidence is identified below.
Both use the existing bridge images, respectively
`sha256:a03b0d3a764744079949a3adf5ecf6fe7ce82382ee35ca95360a38d453af8767`
and `sha256:dd4c1e82b2f999db86e75538f7d32adcaa364452f99a8f615eca693707c382a7`.
Mount the prepared target SDK read-only at `/support` and pinned JDK directories
at `/jdks`, set `IRONWOOD_BRIDGE_SUPPORT_HOME=/support`, and run from `/work`:

```sh
sha256sum -c validation/contents.sha256
./scripts/java-bridge/qualify-generics.sh linux-x86_64 /jdks /work/evidence
```

Use `linux-arm64` for the local ARM64 environment. The expected result is four
passing checks, 20 exact-output replays and `evidence/exit.txt` containing zero.
For the matched supplement, verify its `files.sha256`, then run:

```sh
python3 examples/java-bridge/generics/benchmark.py --cpu 1 --output /work/evidence/performance-matched
```

Omit `--cpu 1` on ARM64. The output directory must be new. Expected results are
42 matching-checksum samples with zero measured Java/native allocations.
`commands.json`, `payloads.json`, `bridge.properties`, disassembly, bytecode,
raw samples and summaries preserve the exact measured payloads. The physical
host CPU, kernel, boot command line, governor and image identity are in
`validation/`. This is a focused runner with no downloads or hosted jobs.

Final Estonia evidence archive `estonia-evidence-final-logs.tar.gz` has SHA-256
`2cb51233c7d2484a8810105ce14d3be49392369285ef233785b3b928aa9803c4`,
verified after copying to this Mac and extracted into `estonia-final/`. It
contains logs, manifests, native images, disassembly, consumers and source
fixtures. Large paired jars remain on Estonia, with exact hashes and manifests
in `validation/payload-inventory.json`; the negative preserved-output sentinel
is intentionally not a ZIP. Task-generated output ownership was restored to
the SSH user so these artifacts remain directly inspectable.

Matched benchmark jar/image SHA-256 identities:

| Target | Paired jar | Native image |
| --- | --- | --- |
| Linux ARM64 | `d6ca8fca0a7528df27c9c6ce2f55a1e7ae90771c43d41599e9da6f78bdcdd360` | `7b32eb43fbfdf0c24ba30e17ef835b9235733910962392bf25da581276a0e02c` |
| Linux x86-64 | `e0b2f789bc989c22668af1f4474c350a9cf69d01b0d686715b4d038a78f3f93e` | `5304830c77b9e8acc9aa5e3d58c9a2af2772b9da8b90d631807fc43e0f0d5ab1` |

The complete generic producer's source artifacts share generation
`a9d1d67faf6faad4c8e8523d1e622eb4e61c5c911c979d44cd2f49ba61dd1bc4`
on all three targets. Assembly passed; `assembled/generics.jar` has SHA-256
`f340771a22fa3d93a44ce025306cd57dacbdc5974be1297eb2fee9078e6788e8`.
Checked-JNI consumers passed against that exact assembled jar on Mac, local
Linux ARM64 and physical Estonia. An initial Estonia consumer ran before its
large jar transfer finished and reported ClassNotFoundException. The complete
jar's SHA-256 was then checked before rerunning successfully; the operational
failure log is retained as `validation/incomplete-transfer-consumer.log`.

A final test-only supplement explicitly casts to `Box<java.lang.String>`, whose
argument is a Java-only type with no native specialization. The wildcard view
still returns the native Quote; assignment to String raises ClassCastException.
The updated consumer passed with the frozen assembled jar on all three targets,
recorded under local `java-only-cast/` and the target validation folders. The
test class and extracted Java consumer compile under Java 21. No compiler,
runtime or measured payload changed for this additional coverage.

**Stop point:** P7d1 implementation and locally available qualification are
complete. No unresolved safety/test blocker or remaining P7d1 implementation is
known. Numerical performance acceptance remains maintainer review. P7d2 and
P7e-P7f remain pending. All commits are local on `java-bridge`; no push or
publication was performed.

## P7d2 pre-change review

The maintainer authorized only bounded construction/mutation. Work remains on
local `java-bridge`; P7e is outside this task. The checkout and both origin URLs
were verified, with a clean starting tree at `ced5a3e5`.

Every mutable class variable must have one exported final nongeneric native
facade bound. This declaration proof, rather than observed factory allocations,
must cover every legal Java input. Exact source identities and erased JNI
descriptors remain distinct. Shared reference storage, mandatory reclamation,
conservative unknown effects, exception containment and D132/D133 remain intact.

Affected consumers are generic domain discovery, surface admission, constructor
entry generation, Java native parameter erasure, JNI facade selection, root
result/retention attribution and source/class/archive reconstruction. Existing
read-only finite domains must retain their input refusal. No ordinary language
ownership exemption or new runtime generic bookkeeping is planned.

Focused coverage will pair final-bounded construction/setters with unrestricted
and nonfinal-bound rejection; null/raw/wildcard/identity with invalid casts;
retained and borrowed values with premature frees, wrong owners and cycles;
normal replacement with throwing replacement and failed construction. Test
source and reconstructed artifacts at O0/O3, checked-JNI child processes and
allocation failure. Reuse adjacent root-retention, construction, non-reclamation,
generic metadata and P7d1 tests. Inspect optimized code and compare matched
generic/nongeneric calls, with Linux measurements and Java 21-23 replay. Record
payload identities, license audit and diff checks before the final local commit.

Initial Mac tests pass source/directory/individual-class/archive production,
checked JNI and allocation-limit children. A newly added test incorrectly
expected a serialized thread transfer to be refused. The established contract
explicitly allows externally synchronized transfer and adds no thread checks.
The test now verifies successful transfer and retention instead. Production
behavior was not changed. Wrong-world rejection remains covered by the existing
object bootstrap/collision checks; borrowed-owner identity has direct new cases.

Implementation commit `c6811c0b` admits final-bounded constructors/inputs and
reuses existing storage, lifetime and retention conversion. No semantic ownership
analyzer, runtime ABI or runtime implementation changed. Commit `bc4cf5a3` adds
the bounded example and focused setter qualification runner. Both are local.
The example's compile/link/run path prints `bounded values: 17 29` under checked
JNI. A Bash 3 nounset/empty-array issue in the new runner was corrected by keeping
the required output arguments in its array; Mac resumed only the benchmark
stage after all fixtures and 16 Java 22/23 replays passed. No payload changed.

Cross-platform input snapshot `workspace/java-bridge/p7d2/input-bc4cf5a3.tar.gz`
has SHA-256 `97c2d41100c6fe3d669a41d4cad04c745e5f65c8f83da488ed6f66fe21f88baf`.
It includes tracked source, the precompiled Java 21 compiler/test classes,
standard-library artifacts and a complete input hash manifest, preserving file
timestamps. Compiler jar SHA-256:
`0269e6eef50d56a84f7018e6d187b455562244e92106958e7770cbe3cb612a97`.
Linux consumes these exact compiler bytes, without rebuilding or installing.
Local ARM64 uses `workspace/java-bridge/p7d2/linux-arm64/work`; physical Estonia
uses `~/temp/java-bridge/p7d2-bc4cf5a3/work`. Both use the previously prepared
images and read-only support/JDK mounts documented above. Estonia began with
108 GiB free; only this new task directory is used.

The initial three-target matrix passed. A final additional check of a publicly
constructed, permanently published class with two final bounds found that its
private registration parameter used a raw class name. Strict generated-source
compilation correctly refused it. The generator now spells that private carrier
as `Pair<?, ?>`, preserving the same erased descriptor. The permanent two-bound
case is added to the main producer regression. This finding requires a new
compiler snapshot and complete focused target qualification before completion;
the earlier `bc4cf5a3` measurements are superseded, not final-candidate evidence.

### Final P7d2 candidate

Commit `175d1524` fixes permanent generic registration and the runner portability
issue. The two-bound permanent class now passes strict generated compilation,
checked-JNI construction, mutation, null/identity checks and absence of `free()`.
A separate pure-permanent projection also passes on Mac under
`workspace/java-bridge/p7d2/permanent-pair/`; the main matrix covers the same
case alongside reclaimable roots. No runtime or safety proof changed.

The final input snapshot is `workspace/java-bridge/p7d2/input-175d1524.tar.gz`,
SHA-256 `66f26718e84479bd82a3d1f85e916375204e2050143d634e6fa9e0025f8b026e`.
Its compiler jar SHA-256 is
`14c59cd2f7cda9b71dde336ffe7ba7164db755b25ba1cb1bf53463ff2bd9f836`.
Mac evidence is in `workspace/java-bridge/p7d2/mac-175d1524`; Linux ARM64 uses
`workspace/java-bridge/p7d2/linux-arm64-175d1524/work`; Estonia uses
`~/temp/java-bridge/p7d2-175d1524/work`. Each Linux runner verifies every input
hash before testing. Earlier directories remain intact as superseded evidence.

Thirteen distinct focused Mac checks passed during implementation. Exact names
are retained in `workspace/java-bridge/p7d2/mac-focused-checks.txt`, with copied
logs and hashes in `check-logs/`. They cover construction isolation/reconstruction,
ABI identity, non-reclamation under unknown/deallocation effects, root result
origins, retention acyclicity, borrowed owner alternatives, exceptional slot
commits, P7d1 production and object bootstrap/world collisions, plus the new
bounded proof and producer tests. The final three-target selection reruns the
bounded proof/producer and P7d1 input-refusal checks on the final compiler.
No unfiltered compiler suite was run.

Final qualification passed on macOS ARM64, local Linux ARM64 virtualization and
physical Linux x86-64 Estonia. Each target passed three focused compiler checks,
four producer variants (source O0; directory, individual class and archive O3),
eight Java 21 checked-JNI consumer/ENOMEM children and 16 exact-output Java 22/23
replays. Supported JDKs remain pinned Temurin 21.0.12.1+1, 22.0.2+9 and 23.0.2+7;
LLVM remains 23.1.0. Each target's `evidence/exit.txt` is zero (Mac uses its
evidence directory directly). The final producer fixture directories are:

| Target | Fixture run under `workspace/java-bridge/bounded-generics/producer/` |
| --- | --- |
| macOS ARM64 | `run-17043453242190954587` |
| Linux ARM64 | `run-5000521200357972924` |
| Linux x86-64 | `run-2706455830595780863` |

All four variants on each target preserve API/program/generation identities.
Across targets the complete fixture shares generation
`d7517094b162c9a885b6f3fca53055bb57d1b34547656247a580ca08d7b07de6`,
API `7785f732f31b595b261ca3c92a0009d80dc2cde03eeb1b0cceeffeacaa5c635e`
and program `612523bfcd858857d7144fa13c87e2f761de2c3effa1fb4ed8a2ee2410cb458e`.
Negative unrestricted APIs refuse production from source/directory/archive and
preserve an existing output sentinel. Safe final-bounded construction, one/two
type variables, permanent publication, raw/wildcard mutation, nulls, identity,
bad Java casts, retained/borrowed owners, failed construction, mutation before
and after exceptions, serialized thread transfer and allocation-limit rollback
have executable coverage. Nonfinal/partial bounds, generic methods/arrays,
unknown effects, cycles and unsafe frees remain rejected.

### Final matched setter measurements

These are generic versus nongeneric **bridge** setters, including existing root
input conversion and retention commits. They are not pure Java/native workload
comparisons. Each alternates two existing input roots for two million calls per
batch. Three independent JVMs per path alternate order, with five warmup and
seven measured batches each. Every target has 42 measured samples, correct final
values and zero measured Java bytes/native allocations. Reported latency is the
median batch-average cost, not an individual-call percentile.

| Target | Generic ns/call | Nongeneric ns/call | Generic M calls/s | Nongeneric M calls/s |
| --- | ---: | ---: | ---: | ---: |
| Linux x86-64 Estonia, isolated CPU 1 | 130.68 | 129.71 | 7.653 | 7.709 |
| Linux ARM64, local VM | 64.48 | 64.27 | 15.509 | 15.559 |
| macOS ARM64 | 354.16 | 335.28 | 2.824 | 2.983 |

Linux is the requested judge. Estonia ranges were 129.53-142.41 ns generic and
129.43-139.78 ns control; ARM64 ranges were 64.22-65.19 and 64.01-64.73 ns.
The existing powersave governor/turbo policy was unchanged; this is not a
fixed-frequency or statistical-significance claim. Mac ranges were 305.15-359.82
and 306.92-361.87 ns, with local VM functional work overlapping that optional
measurement. Numerical acceptance remains maintainer review.

The protected generic setter `ironwood_bridge_entry_4` and control
`ironwood_bridge_entry_7` have identical five-instruction bodies on both ARM64
and x86-64: store the field, report the existing retention slot and return
success. Java setter wrappers have the same operations and erased Value input
descriptor. JNI adapters `iw_permanent_4`/`iw_permanent_7` have matching instruction
counts and helper calls (including cold failure paths): 316/316 on Linux ARM64,
315/315 on x86-64 and 324/324 on Mac. No generic type dispatch, allocation or
additional bookkeeping appears in these paths. `setter-code-review.json`, full
disassembly and bytecode accompany each measurement.

Final measured payload SHA-256 identities:

| Target | Paired benchmark jar | Native image |
| --- | --- | --- |
| macOS ARM64 | `548940be3b12d5c155c1387cd167a166896e83617ffcbe36913804078f83523d` | `a6fb1b5df7ee70e1a270fa01fc7b0dd0501375194181b37477a8324037675d5b` |
| Linux ARM64 | `330c7e7e5f71648e141441b0b5561be8d2ab40b68f4fc38851ef2708cf13f135` | `e0f9998dcbc378bc6c11b3d0f0aa0fd5938af7646f71d057e26546b949e28508` |
| Linux x86-64 | `dadede2e235c25dfd18ec014a30d39c56217faa3c8b0e406ff2e5ed977e81821` | `bf3df59042ff21da304b14443a35fd6f001809c9c8908bbf283eb21581dea945` |

The final Estonia archive was copied to
`workspace/java-bridge/p7d2/estonia-final-175d1524.tar.gz`, SHA-256
`8976f8dd3083effe6ed7d54b8aa716afab88c287be3009d6ecc1ee2b22025221`,
verified before extraction into `estonia-175d1524/`. It includes logs, fixtures,
commands, versions, host/image identity, native image, disassembly and payload
inventory. Large paired jars/ironjars remain in the remote task directory with
streamed hashes and manifests in `validation/payload-inventory.json`. Ownership
of this task's generated workspace was restored to the SSH user. No existing
host files, images or containers were deleted; no tools were installed.

To reproduce, extract the final snapshot into a new workspace, use the existing
target image and prepared read-only SDK/JDK mounts described above, then run:

```sh
sha256sum -c validation/contents.sha256
./scripts/java-bridge/qualify-bounded-generics.sh linux-x86_64 /jdks /work/evidence-new
```

Use `linux-arm64` for the local VM. The runner expects a new evidence directory,
selects CPU 1 only for Estonia timing children, performs no installation, and
records commands, paired identities and all measurements. Expected results are
three passing checks, 16 exact replays, 42 allocation-free measured samples and
`exit.txt` containing zero. Mac uses the pinned local JDK paths and the
`macos-arm64` target. License audit, shell syntax checks, authored punctuation
checks and `git diff --check` passed.

**Stop point:** P7d2 implementation and focused qualification are complete.
No P7d2 implementation or x86 hardware check remains pending. Numerical review
belongs to the maintainer. P7e and P7f remain pending and were not started.
All changes are committed locally on `java-bridge`; no push or publication.
