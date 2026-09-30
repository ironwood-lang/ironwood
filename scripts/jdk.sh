#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

# JAVA_HOME is an explicit override; otherwise use the installed bundle or PATH.
# Resolve PATH's java through its runtime properties, including OS launcher shims.
ironwood_select_java() {
    local root="$1" selected settings home feature
    if [[ -n ${JAVA_HOME:-} ]]; then
        selected="$JAVA_HOME/bin/java"
    elif [[ -x "$root/toolchain/lib/jvm/bin/java" ]]; then
        selected="$root/toolchain/lib/jvm/bin/java"
    else
        selected=$(command -v java || true)
    fi
    if [[ -z "$selected" || ! -x "$selected" ]]; then
        echo "error: selected Java is missing; set JAVA_HOME to a JDK: ${selected:-java on PATH}" >&2
        return 1
    fi
    if ! settings=$("$selected" -XshowSettings:properties -version 2>&1); then
        echo "error: could not inspect selected Java: $selected" >&2
        return 1
    fi
    home=$(printf '%s\n' "$settings" | sed -n 's/^[[:space:]]*java.home = //p')
    feature=$(printf '%s\n' "$settings" | sed -n 's/^[[:space:]]*java.specification.version = //p')
    if [[ ! "$feature" =~ ^[0-9]+$ || "$feature" -lt 21 || ! -x "$home/bin/java" ]]; then
        echo "error: Ironwood requires Java 21 or newer; selected $selected (version ${feature:-unknown})" >&2
        return 1
    fi
    export JAVA_HOME="$home"
    export PATH="$JAVA_HOME/bin:$PATH"
    IRONWOOD_JAVA="$JAVA_HOME/bin/java"
    IRONWOOD_JAVA_FEATURE="$feature"
}

ironwood_require_jdk() {
    local tool version
    for tool in javac jar; do
        if [[ ! -x "$JAVA_HOME/bin/$tool" ]]; then
            echo "error: selected JDK is missing bin/$tool: $JAVA_HOME" >&2
            return 1
        fi
    done
    version=$("$JAVA_HOME/bin/javac" -version 2>&1)
    if [[ "$version" != "javac $IRONWOOD_JAVA_FEATURE" && "$version" != "javac $IRONWOOD_JAVA_FEATURE."* ]]; then
        echo "error: selected java/javac versions differ: Java $IRONWOOD_JAVA_FEATURE, $version ($JAVA_HOME)" >&2
        return 1
    fi
    IRONWOOD_JAVAC="$JAVA_HOME/bin/javac"
    IRONWOOD_JAR_TOOL="$JAVA_HOME/bin/jar"
}
