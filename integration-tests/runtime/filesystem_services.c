// SPDX-License-Identifier: MIT OR Apache-2.0

// This harness includes the runtime after system headers, so enable POSIX first.
#if defined(__linux__) && !defined(_POSIX_C_SOURCE)
#define _POSIX_C_SOURCE 200809L
#endif

/* M4 native filesystem services under injected host behavior: temporary-name
 * collisions and exhaustion, a failed close after creation, a failed String
 * allocation after creation, long stems, the Linux random-source fallback,
 * real paths with invalid UTF-8 and access checks (M4.1); the check-then-
 * rename race of Files.move, competing creators, unsupported and
 * cross-device exclusive renames, and every failure point of the replacing
 * move's cross-device copy (M4.2). Only Ironwood's translation unit is
 * instrumented, not libc. */
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
extern long syscall(long number, ...);
#endif

static int heap_live, descriptors;
static int fail_calloc, exist_opens, exist_mkdirs, fail_close, opens, mkdirs;
/* M4.2: injected rename results, a competitor created between a check and a
 * rename, and failures at each step of the cross-device copy. */
static int rename_error, exclusive_error, fail_write, fail_unlink_source, fail_second_rename, renames;
static const char *compete_path;
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
    if (number == SYS_renameat2) {
        if (exclusive_error != 0) { errno = exclusive_error; return -1; }
        va_start(arguments, number);
        int source_directory = va_arg(arguments, int);
        const char *source = va_arg(arguments, const char *);
        int target_directory = va_arg(arguments, int);
        const char *target = va_arg(arguments, const char *);
        unsigned rename_flags = va_arg(arguments, unsigned);
        va_end(arguments);
        return syscall(number, source_directory, source, target_directory, target, rename_flags);
    }
    return syscall(number, buffer, length, flags);
}
#endif
static int test_lstat(const char *path, struct stat *status) {
    int result = lstat(path, status);
    if (result != 0 && compete_path != NULL && strcmp(path, compete_path) == 0) {
        /* A competing creator wins the window between the check and the rename. */
        int descriptor = open(path, O_WRONLY | O_CREAT | O_EXCL, 0600);
        if (descriptor >= 0) {
            write(descriptor, "competitor", 10);
            close(descriptor);
        }
        compete_path = NULL;
        errno = ENOENT;
    }
    return result;
}
static int test_rename(const char *source, const char *target) {
    renames++;
    if (rename_error != 0 && renames == 1) { errno = rename_error; return -1; }
    if (fail_second_rename && renames == 2) { errno = EIO; return -1; }
    return rename(source, target);
}
static ssize_t test_write(int descriptor, const void *data, size_t length) {
    if (fail_write) { errno = EIO; return -1; }
    return write(descriptor, data, length);
}
static int test_unlink(const char *path) {
    if (fail_unlink_source && strstr(path, ".ironwood-move-") == NULL) { errno = EACCES; return -1; }
    return unlink(path);
}
#if defined(__APPLE__)
static int test_renamex_np(const char *source, const char *target, unsigned int flags) {
    if (exclusive_error != 0) { errno = exclusive_error; return -1; }
    return renamex_np(source, target, flags);
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
#define lstat test_lstat
#define rename test_rename
#define write test_write
#define unlink test_unlink
#if defined(__APPLE__)
#define renamex_np test_renamex_np
#endif
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
#undef lstat
#undef rename
#undef write
#undef unlink
#if defined(__APPLE__)
#undef renamex_np
#endif
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
    int strings = heap_live;
    CHECK(ironwood_file_real_path(to_bad, &string_type, &allocation_error) == NULL);
    CHECK(last_file_error == IRONWOOD_FILE_ERROR_INVALID_UTF8 && heap_live == strings);
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

static int put(const char *path, const char *content) {
    int descriptor = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (descriptor < 0) { return -1; }
    ssize_t written = write(descriptor, content, strlen(content));
    return close(descriptor) == 0 && written == (ssize_t) strlen(content) ? 0 : -1;
}

static int holds(const char *path, const char *content) {
    char buffer[64];
    int descriptor = open(path, O_RDONLY);
    if (descriptor < 0) { return 0; }
    ssize_t count = read(descriptor, buffer, sizeof(buffer) - 1);
    close(descriptor);
    if (count < 0) { return 0; }
    buffer[count] = '\0';
    return strcmp(buffer, content) == 0;
}

static int exists(const char *path) {
    struct stat status;
    return lstat(path, &status) == 0;
}

static int moves(void) {
    struct ironwood_string *source = text("source");
    struct ironwood_string *target = text("target");
    struct ironwood_string *nested = text("dir/sub");
    struct ironwood_string *directory = text("dir");
    int baseline = heap_live;
    /* Files.move checks, then renames: a creator in that window is replaced
     * silently, which is why publication needs the exclusive rename. */
    CHECK(put("source", "new") == 0);
    compete_path = "target";
    CHECK(ironwood_file_move(source, target, &allocation_error) == IRONWOOD_FILE_ERROR_NONE);
    CHECK(compete_path == NULL && holds("target", "new") && !exists("source"));
    CHECK(unlink("target") == 0);
    /* The exclusive rename keeps a competing file, directory or dangling link. */
    for (int competitor = 0; competitor < 3; competitor++) {
        CHECK(put("source", "new") == 0);
        if (competitor == 0) { CHECK(put("target", "competitor") == 0); }
        else if (competitor == 1) { CHECK(mkdir("target", 0700) == 0); }
        else { CHECK(symlink("missing", "target") == 0); }
        CHECK(ironwood_file_move_exclusive(source, target, &allocation_error) == IRONWOOD_FILE_ERROR_ALREADY_EXISTS);
        CHECK(holds("source", "new") && exists("target"));
        if (competitor == 0) { CHECK(holds("target", "competitor") && unlink("target") == 0); }
        else if (competitor == 1) { CHECK(rmdir("target") == 0); }
        else { CHECK(unlink("target") == 0); }
    }
    CHECK(ironwood_file_move_exclusive(source, target, &allocation_error) == IRONWOOD_FILE_ERROR_NONE);
    CHECK(holds("target", "new") && !exists("source"));
    /* A name renamed to itself is an existing target on every host. */
    CHECK(ironwood_file_move_exclusive(target, target, &allocation_error) == IRONWOOD_FILE_ERROR_ALREADY_EXISTS);
    CHECK(holds("target", "new") && unlink("target") == 0);
    /* An unsupported host or file system, or another device, changes nothing. */
    CHECK(put("source", "new") == 0);
    int unsupported[] = {ENOTSUP, EXDEV
#if defined(__linux__)
            , ENOSYS, EINVAL
#endif
    };
    for (size_t index = 0; index < sizeof(unsupported) / sizeof(int); index++) {
        exclusive_error = unsupported[index];
        int32_t result = ironwood_file_move_exclusive(source, target, &allocation_error);
        CHECK(result == (unsupported[index] == EXDEV ? IRONWOOD_FILE_ERROR_CROSS_DEVICE
                : IRONWOOD_FILE_ERROR_UNSUPPORTED));
        CHECK(holds("source", "new") && !exists("target"));
    }
    exclusive_error = 0;
    /* A directory moved inside itself stays an invalid move, not unsupported. */
    CHECK(mkdir("dir", 0700) == 0);
    CHECK(ironwood_file_move_exclusive(directory, nested, &allocation_error) == IRONWOOD_FILE_ERROR_OTHER);
    CHECK(rmdir("dir") == 0);
    /* Atomic replacement: another device fails without effect; a non-empty
     * directory reported as EEXIST is normalized to ENOTEMPTY. */
    CHECK(put("target", "old") == 0);
    renames = 0;
    rename_error = EXDEV;
    CHECK(ironwood_file_move_atomic(source, target, &allocation_error) == IRONWOOD_FILE_ERROR_CROSS_DEVICE);
    CHECK(holds("source", "new") && holds("target", "old"));
    renames = 0;
    rename_error = EEXIST;
    CHECK(ironwood_file_move_atomic(source, target, &allocation_error) == IRONWOOD_FILE_ERROR_DIRECTORY_NOT_EMPTY);
    /* The replacing move copies only a regular file across devices. */
    struct stat before, after;
    CHECK(chmod("source", 0751) == 0 && stat("source", &before) == 0);
    for (int fault = 0; fault < 4; fault++) {
        renames = 0;
        rename_error = EXDEV;
        fail_write = fault == 1;
        fail_second_rename = fault == 2;
        fail_unlink_source = fault == 3;
        int32_t result = ironwood_file_move_replacing(source, target, &allocation_error);
        fail_write = fail_second_rename = fail_unlink_source = 0;
        if (fault == 0) {
            CHECK(result == IRONWOOD_FILE_ERROR_NONE && holds("target", "new") && !exists("source"));
            CHECK(stat("target", &after) == 0 && (after.st_mode & 07777) == 0751);
#if defined(__APPLE__)
            CHECK(after.st_mtimespec.tv_sec == before.st_mtimespec.tv_sec
                    && after.st_mtimespec.tv_nsec == before.st_mtimespec.tv_nsec);
#else
            CHECK(after.st_mtim.tv_sec == before.st_mtim.tv_sec && after.st_mtim.tv_nsec == before.st_mtim.tv_nsec);
#endif
            CHECK(put("source", "new") == 0 && put("target", "old") == 0);
        } else if (fault < 3) {
            /* A failure before the replacement keeps both entries and no temporary. */
            CHECK(result == IRONWOOD_FILE_ERROR_OTHER && holds("source", "new") && holds("target", "old"));
        } else {
            /* The target was replaced; the source that remains is reported, not rolled back. */
            CHECK(result == IRONWOOD_FILE_ERROR_SOURCE_RETAINED && holds("source", "new") && holds("target", "new"));
            CHECK(put("target", "old") == 0);
        }
        CHECK(entries() == 2 && descriptors == 0 && heap_live == baseline);
    }
    /* A directory or link across devices has no fallback and stays unchanged. */
    CHECK(unlink("source") == 0 && mkdir("source", 0700) == 0);
    renames = 0;
    rename_error = EXDEV;
    CHECK(ironwood_file_move_replacing(source, target, &allocation_error) == IRONWOOD_FILE_ERROR_CROSS_DEVICE);
    CHECK(rmdir("source") == 0 && symlink("target", "source") == 0);
    renames = 0;
    CHECK(ironwood_file_move_replacing(source, target, &allocation_error) == IRONWOOD_FILE_ERROR_CROSS_DEVICE);
    rename_error = 0;
    CHECK(unlink("source") == 0 && holds("target", "old") && unlink("target") == 0);
    CHECK(entries() == 0 && descriptors == 0 && heap_live == baseline);
    ironwood_deallocate(directory);
    ironwood_deallocate(nested);
    ironwood_deallocate(target);
    ironwood_deallocate(source);
    return 0;
}

/* Argument 1 is an empty scratch directory; argument 2 selects "temporary"
 * (M4.1) or "publication" (M4.2), and both run without it. */
int main(int argc, char **argv) {
    CHECK((argc == 2 || argc == 3) && chdir(argv[1]) == 0 && entries() == 0);
    _Bool all = argc == 2;
    if (all || strcmp(argv[2], "temporary") == 0) {
        CHECK(temporaries() == 0);
        CHECK(real_paths() == 0);
    }
    if (all || strcmp(argv[2], "publication") == 0) { CHECK(moves() == 0); }
    CHECK(entries() == 0 && heap_live == 0 && descriptors == 0);
    return 0;
}
