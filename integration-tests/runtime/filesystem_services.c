// SPDX-License-Identifier: MIT OR Apache-2.0

// This harness includes the runtime after system headers, so enable POSIX first.
#if defined(__linux__) && !defined(_POSIX_C_SOURCE)
#define _POSIX_C_SOURCE 200809L
#endif

/* M4 native filesystem services under injected host behavior: temporary-name
 * collisions and exhaustion, a failed close after creation, a failed String
 * allocation after creation, long stems, the Linux random-source fallback,
 * real paths with invalid UTF-8 and access checks. Only Ironwood's
 * translation unit is instrumented, not libc. */
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <setjmp.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <unwind.h>
#if defined(__linux__)
#include <sys/syscall.h>
#endif

static int heap_live, descriptors;
static int fail_calloc, exist_opens, exist_mkdirs, fail_close, opens, mkdirs;
#if defined(__linux__)
static int random_unavailable, urandom_unavailable;
#endif
static jmp_buf failure_target;
static struct _Unwind_Exception *caught_unwind;

static void *test_malloc(size_t size) {
    void *result = malloc(size);
    if (result != NULL) { heap_live++; }
    return result;
}
static void *test_calloc(size_t count, size_t size) {
    if (fail_calloc) { return NULL; }
    void *result = calloc(count, size);
    if (result != NULL) { heap_live++; }
    return result;
}
static void *test_realloc(void *value, size_t size) {
    void *result = realloc(value, size);
    if (result != NULL && value == NULL) { heap_live++; }
    return result;
}
static void test_free(void *value) {
    if (value != NULL) { heap_live--; }
    free(value);
}
static int test_open(const char *path, int flags, ...) {
    int mode = 0;
    if ((flags & O_CREAT) != 0) {
        va_list arguments;
        va_start(arguments, flags);
        mode = va_arg(arguments, int);
        va_end(arguments);
    }
#if defined(__linux__)
    if (urandom_unavailable && strcmp(path, "/dev/urandom") == 0) { errno = ENOENT; return -1; }
#endif
    if ((flags & O_EXCL) != 0) {
        opens++;
        if (exist_opens > 0) { exist_opens--; errno = EEXIST; return -1; }
    }
    int result = open(path, flags, mode);
    if (result >= 0) { descriptors++; }
    return result;
}
static int test_close(int descriptor) {
    int result = close(descriptor);
    if (result == 0) { descriptors--; }
    if (fail_close) { errno = EIO; return -1; }
    return result;
}
static int test_mkdir(const char *path, mode_t mode) {
    mkdirs++;
    if (exist_mkdirs > 0) { exist_mkdirs--; errno = EEXIST; return -1; }
    return mkdir(path, mode);
}
#if defined(__linux__)
static long test_syscall(long number, ...) {
    va_list arguments;
    va_start(arguments, number);
    void *buffer = va_arg(arguments, void *);
    size_t length = va_arg(arguments, size_t);
    unsigned flags = va_arg(arguments, unsigned);
    va_end(arguments);
    if (number == SYS_getrandom && random_unavailable) { errno = ENOSYS; return -1; }
    return syscall(number, buffer, length, flags);
}
#endif
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
#define mkdir test_mkdir
#if defined(__linux__)
#define syscall test_syscall
#endif
#define _Unwind_RaiseException test_unwind
#include "../../runtime/src/ironwood_runtime.c"
#undef malloc
#undef calloc
#undef realloc
#undef free
#undef open
#undef close
#undef mkdir
#if defined(__linux__)
#undef syscall
#endif
#undef _Unwind_RaiseException

#define CHECK(condition) do { if (!(condition)) { fprintf(stderr, "line %d\n", __LINE__); return 1; } } while (0)

static const struct ironwood_type_info string_type = { .name = "String" };
static struct ironwood_throwable allocation_error;

static struct ironwood_string *text(const char *value) {
    return string_from_utf8_bytes((const unsigned char *) value, strlen(value), &string_type, NULL);
}

static int entries(void) {
    DIR *directory = opendir(".");
    if (directory == NULL) { return -1; }
    int count = 0;
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        if (strcmp(entry->d_name, ".") != 0 && strcmp(entry->d_name, "..") != 0) { count++; }
    }
    closedir(directory);
    return count;
}

/* The created name is the stem, 1-20 decimal digits and the suffix. */
static int named(const struct ironwood_string *created, const char *stem, const char *suffix) {
    char *value = string_to_utf8(created, NULL);
    size_t stem_length = strlen(stem), suffix_length = strlen(suffix), length = strlen(value);
    int result = length > stem_length + suffix_length && length <= stem_length + 20U + suffix_length
            && strncmp(value, stem, stem_length) == 0
            && strcmp(value + length - suffix_length, suffix) == 0;
    for (size_t index = stem_length; result && index < length - suffix_length; index++) {
        result = value[index] >= '0' && value[index] <= '9';
    }
    test_free(value);
    return result;
}

