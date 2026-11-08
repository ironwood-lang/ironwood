#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

IRONWOOD_SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IRONWOOD_PROJECT_ROOT=$(CDPATH= cd -- "$IRONWOOD_SCRIPT_DIR/.." && pwd)
IRONWOOD_FAILURES=0
IRONWOOD_DERIVED_COUNT=0
IRONWOOD_SOURCE_ROOTS=(compiler/src runtime stdlib/src examples projects integration-tests scripts packaging ide .github)
IRONWOOD_SOURCE_LIST=$(mktemp)
trap 'rm -f "$IRONWOOD_SOURCE_LIST"' EXIT

if [[ -e "$IRONWOOD_PROJECT_ROOT/.git" || -L "$IRONWOOD_PROJECT_ROOT/.git" ]]; then
    # A shared container mount can have another owner. Trust only the checkout
    # whose audit script is already running, for these Git commands alone.
    IRONWOOD_AUDIT_GIT=(git -C "$IRONWOOD_PROJECT_ROOT" -c "safe.directory=$IRONWOOD_PROJECT_ROOT")
    if ! IRONWOOD_REPOSITORY_ROOT=$("${IRONWOOD_AUDIT_GIT[@]}" rev-parse --show-toplevel 2> "$IRONWOOD_SOURCE_LIST"); then
        echo "error: cannot inspect repository for license audit" >&2
        cat "$IRONWOOD_SOURCE_LIST" >&2
        exit 1
    fi
    if [[ ! "$IRONWOOD_REPOSITORY_ROOT" -ef "$IRONWOOD_PROJECT_ROOT" ]]; then
        echo "error: repository root mismatch for license audit: $IRONWOOD_REPOSITORY_ROOT (expected $IRONWOOD_PROJECT_ROOT)" >&2
        exit 1
    fi
    # Include tracked files even when ignored, plus new maintained source. SDK
    # caches and generated target output do not belong to the source inventory.
    if ! "${IRONWOOD_AUDIT_GIT[@]}" ls-files -z --cached --others --exclude-standard \
            -- "${IRONWOOD_SOURCE_ROOTS[@]}" > "$IRONWOOD_SOURCE_LIST"; then
        echo "error: cannot enumerate source files for license audit" >&2
        exit 1
    fi
else
    # Source exports have no Git index. Their target directories are generated
    # output, while ordinary source remains subject to the same header checks.
    IRONWOOD_SOURCE_DIRECTORIES=()
    for IRONWOOD_SOURCE_ROOT in "${IRONWOOD_SOURCE_ROOTS[@]}"; do
        IRONWOOD_SOURCE_DIRECTORIES+=("$IRONWOOD_PROJECT_ROOT/$IRONWOOD_SOURCE_ROOT")
    done
    find "${IRONWOOD_SOURCE_DIRECTORIES[@]}" -type d -name target -prune -o \
        -type f -print0 > "$IRONWOOD_SOURCE_LIST"
fi

IRONWOOD_REQUIRED_LICENSE_FILES=(
    LICENSE
    LICENSE-APACHE
    LICENSE-MIT
    docs/LICENSE_MECHANICS
    LICENSES/GPL-2.0-only.txt
    LICENSES/Classpath-exception-2.0.txt
    LICENSES/Unicode-15.0.txt
    LICENSES/MPL-2.0.txt
    packaging/tls-dependencies.properties
    packaging/bdwgc.json
    docs/GC1_ENGINE_PREPARATION.md
    docs/TLS.md
    docs/NETWORKING_M5_VERIFICATION.md
    docs/NETWORKING_M6_VERIFICATION.md
    docs/THIRD_PARTY_NOTICES.md
    docs/OPENJDK_PORTING.md
    docs/SOURCE_PROVENANCE.md
    docs/STDLIB_FLOATING_PARSE_SOURCE_REVIEW.md
    docs/STDLIB_S0_SOURCE_REVIEW.md
    docs/STDLIB_U1_SOURCE_REVIEW.md
    docs/STDLIB_U2_SOURCE_REVIEW.md
    docs/NETWORKING_MIGRATION_PLAN.md
    docs/STDLIB_N1_SOURCE_REVIEW.md
    docs/STDLIB_N1_VERIFICATION.md
    docs/STDLIB_U3_SOURCE_REVIEW.md
    docs/STDLIB_STRING_REVIEW.md
)

for IRONWOOD_REQUIRED_LICENSE_FILE in "${IRONWOOD_REQUIRED_LICENSE_FILES[@]}"; do
    if [[ ! -s "$IRONWOOD_PROJECT_ROOT/$IRONWOOD_REQUIRED_LICENSE_FILE" ]]; then
        echo "error: missing required license-policy file: $IRONWOOD_REQUIRED_LICENSE_FILE" >&2
        IRONWOOD_FAILURES=$((IRONWOOD_FAILURES + 1))
    fi
done

