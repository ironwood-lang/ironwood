<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Frozen-input replay after later source changes

M0 source reviewers intentionally compare their ROOT compiler sources to the
original or D247 ordered identity. They are phase checks, not claims that later
authorized compiler edits must preserve those hashes. Never reset the working
branch or rewrite a retained identity to make a later checkout match M0.

Use a full immutable M0 checkpoint commit as REV. replay-m0.py reads git archive
and materializes that commit's tools, evidence, Java sources and native library
source in a new ignored target directory. It verifies all 460 D247 source hashes.
It does not create a Git worktree, branch, checkout, remote or network operation.
Run source checks from that archived tool location so their ROOT is the pinned
view rather than the later working sources:

```sh
python3 scripts/self-hosting/replay-m0.py --revision "$REV" --output target/self-hosting-m0/replay
python3 target/self-hosting-m0/replay/scripts/self-hosting/qualify-argument-facts.py
python3 target/self-hosting-m0/replay/scripts/self-hosting/qualify-reconciliation.py
python3 target/self-hosting-m0/replay/scripts/self-hosting/review-builders.py
python3 target/self-hosting-m0/replay/scripts/self-hosting/qualify-final-inventory.py
```

Regenerated reports stay in the ignored replay view. Byte comparison with the
commit's retained report establishes deterministic source replay. New probe
qualifications use new output directories; existing successful/failed evidence
is never overwritten. Exact original command paths in reports remain historical
facts. Changing an invocation path for a new replay does not rewrite those facts
or turn the replay into the original resource measurement.

The frozen J0 installations are ignored inputs referenced by their retained
identities. Keep J0-final2 and J0-D247/J0-D247-repeat separate from the editable
checkout. If an installation is lost, reconstruct it from its identity's full
revision using freeze-j0.py, pinned JDK/LLVM paths, an absent output directory and
a new report in ignored target storage. The script uses read-only git blobs,
compiles sources and packages the library outside measured commands. Verify the
new seed JAR and library archive SHA-256 against the retained identity before
using it. Original J0 is revision 6bde84df320e9dbc32977a2b665d214ac09bc2d4;
ordered D247 is 28664736270f19a73c0a79a2f845bac916953471. If any artifact/tool
identity differs, report the mismatch instead of updating M0 hashes. When using a
different installation directory, copy the retained identity into the ignored
replay view and change only its launcher path there; retain both files and their
hashes in the new replay record. This is explicit invocation relocation, not a
new historical reference. No live repository evidence is modified.

Archive byte/manifest verification and budget derivation do not require the
current compiler source at all. Their retained captures remain authoritative;
later implementation does not justify rerunning resource measurements to replace
them. New native feasibility evidence belongs to M1/M2 with the fixed M0 budgets.
