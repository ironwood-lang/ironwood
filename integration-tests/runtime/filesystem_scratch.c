// SPDX-License-Identifier: MIT OR Apache-2.0

// This harness includes the runtime after system headers, so enable POSIX first.
#if defined(__linux__) && !defined(_POSIX_C_SOURCE)
#define _POSIX_C_SOURCE 200809L
#endif

/* Instrument only Ironwood's C translation unit, not allocator activity inside
 * libc. Faults exercise unpublished-result cleanup and native fallback buffers. */
#include <errno.h>
#include <fcntl.h>
#include <setjmp.h>
#include <stddef.h>
#include <stdint.h>
#include <stdatomic.h>
#include <stdarg.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unwind.h>
#include <unistd.h>

static int heap_live, malloc_calls, calloc_calls, realloc_calls, descriptors;
static int fail_malloc, fail_calloc, fail_realloc, fail_read, fail_close, zero_size;
static int short_reads, short_writes, read_interrupt, write_interrupt, long_directory;
static int large_hint, oversized_hint;
static int fail_write, zero_write, fail_stat, open_interrupt;
static jmp_buf failure_target;
static struct _Unwind_Exception *caught_unwind;

static void *test_malloc(size_t size) {
    malloc_calls++;
    if (fail_malloc) { return NULL; }
    void *result = malloc(size);
    if (result != NULL) { heap_live++; }
    return result;
}
static void *test_calloc(size_t count, size_t size) {
    calloc_calls++;
    if (fail_calloc) { return NULL; }
    void *result = calloc(count, size);
    if (result != NULL) { heap_live++; }
    return result;
}
static void *test_realloc(void *value, size_t size) {
    realloc_calls++;
    if (fail_realloc) { return NULL; }
    void *result = realloc(value, size);
    if (result != NULL && value == NULL) { heap_live++; }
    return result;
}
static void test_free(void *value) {
    if (value != NULL) { heap_live--; }
    free(value);
}
static int test_open(const char *path, int flags, ...) {
    if (open_interrupt) { open_interrupt = 0; errno = EINTR; return -1; }
    int result;
    if ((flags & O_CREAT) != 0) {
        va_list arguments;
        va_start(arguments, flags);
        int mode = va_arg(arguments, int);
        va_end(arguments);
        result = open(path, flags, mode);
    } else {
        result = open(path, flags);
    }
    if (result >= 0) { descriptors++; }
    return result;
}
static int test_close(int descriptor) {
    int result = close(descriptor);
    if (result == 0) { descriptors--; }
    if (fail_close) { errno = EIO; return -1; }
    return result;
}
static int test_fstat(int descriptor, struct stat *status) {
    if (fail_stat) { errno = EIO; return -1; }
    int result = fstat(descriptor, status);
    if (result == 0 && zero_size) { status->st_size = 0; }
    if (result == 0 && large_hint) { status->st_size += 4096; }
    if (result == 0 && oversized_hint) { status->st_size = (off_t) INT32_MAX + 1; }
    return result;
}
static ssize_t test_read(int descriptor, void *data, size_t length) {
    if (fail_read) { errno = EIO; return -1; }
    if (read_interrupt) { read_interrupt = 0; errno = EINTR; return -1; }
    return read(descriptor, data, short_reads && length > 1 ? 1 : length);
}
static ssize_t test_write(int descriptor, const void *data, size_t length) {
    if (fail_write) { errno = EIO; return -1; }
    if (zero_write) { return 0; }
    if (write_interrupt) { write_interrupt = 0; errno = EINTR; return -1; }
    return write(descriptor, data, short_writes && length > 1 ? 1 : length);
}
static char *test_getcwd(char *buffer, size_t size) {
    if (!long_directory) { return getcwd(buffer, size); }
    if (size < 6001) { errno = ERANGE; return NULL; }
    buffer[0] = '/';
    memset(buffer + 1, 'x', 5999);
    buffer[6000] = '\0';
    return buffer;
}
static _Unwind_Reason_Code test_unwind(struct _Unwind_Exception *exception) {
    caught_unwind = exception;
    longjmp(failure_target, 1);
}

#define malloc test_malloc
#define calloc test_calloc
#define realloc test_realloc
#define free test_free
#define open test_open
#define close test_close
#define fstat test_fstat
#define read test_read
#define write test_write
#define getcwd test_getcwd
#define _Unwind_RaiseException test_unwind
#include "../../runtime/src/ironwood_runtime.c"
#undef malloc
#undef calloc
#undef realloc
#undef free
#undef open
#undef close
#undef fstat
#undef read
#undef write
#undef getcwd
#undef _Unwind_RaiseException

#define CHECK(condition) do { if (!(condition)) { fprintf(stderr, "line %d\n", __LINE__); return 1; } } while (0)

static const struct ironwood_type_info string_type = { .name = "String" };
static const struct ironwood_type_info array_type = { .name = "byte[]" };
static struct ironwood_throwable allocation_error;