while IFS= read -r -d '' IRONWOOD_SOURCE_FILE; do
    if [[ "$IRONWOOD_SOURCE_FILE" != /* ]]; then
        IRONWOOD_SOURCE_FILE="$IRONWOOD_PROJECT_ROOT/$IRONWOOD_SOURCE_FILE"
    fi
    [[ -f "$IRONWOOD_SOURCE_FILE" && ! -L "$IRONWOOD_SOURCE_FILE" ]] || continue
    case "$IRONWOOD_SOURCE_FILE" in
        *.java|*.iron|*.c|*.h|*.cpp|*.hpp|*.sh|*.yml|*.yaml) ;;
        *) continue ;;
    esac
    IRONWOOD_RELATIVE_FILE=${IRONWOOD_SOURCE_FILE#"$IRONWOOD_PROJECT_ROOT/"}
    IRONWOOD_SPDX_LINE=$(grep -m 1 'SPDX-License-Identifier:' "$IRONWOOD_SOURCE_FILE" 2>/dev/null         | sed 's/^.*SPDX-License-Identifier:/SPDX-License-Identifier:/'         | sed 's/[[:space:]]*\*\/[[:space:]]*$//' || true)

    case "$IRONWOOD_SPDX_LINE" in
        "SPDX-License-Identifier: MIT OR Apache-2.0"|"SPDX-License-Identifier: Apache-2.0")
            if grep -Eq '^[[:space:]]*(//|\*)[[:space:]]*Derived from OpenJDK:' \
                    "$IRONWOOD_SOURCE_FILE"; then
                echo "error: OpenJDK-derived marker has the default license: $IRONWOOD_RELATIVE_FILE" >&2
                IRONWOOD_FAILURES=$((IRONWOOD_FAILURES + 1))
            fi
            ;;
        "SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0")
            IRONWOOD_DERIVED_COUNT=$((IRONWOOD_DERIVED_COUNT + 1))
            if ! grep -Eq '^[[:space:]]*(//|\*)[[:space:]]*Derived from OpenJDK:' \
                    "$IRONWOOD_SOURCE_FILE"; then
                echo "error: derived source lacks its OpenJDK path: $IRONWOOD_RELATIVE_FILE" >&2
                IRONWOOD_FAILURES=$((IRONWOOD_FAILURES + 1))
            fi
            if ! grep -Eq 'OpenJDK revision: [0-9a-fA-F]{40}([^0-9a-fA-F]|$)' "$IRONWOOD_SOURCE_FILE"; then
                echo "error: derived source lacks a full OpenJDK commit hash: $IRONWOOD_RELATIVE_FILE" >&2
                IRONWOOD_FAILURES=$((IRONWOOD_FAILURES + 1))
            fi
            if ! grep -qi 'Classpath.*exception' "$IRONWOOD_SOURCE_FILE"; then
                echo "error: derived source lacks a retained Classpath Exception notice: $IRONWOOD_RELATIVE_FILE" >&2
                IRONWOOD_FAILURES=$((IRONWOOD_FAILURES + 1))
            fi
            if ! grep -Fq "$IRONWOOD_RELATIVE_FILE" "$IRONWOOD_PROJECT_ROOT/docs/SOURCE_PROVENANCE.md"; then
                echo "error: derived source is absent from docs/SOURCE_PROVENANCE.md: $IRONWOOD_RELATIVE_FILE" >&2
                IRONWOOD_FAILURES=$((IRONWOOD_FAILURES + 1))
            fi
            if ! grep -Fq "$IRONWOOD_RELATIVE_FILE" "$IRONWOOD_PROJECT_ROOT/docs/THIRD_PARTY_NOTICES.md"; then
                echo "error: derived source is absent from THIRD_PARTY_NOTICES.md: $IRONWOOD_RELATIVE_FILE" >&2
                IRONWOOD_FAILURES=$((IRONWOOD_FAILURES + 1))
            fi
            ;;
        "")
            echo "error: source lacks an SPDX license header: $IRONWOOD_RELATIVE_FILE" >&2
            IRONWOOD_FAILURES=$((IRONWOOD_FAILURES + 1))
            ;;
        *)
            echo "error: unrecognized SPDX license in $IRONWOOD_RELATIVE_FILE: $IRONWOOD_SPDX_LINE" >&2
            IRONWOOD_FAILURES=$((IRONWOOD_FAILURES + 1))
            ;;
    esac
done < "$IRONWOOD_SOURCE_LIST"

IRONWOOD_MAKEFILE_SPDX=$(grep -m 1 'SPDX-License-Identifier:' "$IRONWOOD_PROJECT_ROOT/Makefile" || true)
if [[ "$IRONWOOD_MAKEFILE_SPDX" != "# SPDX-License-Identifier: MIT OR Apache-2.0" ]]; then
    echo "error: Makefile lacks the default SPDX license header" >&2
    IRONWOOD_FAILURES=$((IRONWOOD_FAILURES + 1))
fi

if [[ $IRONWOOD_FAILURES -ne 0 ]]; then
    echo "license audit failed with $IRONWOOD_FAILURES error(s)" >&2
    exit 1
fi

echo "license audit passed ($IRONWOOD_DERIVED_COUNT OpenJDK-derived source files)"
