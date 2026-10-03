// SPDX-License-Identifier: MIT OR Apache-2.0

#ifndef IRONWOOD_BRIDGE_H
#define IRONWOOD_BRIDGE_H

#include "ironwood_runtime.h"

/* Private native String layout shared with generated JNI value adapters.
 * Result proofs keep this storage live through NewString; the adapter releases
 * owned results even when NewString leaves a pending Java exception. */
struct ironwood_string {
    const struct ironwood_type_info *type;
    int32_t utf16_length;
    int32_t utf8_length;
    uint16_t units[];
};

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

/* The generated adapter reserves the exact contract's number of trailing records.
 * A null holder denotes an absent slot (null input or rolled-back constructor).
 * These records survive later failure-snapshot conversion. */
struct ironwood_bridge_slot {
    void *holder;
    void *value;
};

_Static_assert(sizeof(struct ironwood_bridge_result) == 288, "bridge result frame layout");
_Static_assert(sizeof(struct ironwood_bridge_slot) == 16, "bridge slot record layout");

/* Called under the typed entry's snapshot handler after ordinary catch cleanup.
 * Copies only immutable image metadata pointers into invocation-local storage.
 * The owning image remains loaded while an adapter consumes the snapshot. */
void ironwood_bridge_snapshot_failure(const void *object, struct ironwood_bridge_result *result);
/* Used only for a proved finite generic result's facade cache miss. */
int32_t ironwood_bridge_type_id(const void *object);

/* Protected typed entry only. The adapter supplies valid UTF-16 storage through
 * the call; -1 denotes null. The copy owns its inline character storage. */
void *ironwood_bridge_copy_string(const uint16_t *characters, int32_t length,
        const void *string_type, void *allocation_failure);

/* One state per distinct Java input identity, owned by the invocation adapter.
 * length -1 denotes null. The adapter keeps elements alive until conversion and
 * releases converted storage on every exit, including partial acquisition. */
struct ironwood_bridge_array_input {
    const void *elements;
    struct ironwood_array *converted;
    int32_t length;
};

void *ironwood_bridge_copy_array(struct ironwood_bridge_array_input *input,
        size_t element_size, uint32_t element_kind, const void *array_type,
        void *allocation_failure);

#endif
