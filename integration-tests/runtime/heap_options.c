// SPDX-License-Identifier: MIT OR Apache-2.0
// Observe actual startup capacity through the production parser and runtime.
// No source stack-map claim: this probe never collects or enters source code.
#include "ironwood_gc_configuration.h"
#include "../../runtime/src/ironwood_runtime.c"
#include <assert.h>

const char iw_gc_expected_configuration[] = IRONWOOD_GC_CONFIG_SHA256;

int main(int argc, char **argv) {
    int application_argc = ironwood_runtime_options(argc, argv);
    assert(application_argc == 3);
    size_t initial = (size_t)strtoull(argv[1], NULL, 10);
    size_t maximum = (size_t)strtoull(argv[2], NULL, 10);
    ironwood_gc_start();
    struct iw_gc_snapshot state = iw_gc_snapshot();
    assert(state.disabled && state.collections == 0);
    assert(state.heap_bytes >= initial && state.heap_bytes <= maximum);
    return 0;
}
