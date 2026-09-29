<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# P7d0 generic signature foundation log

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
facades live and unwritten output survives rejected public construction. A
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
