// SPDX-License-Identifier: MIT OR Apache-2.0

/* Reuse the controlled OS records, substituting only the adapter's allocator.
 * The native index has one allocation and no sorting scratch allocation. */
#define HOST_NATIVE_CONTRACTS 1
#include "host_interpose.c"
#include <time.h>
#include "../../runtime/include/ironwood_runtime.h"

static int fail_index, index_allocations, index_releases;
static void *index_allocate(size_t count, size_t size) {
    index_allocations++;
    return fail_index ? NULL : calloc(count, size);
}
static void index_free(void *pointer) {
    if (pointer) index_releases++;
    free(pointer);
}
#define getifaddrs test_getifaddrs
#define freeifaddrs test_freeifaddrs
#define if_nametoindex test_if_nametoindex
#define calloc index_allocate
#define free index_free
#include "../../runtime/src/ironwood_host.c"
#undef calloc
#undef free

int main(void) {
    int64_t handle;
    int32_t count;
    setenv("IRONWOOD_TEST_HOST_MODE", "query_error", 1);
    assert((uint64_t) ironwood_tcp_interfaces_start(&handle, &count) >> 32 == EIO && !handle && !count);
    setenv("IRONWOOD_TEST_HOST_MODE", "empty", 1);
    assert((uint64_t) ironwood_tcp_interfaces_start(&handle, &count) >> 32 == ENODEV && !handle && !count);
    unsetenv("IRONWOOD_TEST_HOST_MODE");
    fail_index = 1;
    assert((uint64_t) ironwood_tcp_interfaces_start(&handle, &count) >> 32 == ENOMEM && !handle && !count);
    assert(captures == releases && index_allocations == 1 && index_releases == 0);
    fail_index = 0;
    assert(!ironwood_tcp_interfaces_start(&handle, &count) && handle && count == 3);
    union { max_align_t alignment; unsigned char data[sizeof(struct ironwood_array) + 256]; } storage = {0};
    struct ironwood_array *buffer = (struct ironwood_array *) storage.data;
    buffer->length = 256;
    int32_t cursor = 0, index, parent, addresses, address_cursor;
    int64_t result = ironwood_tcp_interface_next(handle, cursor, buffer, &cursor, &index, &parent, &addresses, &address_cursor);
    assert(result == 6 && !memcmp(buffer->data, "alpha0", 6) && index == 7 && parent == -1 && addresses == 2);
    int32_t family, a, b, c, d, scope, prefix, broadcast, has_broadcast;
    assert(!ironwood_tcp_interface_address(handle, address_cursor, &family, &a, &b, &c, &d, &scope,
            &prefix, &broadcast, &has_broadcast, &address_cursor));
    assert(family == 6 && a == (int32_t) 0xfe800000 && !b && !c && d == 1 && scope == 7 && prefix == 64 && !has_broadcast);
    assert(!ironwood_tcp_interface_address(handle, address_cursor, &family, &a, &b, &c, &d, &scope,
            &prefix, &broadcast, &has_broadcast, &address_cursor));
    assert(family == 4 && d == (int32_t) 0xc0000201 && prefix == 24 && broadcast == (int32_t) 0xc00002ff && has_broadcast);
    assert(ironwood_tcp_interface_next(handle, cursor, buffer, &cursor, &index, &parent, &addresses, &address_cursor) == 8);
    assert(parent == 0 && index == -1 && addresses == 1 && !memcmp(buffer->data, "alpha0:1", 8));
    assert(ironwood_tcp_interface_next(handle, cursor, buffer, &cursor, &index, &parent, &addresses, &address_cursor) == 5);
    assert(parent == -1 && index == 8 && addresses == 1);
    assert((uint64_t) ironwood_tcp_interface_next(handle, cursor, buffer, &cursor, &index, &parent, &addresses, &address_cursor) >> 32 == EINVAL);
    buffer->length = 1;
    assert((uint64_t) ironwood_tcp_interface_next(handle, 0, buffer, &cursor, &index, &parent, &addresses, &address_cursor) >> 32 == ENAMETOOLONG);
    assert(!ironwood_tcp_interfaces_release(handle));
    assert(!ironwood_tcp_interfaces_release(0));
    assert(captures == releases && index_allocations == index_releases + 1);
    buffer->length = 6;
    memcpy(buffer->data, "alpha0", 6);
    union { max_align_t alignment; unsigned char data[sizeof(struct ironwood_array) + 256]; } hardware_storage = {0};
    struct ironwood_array *hardware = (struct ironwood_array *) hardware_storage.data;
    hardware->length = 256;
    setenv("IRONWOOD_TEST_HOST_MODE", "long_hardware", 1);
    assert(ironwood_tcp_interface_hardware(buffer, hardware) == 20);
    for (int n = 0; n < 20; n++) assert(hardware->data[n] == n + 2);
    hardware->length = 19;
    assert((uint64_t) ironwood_tcp_interface_hardware(buffer, hardware) >> 32 == EOVERFLOW);
    unsetenv("IRONWOOD_TEST_HOST_MODE");
    assert(captures == releases);
    int before = index_allocations;
    struct timespec start, end;
    assert(!clock_gettime(CLOCK_MONOTONIC, &start));
    for (int iteration = 0; iteration < 10000; iteration++) {
        assert(!ironwood_tcp_interfaces_start(&handle, &count) && count == 3);
        assert(!ironwood_tcp_interfaces_release(handle));
    }
    assert(!clock_gettime(CLOCK_MONOTONIC, &end));
    assert(index_allocations - before == 10000 && captures == releases && index_allocations == index_releases + 1);
    long long elapsed = (end.tv_sec - start.tv_sec) * 1000000000LL + end.tv_nsec - start.tv_nsec;
    printf("INTERFACE_CONTRACTS allocation failure, sorted records, parent links, prefixes, broadcast, bounds, cleanup\n");
    printf("INTERFACE_BENCHMARK captures=10000 index_allocations=10000 extra_sort_allocations=0 ns=%lld\n", elapsed);
    return 0;
}