/* U3: the retained descriptor boundary has no successful-path heap scratch.
 * Fault injection covers partial/interrupted calls, no-progress writes, failed
 * stat after open, close failure, and bounded repeated descriptor ownership. */
static int streaming_boundary(const struct ironwood_string *path,
                              const struct ironwood_string *long_path) {
    struct ironwood_array *bytes = ironwood_allocate_array(257, 1,
            IRONWOOD_ARRAY_BYTE, &array_type, &allocation_error);
    for (int index = 0; index < 257; index++) { bytes->data[index] = (unsigned char) index; }
    int baseline = heap_live, allocations = calloc_calls, scratch = malloc_calls;
    for (int round = 0; round < 100; round++) {
        open_interrupt = short_writes = write_interrupt = 1;
        int output = ironwood_stream_open(path, 1, &allocation_error);
        CHECK(output >= 0 && descriptors == 1);
        CHECK(ironwood_stream_write_bytes(output, bytes, 0, 257) == 0);
        CHECK(ironwood_stream_close(output) == 0 && descriptors == 0);
        int input = ironwood_stream_open(path, 0, &allocation_error);
        CHECK(input >= 0 && ironwood_stream_available(input) == 257);
        short_reads = read_interrupt = 1;
        for (int index = 0; index < 257; index++) {
            CHECK(ironwood_stream_read_bytes(input, bytes, 0, 257) == 1);
            CHECK(bytes->data[0] == (unsigned char) index);
        }
        CHECK(ironwood_stream_read_byte(input) == -1);
        CHECK(ironwood_stream_read_bytes(input, bytes, 0, 0) == 0);
        CHECK(ironwood_stream_available(input) == 0);
        CHECK(ironwood_stream_close(input) == 0 && descriptors == 0);
        CHECK(heap_live == baseline && calloc_calls == allocations && malloc_calls == scratch);
        for (int index = 0; index < 257; index++) { bytes->data[index] = (unsigned char) index; }
    }
    int output = ironwood_stream_open(path, 1, &allocation_error);
    fail_write = 1;
    CHECK(ironwood_stream_write_byte(output, 42) == -1);
    fail_write = 0;
    zero_write = 1;
    CHECK(ironwood_stream_write_bytes(output, bytes, 0, 1) == -1);
    zero_write = 0;
    fail_close = 1;
    CHECK(ironwood_stream_close(output) == -1 && descriptors == 0);
    fail_close = 0;
    int input = ironwood_stream_open(path, 0, &allocation_error);
    fail_read = 1;
    CHECK(ironwood_stream_read_byte(input) == -2);
    CHECK(ironwood_stream_read_bytes(input, bytes, 0, 1) == -2);
    fail_read = 0;
    CHECK(ironwood_stream_close(input) == 0);
    fail_stat = 1;
    CHECK(ironwood_stream_open(path, 0, &allocation_error) < 0 && descriptors == 0);
    fail_stat = 0;
    CHECK(ironwood_stream_available(-1) == -1);
    fail_malloc = 1;
    if (setjmp(failure_target) == 0) {
        ironwood_stream_open(long_path, 0, &allocation_error);
        CHECK(0);
    }
    cleanup_exception(_URC_FOREIGN_EXCEPTION_CAUGHT, caught_unwind);
    ironwood_exception_caught(&allocation_error);
    fail_malloc = 0;
    CHECK(heap_live == baseline && descriptors == 0);
    ironwood_deallocate(bytes);
    return 0;
}

