// SPDX-License-Identifier: MIT OR Apache-2.0

#ifndef IRONWOOD_BRIDGE_H
#define IRONWOOD_BRIDGE_H

#include "ironwood_runtime.h"

/* Private compiler/adapter transport, not a public native ABI commitment. */
#define IRONWOOD_BRIDGE_TRACE_CAPACITY 32
#define IRONWOOD_BRIDGE_TRACE_TRUNCATED UINT32_C(1)
#define IRONWOOD_BRIDGE_TRACE_UNAVAILABLE UINT32_C(2)

union ironwood_bridge_value {
    uint8_t boolean;
    int8_t byte;
    int16_t short_integer;
    uint16_t character;
    int32_t integer;
    int64_t wide;
    float single;
    double real;
    void *reference;
};

struct ironwood_bridge_failure {
    const char *type_name;
    int32_t frame_count;
    uint32_t flags;
    const struct ironwood_trace_site *frames[IRONWOOD_BRIDGE_TRACE_CAPACITY];
};

struct ironwood_bridge_result {
    union ironwood_bridge_value value;
    void *exception;
    struct ironwood_bridge_failure failure;
};

/* Called under the typed entry's snapshot handler after ordinary catch cleanup.
 * Copies only immutable image metadata pointers into invocation-local storage.
 * The owning image remains loaded while an adapter consumes the snapshot. */
void ironwood_bridge_snapshot_failure(const void *object, struct ironwood_bridge_result *result);

#endif
