#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
#
# Removes the Ironwood plugin from Eclipse through p2, leaving the installation
# in the state it had before ./install.sh ran.
#
# Eclipse must not be running.

set -euo pipefail

IRONWOOD_IDE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IRONWOOD_BUILD_DIR="$IRONWOOD_IDE_DIR/target"
IRONWOOD_FEATURE_GROUP=ironwood.ide.eclipse.feature.feature.group
IRONWOOD_BUNDLE_ID=ironwood.ide.eclipse

IRONWOOD_ECLIPSE_APP=${IRONWOOD_ECLIPSE_APP:-/Applications/Eclipse-2026-03.app}
IRONWOOD_ECLIPSE_LAUNCHER="$IRONWOOD_ECLIPSE_APP/Contents/MacOS/eclipse"
IRONWOOD_P2_PROFILE=${IRONWOOD_P2_PROFILE:-epp.package.java}

if [[ ! -x "$IRONWOOD_ECLIPSE_LAUNCHER" ]]; then
    echo "error: Eclipse launcher not found: $IRONWOOD_ECLIPSE_LAUNCHER" >&2
    exit 1
fi

# Match the launcher at the start of a command line. A looser pattern also
# matches the language server, whose classpath contains Eclipse plugin paths,
# and any shell command that merely mentions the installation.
if pgrep -f "^$IRONWOOD_ECLIPSE_LAUNCHER" > /dev/null 2>&1; then
    echo "error: Eclipse is running; quit it before uninstalling" >&2
    exit 1
fi

mkdir -p "$IRONWOOD_BUILD_DIR"

# Removes the feature, and also a bundle from an install made before the
# feature existed. Neither being present is not an error.
for IRONWOOD_UNIT in "$IRONWOOD_FEATURE_GROUP" "$IRONWOOD_BUNDLE_ID"; do
    echo "uninstalling $IRONWOOD_UNIT"
    "$IRONWOOD_ECLIPSE_LAUNCHER" \
        -nosplash \
        -consoleLog \
        -data "$IRONWOOD_BUILD_DIR/p2-workspace" \
        -application org.eclipse.equinox.p2.director \
        -uninstallIU "$IRONWOOD_UNIT" \
        -profile "$IRONWOOD_P2_PROFILE" \
        >> "$IRONWOOD_BUILD_DIR/uninstall.log" 2>&1 || true
done

echo "uninstalled the Ironwood plugin"
