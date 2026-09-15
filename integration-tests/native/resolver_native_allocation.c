// SPDX-License-Identifier: MIT OR Apache-2.0

/* Compile the actual native adapter with a controlled allocator/resolver.
 * This checks the long-name allocation branch without allocating huge Strings. */
#if defined(__linux__)
#define _POSIX_C_SOURCE 200809L
#endif
#include <assert.h>
#include <errno.h>
#include <netdb.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "../../runtime/include/ironwood_runtime.h"

static int fail_allocation, allocations, releases, queries;
static size_t expected_length;
static void *query_allocate(size_t size) {
    allocations++;
    if (fail_allocation) return NULL;
    return malloc(size);
}
static void query_free(void *pointer) {
    releases++;
    free(pointer);
}
static int query_lookup(const char *host, const char *service,
        const struct addrinfo *hints, struct addrinfo **result) {
    (void) service;
    assert(hints->ai_family == AF_UNSPEC && hints->ai_socktype == SOCK_STREAM);
    assert(strlen(host) == expected_length);
    queries++;
    *result = NULL;
    return EAI_NONAME;
}
#define malloc query_allocate
#define free query_free
#define getaddrinfo query_lookup
#include "../../runtime/src/ironwood_tcp.c"
#undef malloc
#undef free
#undef getaddrinfo

int main(void) {
    union { max_align_t alignment; unsigned char bytes[sizeof(struct ironwood_array) + 2048]; } storage;
    memset(&storage, 0, sizeof(storage));
    struct ironwood_array *host = (struct ironwood_array *) storage.bytes;
    host->length = 2048;
    memset(host->data, 'a', host->length);
    int64_t handle = -1;
    int32_t count = -1;
    fail_allocation = 1;
    assert((uint64_t) ironwood_tcp_resolve_start(host, &handle, &count) >> 32 == 2);
    assert(handle == 0 && count == 0 && allocations == 1 && releases == 0 && queries == 0);
    fail_allocation = 0;
    expected_length = host->length;
    assert((uint64_t) ironwood_tcp_resolve_start(host, &handle, &count) >> 32 == 1);
    assert(handle == 0 && count == 0 && allocations == 2 && releases == 1 && queries == 1);
    host->data[1024] = 0;
    assert((uint64_t) ironwood_tcp_resolve_start(host, &handle, &count) >> 32 == 1);
    assert(allocations == 2 && releases == 1 && queries == 1);
    host->length = 8;
    expected_length = 8;
    assert((uint64_t) ironwood_tcp_resolve_start(host, &handle, &count) >> 32 == 1);
    assert(allocations == 2 && releases == 1 && queries == 2);
    return 0;
}
