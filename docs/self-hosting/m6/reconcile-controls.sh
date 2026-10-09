#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Negative controls for reconcile.py. Each control copies the checked-in
# inputs of TREE into SCRATCH, applies one defect and requires the tool to
# fail with the matching message; the unchanged copy must pass. Prints one
# line per control and exits 1 if any control is not rejected.
set -u
tree=${1:?usage: reconcile-controls.sh TREE SCRATCH}
scratch=${2:?usage: reconcile-controls.sh TREE SCRATCH}
export PYTHONDONTWRITEBYTECODE=1
failed=0
control() {
    local name=$1 expect=$2 edit=$3 copy=$scratch/$1 out status
    rm -rf "$copy" && mkdir -p "$copy"
    (cd "$tree" && tar -cf - docs compiler/src/main/ironwood stdlib/src/main/ironwood) | tar -xf - -C "$copy"
    (cd "$copy" && eval "$edit")
    out=$(python3 "$copy/docs/self-hosting/m6/reconcile.py" 2>&1)
    status=$?
    if [ -z "$expect" ] && [ $status -eq 0 ]; then
        echo "control $name passes"
    elif [ -n "$expect" ] && [ $status -ne 0 ] && printf '%s\n' "$out" | grep -q -- "$expect"; then
        echo "control $name rejected"
    else
        echo "control $name FAILED (exit $status)"; printf '%s\n' "$out" | tail -3; failed=1
    fi
    rm -rf "$copy"
}
control unchanged "" ":"
control ledger "excluded-edges.json.gz differs" \
    "gzip -dc docs/self-hosting/m0/deferred/excluded-edges.json.gz | sed 's/StandardLibrary.discover()/StandardLibrary.discover( )/' | gzip -9n > edited && mv edited docs/self-hosting/m0/deferred/excluded-edges.json.gz"
control m1-row "calls in its rows" \
    "sed '/^| API0331 |/d' docs/self-hosting/m1/CLASSIFICATION.md > edited && mv edited docs/self-hosting/m1/CLASSIFICATION.md"
control phase-rule "no longer regenerates" \
    "sed 's/(\"java.lang.Byte\", r\"^SIZE\$\", b(\"B: the literal 8\")),//' docs/self-hosting/m3/classify.py > edited && mv edited docs/self-hosting/m3/classify.py"
control decision "decision D258 is not recorded" \
    "sed 's/^## D258 - /## D25X - /' docs/DECISIONS.md > edited && mv edited docs/DECISIONS.md"
control record "record m4/HANDOFF_M4.3.md is missing" \
    "rm docs/self-hosting/m4/HANDOFF_M4.3.md"
control ir-model "no operation-model treatment" \
    "sed 's/IR_ADD_SECONDARY_EXCEPTION_INSTRUCTION, IR_ALLOCATE_INSTRUCTION,/IR_ALLOCATE_INSTRUCTION,/' compiler/src/main/ironwood/ironwood/compiler/port/IrModel.iron > edited && mv edited compiler/src/main/ironwood/ironwood/compiler/port/IrModel.iron"
control document "RECONCILIATION.md differs" \
    "sed 's/^# M6.3 inventory reconciliation/# M6.3 reconciliation/' docs/self-hosting/m6/RECONCILIATION.md > edited && mv edited docs/self-hosting/m6/RECONCILIATION.md"
control ledger-output "reconciliation.json.gz differs" \
    "gzip -dc docs/self-hosting/m6/reconciliation.json.gz | sed 's/\"Y00001\",//' | gzip -9n > edited && mv edited docs/self-hosting/m6/reconciliation.json.gz"
exit $failed
