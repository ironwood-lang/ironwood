#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
#
# Builds the Ironwood Eclipse plugin into a plain OSGi bundle jar.
#
# The bundle is compiled against the target Eclipse installation's own plugin
# jars rather than a downloaded target platform, so the build stays offline and
# needs no Maven, Tycho, or PDE. HashSet IRONWOOD_ECLIPSE_HOME to build against a
# different Eclipse.

set -euo pipefail

IRONWOOD_IDE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IRONWOOD_ROOT=$(CDPATH= cd -- "$IRONWOOD_IDE_DIR/../.." && pwd)
IRONWOOD_PLUGIN_DIR="$IRONWOOD_IDE_DIR/plugin"
IRONWOOD_BUILD_DIR="$IRONWOOD_IDE_DIR/target"

IRONWOOD_ECLIPSE_HOME=${IRONWOOD_ECLIPSE_HOME:-/Applications/Eclipse-2026-03.app/Contents/Eclipse}
IRONWOOD_ECLIPSE_PLUGINS="$IRONWOOD_ECLIPSE_HOME/plugins"

if [[ ! -d "$IRONWOOD_ECLIPSE_PLUGINS" ]]; then
    echo "error: Eclipse plugins directory not found: $IRONWOOD_ECLIPSE_PLUGINS" >&2
    echo "set IRONWOOD_ECLIPSE_HOME to the Eclipse installation to build against" >&2
    exit 1
fi

# The bundle targets JavaSE-21, matching the bootstrap compiler and the JVM
# Eclipse itself runs on.
IRONWOOD_JAVA_HOME=${IRONWOOD_JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home}
if [[ -x "$IRONWOOD_JAVA_HOME/bin/java" ]]; then
    IRONWOOD_JAVA="$IRONWOOD_JAVA_HOME/bin/java"
    IRONWOOD_JAVAC="$IRONWOOD_JAVA_HOME/bin/javac"
else
    IRONWOOD_JAVA=java
    IRONWOOD_JAVAC=javac
fi

# OSGi versions are strictly numeric with an optional qualifier, so 0.1.7-beta
# becomes 0.1.7 with a build timestamp qualifier.
IRONWOOD_VERSION_LABEL=$(<"$IRONWOOD_ROOT/VERSION")
IRONWOOD_VERSION_TRIPLE=$(printf '%s' "$IRONWOOD_VERSION_LABEL" \
    | sed -E 's/^([0-9]+\.[0-9]+\.[0-9]+).*$/\1/')