int main(int argc, char **argv) {
    CHECK(argc == 2 && chdir(argv[1]) == 0);
    struct ironwood_string *path = string_from_utf8_bytes(
            (const unsigned char *) "scratch.txt", 11, &string_type, NULL);
    struct ironwood_string *text = string_from_utf8_bytes(
            (const unsigned char *) "A\xF0\x9F\x8C\xB2\xC3\xA9Z", 8, &string_type, NULL);
    int baseline = heap_live;
    int allocations = calloc_calls;
    int scratch = malloc_calls;
    short_writes = write_interrupt = 1;
    CHECK(ironwood_file_write_string(path, text, &allocation_error) == 0);
    CHECK(ironwood_file_kind(path, &allocation_error) == 1);
    CHECK(ironwood_file_size(path, &allocation_error) == 8);
    CHECK(calloc_calls == allocations && malloc_calls == scratch && descriptors == 0);

    for (int mode = 0; mode < 4; mode++) {
        zero_size = mode & 1; /* Simulates a growing file or a non-size-reporting source. */
        large_hint = mode == 2; /* Simulates shrinkage after the size hint. */
        short_reads = read_interrupt = 1;
        void *result = mode < 2 ? ironwood_file_read_string(path, &string_type, &allocation_error)
                : ironwood_file_read_all_bytes(path, &array_type, &allocation_error);
        CHECK(result != NULL && heap_live == baseline + 1 && descriptors == 0);
        if (mode < 2) { CHECK(ironwood_string_equals(result, text, &string_type)); }
        else { CHECK(((struct ironwood_array *) result)->length == 8); }
        ironwood_deallocate(result);
        CHECK(heap_live == baseline && malloc_calls == scratch);
    }
    zero_size = large_hint = 0;
    fail_realloc = 1;
    void *uncompacted = ironwood_file_read_string(path, &string_type, &allocation_error);
    CHECK(uncompacted != NULL && ironwood_string_equals(uncompacted, text, &string_type));
    ironwood_deallocate(uncompacted);
    fail_realloc = 0;
    oversized_hint = 1;
    allocations = calloc_calls;
    CHECK(ironwood_file_read_all_bytes(path, &array_type, &allocation_error) == NULL);
    CHECK(last_file_error == IRONWOOD_FILE_ERROR_TOO_LARGE && calloc_calls == allocations);
    oversized_hint = 0;
    const unsigned char invalid[][4] = {
        {0xC0, 0x80}, {0x80}, {0xE2}, {0xED, 0xA0, 0x80},
        {0xF4, 0x90, 0x80, 0x80}, {0xF0, 0x80, 0x80, 0x80}, {0xC3, 0x28}
    };
    const size_t invalid_lengths[] = {2, 1, 1, 3, 4, 4, 2};
    for (size_t index = 0; index < sizeof(invalid_lengths) / sizeof(size_t); index++) {
        int descriptor = open("scratch.txt", O_WRONLY | O_TRUNC);
        CHECK(descriptor >= 0 && write(descriptor, invalid[index], invalid_lengths[index])
                == (ssize_t) invalid_lengths[index] && close(descriptor) == 0);
        CHECK(ironwood_file_read_string(path, &string_type, &allocation_error) == NULL);
        CHECK(last_file_error == IRONWOOD_FILE_ERROR_INVALID_UTF8
                && heap_live == baseline && descriptors == 0);
    }
    CHECK(ironwood_file_write_string(path, text, &allocation_error) == 0);
    for (int fault = 0; fault < 2; fault++) {
        fail_read = fault == 0;
        fail_close = fault == 1;
        CHECK(ironwood_file_read_string(path, &string_type, &allocation_error) == NULL);
        CHECK(heap_live == baseline && descriptors == 0);
    }
    fail_read = fail_close = 0;
    /* Initial-result OOM, growing-result OOM, and long-path/cwd fallback OOM. */
    struct ironwood_string *long_path = allocate_string(6000, &string_type, NULL);
    for (int index = 0; index < 6000; index++) { long_path->units[index] = 'x'; }
    long_path->utf8_length = 6000;
    baseline = heap_live;
    for (int fault = 0; fault < 4; fault++) {
        fail_calloc = fault == 0;
        zero_size = fail_realloc = fault == 1;
        fail_malloc = fault >= 2;
        long_directory = fault == 3;
        if (setjmp(failure_target) == 0) {
            if (fault < 2) { ironwood_file_read_string(path, &string_type, &allocation_error); }
            else if (fault == 2) { ironwood_file_kind(long_path, &allocation_error); }
            else { ironwood_path_absolute(path, &string_type, &allocation_error); }
            CHECK(0);
        }
        cleanup_exception(_URC_FOREIGN_EXCEPTION_CAUGHT, caught_unwind);
        ironwood_exception_caught(&allocation_error);
        CHECK(heap_live == baseline && descriptors == 0);
    }
    fail_calloc = fail_malloc = fail_realloc = zero_size = long_directory = 0;
    scratch = malloc_calls;
    allocations = calloc_calls;
    struct ironwood_string *normalized = ironwood_path_normalize_syntax(long_path, &string_type, NULL);
    CHECK(calloc_calls == allocations + 1 && malloc_calls == scratch);
    ironwood_deallocate(normalized);
    normalized = ironwood_path_normalize(long_path, &string_type, NULL);
    CHECK(malloc_calls == scratch && heap_live == baseline + 1);
    ironwood_deallocate(normalized);
    normalized = ironwood_path_resolve(long_path, path, &string_type, NULL);
    CHECK(malloc_calls == scratch && heap_live == baseline + 1);
    ironwood_deallocate(normalized);
    normalized = ironwood_path_resolve_sibling(long_path, path, &string_type, NULL);
    CHECK(malloc_calls == scratch && heap_live == baseline + 1);
    ironwood_deallocate(normalized);
    long_directory = 1;
    struct ironwood_string *absolute = ironwood_path_absolute(path, &string_type, NULL);
    CHECK(absolute != NULL && absolute->utf16_length == 6012 && heap_live == baseline + 1);
    ironwood_deallocate(absolute);
    long_directory = 0;
    CHECK(streaming_boundary(path, long_path) == 0);
    ironwood_deallocate(long_path);
    ironwood_deallocate(text);
    ironwood_deallocate(path);
    CHECK(heap_live == 0 && descriptors == 0);
    return 0;
}
