#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

IRONWOOD_SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IRONWOOD_PROJECT_ROOT=$(CDPATH= cd -- "$IRONWOOD_SCRIPT_DIR/.." && pwd)
IRONWOOD_FAILURES=0
IRONWOOD_DERIVED_COUNT=0

IRONWOOD_REQUIRED_LICENSE_FILES=(
    LICENSE
    LICENSE-APACHE
    LICENSE-MIT
    docs/LICENSE_MECHANICS
    LICENSES/GPL-2.0-only.txt
    LICENSES/Classpath-exception-2.0.txt
    LICENSES/Unicode-15.0.txt
    docs/THIRD_PARTY_NOTICES.md
    docs/OPENJDK_PORTING.md
    docs/SOURCE_PROVENANCE.md
    docs/STDLIB_FLOATING_PARSE_SOURCE_REVIEW.md
    docs/STDLIB_S0_SOURCE_REVIEW.md
    docs/STDLIB_U1_SOURCE_REVIEW.md
    docs/STDLIB_U2_SOURCE_REVIEW.md
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
    IRONWOOD_RELATIVE_FILE=${IRONWOOD_SOURCE_FILE#"$IRONWOOD_PROJECT_ROOT/"}
    IRONWOOD_SPDX_LINE=$(grep -m 1 'SPDX-License-Identifier:' "$IRONWOOD_SOURCE_FILE" 2>/dev/null         | sed 's/^.*SPDX-License-Identifier:/SPDX-License-Identifier:/'         | sed 's/[[:space:]]*\*\/[[:space:]]*$//' || true)

    case "$IRONWOOD_SPDX_LINE" in
        "SPDX-License-Identifier: MIT OR Apache-2.0")
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
done < <(
    find         "$IRONWOOD_PROJECT_ROOT/compiler/src"         "$IRONWOOD_PROJECT_ROOT/runtime"         "$IRONWOOD_PROJECT_ROOT/stdlib/src"         "$IRONWOOD_PROJECT_ROOT/examples"         "$IRONWOOD_PROJECT_ROOT/projects"         "$IRONWOOD_PROJECT_ROOT/integration-tests"         "$IRONWOOD_PROJECT_ROOT/scripts"         "$IRONWOOD_PROJECT_ROOT/packaging"         "$IRONWOOD_PROJECT_ROOT/ide"         "$IRONWOOD_PROJECT_ROOT/.github"         -type f \(             -name '*.java' -o             -name '*.iron' -o             -name '*.c' -o             -name '*.h' -o             -name '*.sh' -o             -name '*.yml' -o             -name '*.yaml'         \) -print0
)

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