if [[ ! "$IRONWOOD_VERSION_TRIPLE" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "error: cannot derive an OSGi version from VERSION '$IRONWOOD_VERSION_LABEL'" >&2
    exit 1
fi
IRONWOOD_QUALIFIER=$(date -u +%Y%m%d%H%M)
IRONWOOD_BUNDLE_VERSION="$IRONWOOD_VERSION_TRIPLE.$IRONWOOD_QUALIFIER"
IRONWOOD_BUNDLE_ID=ironwood.ide.eclipse
IRONWOOD_BUNDLE_JAR="$IRONWOOD_BUILD_DIR/${IRONWOOD_BUNDLE_ID}_${IRONWOOD_BUNDLE_VERSION}.jar"

echo "building $IRONWOOD_BUNDLE_ID $IRONWOOD_BUNDLE_VERSION"
echo "  eclipse: $IRONWOOD_ECLIPSE_HOME"

# The grammar generator needs the bootstrap compiler classes, since it asks the
# real lexer which words are keywords.
IRONWOOD_COMPILER_JAR="$IRONWOOD_ROOT/compiler/build/ironwoodc.jar"
if [[ ! -f "$IRONWOOD_COMPILER_JAR" ]]; then
    echo "  building the bootstrap compiler first"
    "$IRONWOOD_ROOT/scripts/build.sh"
fi

rm -rf "$IRONWOOD_BUILD_DIR"
mkdir -p "$IRONWOOD_BUILD_DIR/bundle"

echo "  generating the TextMate grammar from the compiler lexer"
"$IRONWOOD_JAVA" -cp "$IRONWOOD_COMPILER_JAR" \
    "$IRONWOOD_IDE_DIR/tools/GenerateGrammar.java" \
    "$IRONWOOD_PLUGIN_DIR/syntaxes/ironwood.tmLanguage.template.json" \
    "$IRONWOOD_PLUGIN_DIR/syntaxes/ironwood.tmLanguage.json"

# Tokenize the fixture with the same TM4E engine Eclipse uses and assert the
# resulting scopes, so a broken grammar fails the build instead of shipping as
# silently uncolored source.
echo "  verifying the grammar against the TM4E engine"
IRONWOOD_TM4E_CLASSPATH=$(find "$IRONWOOD_ECLIPSE_PLUGINS" \
    \( -name 'org.eclipse.tm4e.core_*.jar' \
    -o -name 'com.google.gson_*.jar' \
    -o -name 'org.joni_*.jar' \
    -o -name 'org.jcodings_*.jar' \
    -o -name 'org.snakeyaml.engine_*.jar' \) | tr '\n' ':')
"$IRONWOOD_JAVA" -cp "$IRONWOOD_TM4E_CLASSPATH" \
    "$IRONWOOD_IDE_DIR/tools/VerifyGrammar.java" \
    "$IRONWOOD_PLUGIN_DIR/syntaxes/ironwood.tmLanguage.json" \
    "$IRONWOOD_IDE_DIR/tools/fixtures/Highlighting.iron"

# Stage the bundle contents. The template stays in the source tree; only the
# generated grammar ships.
mkdir -p "$IRONWOOD_BUILD_DIR/bundle/META-INF" "$IRONWOOD_BUILD_DIR/bundle/syntaxes"

echo "  drawing icons"
"$IRONWOOD_JAVA" "$IRONWOOD_IDE_DIR/tools/GenerateIcons.java" \
    "$IRONWOOD_BUILD_DIR/bundle/icons" > /dev/null
sed "s/^Bundle-Version: .*/Bundle-Version: $IRONWOOD_BUNDLE_VERSION/" \
    "$IRONWOOD_PLUGIN_DIR/META-INF/MANIFEST.MF" \
    > "$IRONWOOD_BUILD_DIR/bundle/META-INF/MANIFEST.MF"
cp "$IRONWOOD_PLUGIN_DIR/plugin.xml" "$IRONWOOD_BUILD_DIR/bundle/plugin.xml"
cp "$IRONWOOD_PLUGIN_DIR/syntaxes/ironwood.tmLanguage.json" \
    "$IRONWOOD_PLUGIN_DIR/syntaxes/ironwood-language-configuration.json" \
    "$IRONWOOD_BUILD_DIR/bundle/syntaxes/"

# The language server ships inside the bundle so that installing the plugin is
# all it takes to get diagnostics. It is launched as a separate process rather
# than loaded as OSGi code, so it is a plain resource here.
echo "  building the language server"
"$IRONWOOD_IDE_DIR/../langserver/build.sh" > "$IRONWOOD_BUILD_DIR/langserver-build.log" 2>&1 \
    || { cat "$IRONWOOD_BUILD_DIR/langserver-build.log" >&2; exit 1; }
mkdir -p "$IRONWOOD_BUILD_DIR/bundle/lib"
cp "$IRONWOOD_IDE_DIR/../langserver/target/ironwood-langserver.jar" \
    "$IRONWOOD_BUILD_DIR/bundle/lib/"

# Compile the bundle's Java sources when it has any. A resource-only bundle is
# valid OSGi, so an empty src tree is not an error.
if [[ -d "$IRONWOOD_PLUGIN_DIR/src" ]] \
        && find "$IRONWOOD_PLUGIN_DIR/src" -name '*.java' -print -quit | grep -q .; then
    echo "  compiling bundle classes"
    IRONWOOD_CLASSPATH=$(find "$IRONWOOD_ECLIPSE_PLUGINS" -name '*.jar' | tr '\n' ':')
    mkdir -p "$IRONWOOD_BUILD_DIR/classes"
    find "$IRONWOOD_PLUGIN_DIR/src" -name '*.java' > "$IRONWOOD_BUILD_DIR/sources.txt"
    "$IRONWOOD_JAVAC" \
        --release 21 \
        -nowarn \
        -cp "$IRONWOOD_CLASSPATH" \
        -d "$IRONWOOD_BUILD_DIR/classes" \
        @"$IRONWOOD_BUILD_DIR/sources.txt"
    cp -R "$IRONWOOD_BUILD_DIR/classes/." "$IRONWOOD_BUILD_DIR/bundle/"

    # The builder recovers errors from the compiler's printed output, so a
    # change to that format would silently stop producing markers. Run the real
    # compiler over known-bad source and check that every diagnostic survives.
    if [[ -x "$IRONWOOD_ROOT/bin/ironwoodc" ]]; then
        echo "  verifying compiler output parsing"
        rm -rf "$IRONWOOD_BUILD_DIR/parse-check"
        mkdir -p "$IRONWOOD_BUILD_DIR/parse-check"
        "$IRONWOOD_JAVA" -cp "$IRONWOOD_BUILD_DIR/classes" \
            "$IRONWOOD_IDE_DIR/tools/VerifyCompilerOutput.java" \
            "$IRONWOOD_ROOT/bin/ironwoodc" \
            "$IRONWOOD_BUILD_DIR/parse-check"

        # The new-project wizard generates plain text that no compiler would
        # otherwise check, and a wizard that produces a broken project is worse
        # than no wizard.
        IRONWOOD_TESTING_ARCHIVE="$IRONWOOD_ROOT/compiler/build/ironwood-testing.ironjar"
        if [[ -f "$IRONWOOD_TESTING_ARCHIVE" ]]; then
            echo "  verifying the new-project templates compile"
            rm -rf "$IRONWOOD_BUILD_DIR/template-check"
            mkdir -p "$IRONWOOD_BUILD_DIR/template-check"
            "$IRONWOOD_JAVA" -cp "$IRONWOOD_BUILD_DIR/classes" \
                "$IRONWOOD_IDE_DIR/tools/VerifyTemplates.java" \
                "$IRONWOOD_ROOT/bin/ironwoodc" \
                "$IRONWOOD_TESTING_ARCHIVE" \
                "$IRONWOOD_BUILD_DIR/template-check"
        fi
    fi
fi

echo "  packaging $IRONWOOD_BUNDLE_JAR"
(cd "$IRONWOOD_BUILD_DIR/bundle" \
    && "$IRONWOOD_JAVA_HOME/bin/jar" --create \
        --file "$IRONWOOD_BUNDLE_JAR" \
        --manifest META-INF/MANIFEST.MF \
        .)

echo "built $IRONWOOD_BUNDLE_JAR"
printf '%s\n' "$IRONWOOD_BUNDLE_JAR" > "$IRONWOOD_BUILD_DIR/bundle-jar.txt"
