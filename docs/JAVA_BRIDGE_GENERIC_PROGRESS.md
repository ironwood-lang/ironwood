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
