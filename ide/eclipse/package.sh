#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
#
# Builds the Ironwood plugin and publishes it as a p2 update site.
#
# The result is a directory that Eclipse can install from directly, plus a zip
# of the same thing for publishing. This is the single producer of the p2
# repository: install.sh installs from what this builds, so the distribution
# path is the one exercised during development rather than a separate one that
# only runs at release time.

set -euo pipefail

IRONWOOD_IDE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IRONWOOD_ROOT=$(CDPATH= cd -- "$IRONWOOD_IDE_DIR/../.." && pwd)
IRONWOOD_BUILD_DIR="$IRONWOOD_IDE_DIR/target"
IRONWOOD_FEATURE_DIR="$IRONWOOD_IDE_DIR/feature"

IRONWOOD_ECLIPSE_APP=${IRONWOOD_ECLIPSE_APP:-/Applications/Eclipse-2026-03.app}
IRONWOOD_ECLIPSE_LAUNCHER="$IRONWOOD_ECLIPSE_APP/Contents/MacOS/eclipse"

IRONWOOD_JAVA_HOME=${IRONWOOD_JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home}
if [[ -x "$IRONWOOD_JAVA_HOME/bin/jar" ]]; then
    IRONWOOD_JAR_TOOL="$IRONWOOD_JAVA_HOME/bin/jar"
else
    IRONWOOD_JAR_TOOL=jar
fi

if [[ ! -x "$IRONWOOD_ECLIPSE_LAUNCHER" ]]; then
    echo "error: Eclipse launcher not found: $IRONWOOD_ECLIPSE_LAUNCHER" >&2
    echo "set IRONWOOD_ECLIPSE_APP to the Eclipse application bundle" >&2
    exit 1
fi

"$IRONWOOD_IDE_DIR/build.sh"

IRONWOOD_BUNDLE_JAR=$(<"$IRONWOOD_BUILD_DIR/bundle-jar.txt")
IRONWOOD_BUNDLE_FILE=$(basename "$IRONWOOD_BUNDLE_JAR")
# ironwood.ide.eclipse_0.1.7.202609061448.jar -> 0.1.7.202609061448
IRONWOOD_VERSION=${IRONWOOD_BUNDLE_FILE#ironwood.ide.eclipse_}
IRONWOOD_VERSION=${IRONWOOD_VERSION%.jar}

IRONWOOD_FEATURE_ID=ironwood.ide.eclipse.feature
IRONWOOD_SITE_SOURCE="$IRONWOOD_BUILD_DIR/site-source"
IRONWOOD_REPOSITORY="$IRONWOOD_BUILD_DIR/repository"

echo "packaging the update site for $IRONWOOD_VERSION"

rm -rf "$IRONWOOD_SITE_SOURCE" "$IRONWOOD_REPOSITORY"
mkdir -p "$IRONWOOD_SITE_SOURCE/plugins" "$IRONWOOD_SITE_SOURCE/features" \
    "$IRONWOOD_BUILD_DIR/feature-stage"

cp "$IRONWOOD_BUNDLE_JAR" "$IRONWOOD_SITE_SOURCE/plugins/"

# Stamp the feature and the plugin reference inside it with the build's version,
# so the feature always installs exactly the bundle built alongside it.
sed -e "s|^      version=\"0.0.0\"|      version=\"$IRONWOOD_VERSION\"|" \
    -e "s|         version=\"0.0.0\"|         version=\"$IRONWOOD_VERSION\"|" \
    "$IRONWOOD_FEATURE_DIR/feature.xml" \
    > "$IRONWOOD_BUILD_DIR/feature-stage/feature.xml"

if grep -q '0\.0\.0' "$IRONWOOD_BUILD_DIR/feature-stage/feature.xml"; then
    echo "error: the feature still carries a placeholder version" >&2
    exit 1
fi

(cd "$IRONWOOD_BUILD_DIR/feature-stage" \
    && "$IRONWOOD_JAR_TOOL" --create \
        --file "$IRONWOOD_SITE_SOURCE/features/${IRONWOOD_FEATURE_ID}_${IRONWOOD_VERSION}.jar" \
        feature.xml)

echo "  publishing bundle and feature metadata"
"$IRONWOOD_ECLIPSE_LAUNCHER" \
    -nosplash \
    -consoleLog \
    -data "$IRONWOOD_BUILD_DIR/p2-workspace" \
    -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher \
    -metadataRepository "file:$IRONWOOD_REPOSITORY" \
    -artifactRepository "file:$IRONWOOD_REPOSITORY" \
    -source "$IRONWOOD_SITE_SOURCE" \
    -configs ANY \
    -publishArtifacts \
    > "$IRONWOOD_BUILD_DIR/publish.log" 2>&1 \
    || { echo "error: p2 publish failed; see $IRONWOOD_BUILD_DIR/publish.log" >&2; exit 1; }

echo "  publishing the category"
"$IRONWOOD_ECLIPSE_LAUNCHER" \
    -nosplash \
    -consoleLog \
    -data "$IRONWOOD_BUILD_DIR/p2-workspace" \
    -application org.eclipse.equinox.p2.publisher.CategoryPublisher \
    -metadataRepository "file:$IRONWOOD_REPOSITORY" \
    -categoryDefinition "file:$IRONWOOD_FEATURE_DIR/category.xml" \
    > "$IRONWOOD_BUILD_DIR/category.log" 2>&1 \
    || { echo "error: category publish failed; see $IRONWOOD_BUILD_DIR/category.log" >&2; exit 1; }

# A repository that does not actually contain the feature would still look fine
# until someone tried to install from it.
if ! grep -q "$IRONWOOD_FEATURE_ID" "$IRONWOOD_REPOSITORY/content.xml"; then
    echo "error: the published repository does not contain $IRONWOOD_FEATURE_ID" >&2
    exit 1
fi

IRONWOOD_ARCHIVE="$IRONWOOD_BUILD_DIR/ironwood-eclipse-$IRONWOOD_VERSION.zip"
rm -f "$IRONWOOD_ARCHIVE"
(cd "$IRONWOOD_REPOSITORY" && zip -q -r "$IRONWOOD_ARCHIVE" .)

printf '%s\n' "$IRONWOOD_FEATURE_ID" > "$IRONWOOD_BUILD_DIR/feature-id.txt"
echo "published $IRONWOOD_REPOSITORY"
echo "archived  $IRONWOOD_ARCHIVE"