static int remove_created(struct ironwood_string *created, _Bool directory) {
    char *value = string_to_utf8(created, NULL);
    int result = directory ? rmdir(value) : unlink(value);
    test_free(value);
    ironwood_deallocate(created);
    return result;
}

static int temporaries(void) {
    struct ironwood_string *stem = text("t-");
    struct ironwood_string *suffix = text(".tmp");
    struct ironwood_string *slashes = text(".s//");
    int baseline = heap_live;
    /* Ordinary creation, then collisions resolved by fresh names. */
    for (int collisions = 0; collisions < 4; collisions++) {
        exist_opens = collisions;
        opens = 0;
        struct ironwood_string *created = ironwood_file_create_temp_file(stem, suffix, &string_type,
                &allocation_error);
        CHECK(created != NULL && opens == collisions + 1 && named(created, "t-", ".tmp"));
        struct stat status;
        char *value = string_to_utf8(created, NULL);
        CHECK(stat(value, &status) == 0 && S_ISREG(status.st_mode) && (status.st_mode & 07777) == 0600);
        test_free(value);
        CHECK(remove_created(created, 0) == 0 && descriptors == 0 && heap_live == baseline);
        exist_mkdirs = collisions;
        mkdirs = 0;
        created = ironwood_file_create_temp_directory(stem, &string_type, &allocation_error);
        CHECK(created != NULL && mkdirs == collisions + 1 && named(created, "t-", ""));
        value = string_to_utf8(created, NULL);
        CHECK(stat(value, &status) == 0 && S_ISDIR(status.st_mode) && (status.st_mode & 07777) == 0700);
        test_free(value);
        CHECK(remove_created(created, 1) == 0 && heap_live == baseline);
    }
    /* Trailing separators of the suffix are dropped, as Java drops them. */
    struct ironwood_string *created = ironwood_file_create_temp_file(stem, slashes, &string_type,
            &allocation_error);
    CHECK(created != NULL && named(created, "t-", ".s"));
    CHECK(remove_created(created, 0) == 0);
    /* Exhausted attempts report an existing name and create nothing. */
    exist_opens = IRONWOOD_TEMPORARY_ATTEMPTS;
    opens = 0;
    CHECK(ironwood_file_create_temp_file(stem, suffix, &string_type, &allocation_error) == NULL);
    CHECK(opens == IRONWOOD_TEMPORARY_ATTEMPTS && last_file_error == IRONWOOD_FILE_ERROR_ALREADY_EXISTS);
    exist_mkdirs = IRONWOOD_TEMPORARY_ATTEMPTS;
    CHECK(ironwood_file_create_temp_directory(stem, &string_type, &allocation_error) == NULL);
    CHECK(last_file_error == IRONWOOD_FILE_ERROR_ALREADY_EXISTS && entries() == 0);
    /* A failed close after creation removes the file and reports the error. */
    fail_close = 1;
    CHECK(ironwood_file_create_temp_file(stem, suffix, &string_type, &allocation_error) == NULL);
    fail_close = 0;
    CHECK(last_file_error == IRONWOOD_FILE_ERROR_OTHER && entries() == 0 && descriptors == 0);
    /* A failed String allocation after creation removes the entry first. */
    for (int directory = 0; directory < 2; directory++) {
        fail_calloc = 1;
        if (setjmp(failure_target) == 0) {
            if (directory) { ironwood_file_create_temp_directory(stem, &string_type, &allocation_error); }
            else { ironwood_file_create_temp_file(stem, suffix, &string_type, &allocation_error); }
            CHECK(0);
        }
        fail_calloc = 0;
        cleanup_exception(_URC_FOREIGN_EXCEPTION_CAUGHT, caught_unwind);
        ironwood_exception_caught(&allocation_error);
        CHECK(entries() == 0 && heap_live == baseline && descriptors == 0);
    }
    /* A stem beyond the stack buffer uses one reclaimed heap buffer. */
    struct ironwood_string *long_stem = allocate_string(5000, &string_type, NULL);
    for (int index = 0; index < 5000; index++) { long_stem->units[index] = index % 200 == 199 ? '/' : 'a'; }
    long_stem->utf8_length = 5000;
    baseline = heap_live;
    CHECK(ironwood_file_create_temp_file(long_stem, suffix, &string_type, &allocation_error) == NULL);
    /* ENOENT, or ENAMETOOLONG where PATH_MAX is shorter than the stem. */
    CHECK(last_file_error != IRONWOOD_FILE_ERROR_NONE && heap_live == baseline && entries() == 0);
    ironwood_deallocate(long_stem);
#if defined(__linux__)
    /* Without getrandom the urandom device supplies the name; without either
     * source the creation fails rather than using weaker bits. */
    random_unavailable = 1;
    created = ironwood_file_create_temp_file(stem, suffix, &string_type, &allocation_error);
    CHECK(created != NULL && named(created, "t-", ".tmp") && remove_created(created, 0) == 0);
    urandom_unavailable = 1;
    CHECK(ironwood_file_create_temp_file(stem, suffix, &string_type, &allocation_error) == NULL);
    CHECK(last_file_error == IRONWOOD_FILE_ERROR_NO_SUCH_FILE && entries() == 0 && descriptors == 0);
    random_unavailable = urandom_unavailable = 0;
#endif
    ironwood_deallocate(slashes);
    ironwood_deallocate(suffix);
    ironwood_deallocate(stem);
    return 0;
}

