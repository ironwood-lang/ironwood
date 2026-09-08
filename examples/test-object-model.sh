#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0

set -euo pipefail

EXAMPLES_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
EXAMPLES=(
    abstractclasses
    finality
    initialization
    finalbindings
    fieldhiding
    superaccess
    qualifiedsuper
    staticnested
    innerclasses
    nestedinterfaces
    localclasses
    anonymousclasses
    qualifiedanonymous
    anonymousenclosing
    anonymousdiamond
    lexicalcapture
    nestaccess
    lexicalmembertypes
    capturedaliases
    interfacemembers
    interfacedefaults
    boundedgenerics
    genericcallables
    wildcardcapture
    genericinference
    diamond
    nestedgenerics
    innerdiamond
    genericcasts
    throwabletypes
)

for example in "${EXAMPLES[@]}"; do
    printf '\n== %s ==\n' "$example"
    "$EXAMPLES_DIR/$example/compile.sh"
    "$EXAMPLES_DIR/$example/link.sh"
    "$EXAMPLES_DIR/$example/run.sh"
done

printf '\nPASS: %d object-model examples\n' "${#EXAMPLES[@]}"
