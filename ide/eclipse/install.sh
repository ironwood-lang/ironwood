#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
#
# Publishes the built bundle as a p2 repository and installs it into Eclipse.
#
# The install goes through p2 rather than the dropins folder for two reasons.
# A shared Eclipse installation owned by another account has an unwritable
# dropins directory, while p2 writes into the current user's configuration area
# instead, and a p2 install survives later Eclipse updates that rewrite
# bundles.info. Uninstall with ./uninstall.sh.
#
# Eclipse must not be running.

set -euo pipefail

IRONWOOD_IDE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IRONWOOD_BUILD_DIR="$IRONWOOD_IDE_DIR/target"
# p2 installs features, not bare bundles, so the installable unit is the
# feature group that carries the bundle.
IRONWOOD_FEATURE_GROUP=ironwood.ide.eclipse.feature.feature.group
IRONWOOD_BUNDLE_ID=ironwood.ide.eclipse

IRONWOOD_ECLIPSE_APP=${IRONWOOD_ECLIPSE_APP:-/Applications/Eclipse-2026-03.app}
IRONWOOD_ECLIPSE_LAUNCHER="$IRONWOOD_ECLIPSE_APP/Contents/MacOS/eclipse"
IRONWOOD_P2_PROFILE=${IRONWOOD_P2_PROFILE:-epp.package.java}

if [[ ! -x "$IRONWOOD_ECLIPSE_LAUNCHER" ]]; then
    echo "error: Eclipse launcher not found: $IRONWOOD_ECLIPSE_LAUNCHER" >&2
    echo "set IRONWOOD_ECLIPSE_APP to the Eclipse application bundle" >&2
    exit 1
fi

# Match the launcher at the start of a command line. A looser pattern also
# matches the language server, whose classpath contains Eclipse plugin paths,
# and any shell command that merely mentions the installation.
if pgrep -f "^$IRONWOOD_ECLIPSE_LAUNCHER" > /dev/null 2>&1; then
    echo "error: Eclipse is running; quit it before installing" >&2
    exit 1
fi

# Building the update site is what install works from, so the path users take
# and the path used during development are the same one.
"$IRONWOOD_IDE_DIR/package.sh"

IRONWOOD_BUNDLE_JAR=$(<"$IRONWOOD_BUILD_DIR/bundle-jar.txt")
IRONWOOD_REPOSITORY="$IRONWOOD_BUILD_DIR/repository"
IRONWOOD_P2_WORKSPACE="$IRONWOOD_BUILD_DIR/p2-workspace"

# Reinstalling is an update, so remove any earlier version first. Both the
# feature and a bundle from an install made before features existed are
# removed, and an absent unit is not an error here.
echo "removing any previously installed version"
for IRONWOOD_PREVIOUS in "$IRONWOOD_FEATURE_GROUP" "$IRONWOOD_BUNDLE_ID"; do
    "$IRONWOOD_ECLIPSE_LAUNCHER" \
        -nosplash \
        -consoleLog \
        -data "$IRONWOOD_P2_WORKSPACE" \
        -application org.eclipse.equinox.p2.director \
        -uninstallIU "$IRONWOOD_PREVIOUS" \
        -profile "$IRONWOOD_P2_PROFILE" \
        >> "$IRONWOOD_BUILD_DIR/uninstall.log" 2>&1 || true
done

echo "installing $IRONWOOD_FEATURE_GROUP"
"$IRONWOOD_ECLIPSE_LAUNCHER" \
    -nosplash \
    -consoleLog \
    -data "$IRONWOOD_P2_WORKSPACE" \
    -application org.eclipse.equinox.p2.director \
    -repository "file:$IRONWOOD_REPOSITORY" \
    -installIU "$IRONWOOD_FEATURE_GROUP" \
    -profile "$IRONWOOD_P2_PROFILE" \
    > "$IRONWOOD_BUILD_DIR/install.log" 2>&1 \
    || { echo "error: p2 install failed; see $IRONWOOD_BUILD_DIR/install.log" >&2; exit 1; }

if ! grep -q "Operation completed" "$IRONWOOD_BUILD_DIR/install.log"; then
    echo "error: p2 install did not complete; see $IRONWOOD_BUILD_DIR/install.log" >&2
    exit 1
fi

echo "installed $(basename "$IRONWOOD_BUNDLE_JAR")"
echo "start Eclipse and open a .iron file to use it"