static int real_paths(void) {
    CHECK(mkdir("real", 0700) == 0 && symlink("real", "link") == 0);
    struct ironwood_string *link = text("link/../link");
    struct ironwood_string *empty = text("");
    struct ironwood_string *bad = text("bad\xef\xbf\xbd");
    char expected[PATH_MAX];
    CHECK(realpath("real", expected) != NULL);
    int baseline = heap_live;
    struct ironwood_string *resolved = ironwood_file_real_path(link, &string_type, &allocation_error);
    char *value = resolved == NULL ? NULL : string_to_utf8(resolved, NULL);
    CHECK(value != NULL && strcmp(value, expected) == 0);
    test_free(value);
    ironwood_deallocate(resolved);
    resolved = ironwood_file_real_path(empty, &string_type, &allocation_error);
    CHECK(resolved != NULL && resolved->utf16_length > 0);
    ironwood_deallocate(resolved);
    fail_calloc = 1;
    if (setjmp(failure_target) == 0) {
        ironwood_file_real_path(link, &string_type, &allocation_error);
        CHECK(0);
    }
    fail_calloc = 0;
    cleanup_exception(_URC_FOREIGN_EXCEPTION_CAUGHT, caught_unwind);
    ironwood_exception_caught(&allocation_error);
    CHECK(heap_live == baseline);
    CHECK(ironwood_file_access(link, 1, &allocation_error) == 1 && ironwood_file_access(link, 2, &allocation_error) == 1);
    CHECK(ironwood_file_access(bad, 1, &allocation_error) == 0 && heap_live == baseline);
    char invalid[] = {'b', 'a', 'd', (char) 0xff, 0};
    int descriptor = open(invalid, O_WRONLY | O_CREAT | O_EXCL, 0600);
#if defined(__APPLE__)
    /* APFS rejects names that are not UTF-8, so no resolved path can hold one. */
    CHECK(descriptor < 0 && errno == EILSEQ);
#else
    CHECK(descriptor >= 0 && close(descriptor) == 0);
    /* The UTF-8 encoding of U+FFFD does not name the 0xff file. */
    CHECK(ironwood_file_real_path(bad, &string_type, &allocation_error) == NULL);
    CHECK(last_file_error == IRONWOOD_FILE_ERROR_NO_SUCH_FILE);
    /* A resolved name that is not UTF-8 cannot become a String. */
    CHECK(symlink(invalid, "to-bad") == 0);
    struct ironwood_string *to_bad = text("to-bad");
    CHECK(ironwood_file_real_path(to_bad, &string_type, &allocation_error) == NULL);
    CHECK(last_file_error == IRONWOOD_FILE_ERROR_INVALID_UTF8 && heap_live == baseline);
    CHECK(ironwood_file_access(to_bad, 1, &allocation_error) == 1 && ironwood_file_access(to_bad, 2, &allocation_error) == 0);
    unlink("to-bad");
    unlink(invalid);
    ironwood_deallocate(to_bad);
#endif
    unlink("link");
    rmdir("real");
    ironwood_deallocate(bad);
    ironwood_deallocate(empty);
    ironwood_deallocate(link);
    return 0;
}

int main(int argc, char **argv) {
    CHECK(argc == 2 && chdir(argv[1]) == 0 && entries() == 0);
    CHECK(temporaries() == 0);
    CHECK(real_paths() == 0);
    CHECK(entries() == 0 && heap_live == 0 && descriptors == 0);
    return 0;
}
