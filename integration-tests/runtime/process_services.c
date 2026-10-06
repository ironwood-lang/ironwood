// SPDX-License-Identifier: MIT OR Apache-2.0

// This harness includes the runtime after system headers, so enable POSIX first.
#if defined(__linux__) && !defined(_POSIX_C_SOURCE)
#define _POSIX_C_SOURCE 200809L
#endif

/* M4.3 process launch under injected host behavior: failures opening the
 * output and standard input, creating the report pipe, forking and encoding
 * the command; waits interrupted by a real signal; exec and directory
 * failures reported after the fork; repeated launches that leave neither
 * descriptors nor unreaped children; and the recorded boundary that a
 * descriptor this process inherited without close-on-exec reaches the
 * program. Argument 1 is a scratch directory, argument 2 the helper. Only
 * Ironwood's translation unit is instrumented. */
#include <errno.h>
#include <fcntl.h>
#include <setjmp.h>
#include <signal.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <unwind.h>

static int fail_output, fail_input, fail_pipe, fail_fork, fail_malloc;
static jmp_buf failure_target;
static struct _Unwind_Exception *caught_unwind;
static volatile sig_atomic_t alarms;

static void *test_malloc(size_t size) {
    if (fail_malloc) { return NULL; }
    return malloc(size);
}
static int test_open(const char *path, int flags, ...) {
    int mode = 0;
    if ((flags & O_CREAT) != 0) {
        va_list arguments;
        va_start(arguments, flags);
        mode = va_arg(arguments, int);
        va_end(arguments);
    }
    if (fail_output && (flags & O_CREAT) != 0) { errno = EACCES; return -1; }
    if (fail_input && strcmp(path, "/dev/null") == 0) { errno = EMFILE; return -1; }
    return open(path, flags, mode);
}
static int test_pipe(int descriptors[2]) {
    if (fail_pipe) { errno = EMFILE; return -1; }
    return pipe(descriptors);
}
static pid_t test_fork(void) {
    if (fail_fork) { errno = EAGAIN; return -1; }
    return fork();
}
static _Unwind_Reason_Code test_unwind(struct _Unwind_Exception *exception) {
    caught_unwind = exception;
    longjmp(failure_target, 1);
}

#define malloc test_malloc
#define open test_open
#define pipe test_pipe
#define fork test_fork
#define _Unwind_RaiseException test_unwind
#include "../../runtime/src/ironwood_runtime.c"
#undef malloc
#undef open
#undef pipe
#undef fork
#undef _Unwind_RaiseException

#define CHECK(condition) do { if (!(condition)) { fprintf(stderr, "line %d\n", __LINE__); return 1; } } while (0)

static const struct ironwood_type_info string_type = { .name = "String" };
static const struct ironwood_type_info array_type = { .name = "String[]" };
static struct ironwood_throwable allocation_error;

static struct ironwood_string *text(const char *value) {
    return string_from_utf8_bytes((const unsigned char *) value, strlen(value), &string_type, NULL);
}

/* A String[] holding the given texts; the caller frees elements and array. */
static struct ironwood_array *command(int count, const char **values) {
    struct ironwood_array *array = ironwood_allocate_array(count, sizeof(void *), IRONWOOD_ARRAY_REFERENCE,
            &array_type, NULL);
    for (int index = 0; index < count; index++) {
        ((struct ironwood_string **) array->data)[index] = text(values[index]);
    }
    return array;
}

static void release(struct ironwood_array *array) {
    for (size_t index = 0; index < array->length; index++) {
        ironwood_deallocate(((struct ironwood_string **) array->data)[index]);
    }
    ironwood_deallocate(array);
}

static int open_descriptors(void) {
    int count = 0;
    for (int descriptor = 0; descriptor < 1024; descriptor++) {
        if (fcntl(descriptor, F_GETFD) >= 0) { count++; }
    }
    return count;
}

static int unreaped(void) {
    return waitpid(-1, NULL, WNOHANG) != -1 || errno != ECHILD;
}

static void interrupted(int signal_number) {
    (void) signal_number;
    alarms++;
}

int main(int argc, char **argv) {
    CHECK(argc == 3 && chdir(argv[1]) == 0);
    const char *helper = argv[2];
    struct ironwood_string *output = text("out.log");
    struct ironwood_string *missing = text("missing-dir");
    int baseline = open_descriptors();
    const char *exit_seven[] = {helper, "exit", "7"};
    struct ironwood_array *seven = command(3, exit_seven);
    CHECK(ironwood_process_run(seven, NULL, output, &allocation_error) == 7);
    CHECK(open_descriptors() == baseline && !unreaped());
    /* Failures before the fork close what was opened and start nothing. */
    fail_output = 1;
    CHECK(ironwood_process_run(seven, NULL, output, &allocation_error)
            == -(IRONWOOD_PROCESS_OUTPUT * 16 + IRONWOOD_PROCESS_PERMISSION));
    fail_output = 0;
    fail_input = 1;
    CHECK(ironwood_process_run(seven, NULL, output, &allocation_error)
            == -(IRONWOOD_PROCESS_INPUT * 16 + IRONWOOD_PROCESS_RESOURCE));
    fail_input = 0;
    fail_pipe = 1;
    CHECK(ironwood_process_run(seven, NULL, output, &allocation_error)
            == -(IRONWOOD_PROCESS_START * 16 + IRONWOOD_PROCESS_RESOURCE));
    fail_pipe = 0;
    fail_fork = 1;
    CHECK(ironwood_process_run(seven, NULL, output, &allocation_error)
            == -(IRONWOOD_PROCESS_START * 16 + IRONWOOD_PROCESS_RESOURCE));
    fail_fork = 0;
    CHECK(open_descriptors() == baseline && !unreaped());
    /* Encoding the command fails before any descriptor is opened. */
    fail_malloc = 1;
    if (setjmp(failure_target) == 0) {
        ironwood_process_run(seven, NULL, output, &allocation_error);
        CHECK(0);
    }
    fail_malloc = 0;
    cleanup_exception(_URC_FOREIGN_EXCEPTION_CAUGHT, caught_unwind);
    ironwood_exception_caught(&allocation_error);
    CHECK(open_descriptors() == baseline && !unreaped());
    /* Failures after the fork come back through the report pipe; the child is reaped. */
    const char *absent[] = {"/no/such/tool"};
    struct ironwood_array *missing_tool = command(1, absent);
    CHECK(ironwood_process_run(missing_tool, NULL, output, &allocation_error)
            == -(IRONWOOD_PROCESS_EXECUTE * 16 + IRONWOOD_PROCESS_NO_SUCH_FILE));
    CHECK(ironwood_process_run(seven, missing, output, &allocation_error)
            == -(IRONWOOD_PROCESS_DIRECTORY * 16 + IRONWOOD_PROCESS_NO_SUCH_FILE));
    CHECK(open_descriptors() == baseline && !unreaped());
    /* A signal handler without SA_RESTART interrupts the wait; it resumes. */
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = interrupted;
    sigemptyset(&action.sa_mask);
    CHECK(sigaction(SIGALRM, &action, NULL) == 0);
    struct itimerval timer = {{0, 20000}, {0, 20000}};
    CHECK(setitimer(ITIMER_REAL, &timer, NULL) == 0);
    const char *sleeper[] = {"/bin/sleep", "0.3"};
    struct ironwood_array *sleep_command = command(2, sleeper);
    CHECK(ironwood_process_run(sleep_command, NULL, output, &allocation_error) == 0);
    struct itimerval stop = {{0, 0}, {0, 0}};
    CHECK(setitimer(ITIMER_REAL, &stop, NULL) == 0 && alarms > 2);
    CHECK(open_descriptors() == baseline && !unreaped());
    /* Repeated launches accumulate neither descriptors nor children. */
    for (int round = 0; round < 200; round++) {
        CHECK(ironwood_process_run(seven, NULL, output, &allocation_error) == 7);
    }
    CHECK(open_descriptors() == baseline && !unreaped());
    /* Internal descriptors are close-on-exec; one this process inherited
     * without it reaches the program, as with posix_spawn's default. */
    const char *list_fds[] = {helper, "fds"};
    struct ironwood_array *fds = command(2, list_fds);
    int kept = open("kept.txt", O_WRONLY | O_CREAT | O_TRUNC, 0600);
    int hidden = open("hidden.txt", O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    CHECK(kept > 2 && hidden > 2);
    CHECK(ironwood_process_run(fds, NULL, output, &allocation_error) == 0);
    char listing[256] = {0};
    int descriptor = open("out.log", O_RDONLY);
    CHECK(descriptor >= 0 && read(descriptor, listing, sizeof(listing) - 1) > 0 && close(descriptor) == 0);
    char expected[64];
    snprintf(expected, sizeof(expected), "0\n1\n2\n%d\n", kept);
    CHECK(strcmp(listing, expected) == 0);
    CHECK(close(kept) == 0 && close(hidden) == 0);
    unlink("kept.txt");
    unlink("hidden.txt");
    unlink("out.log");
    release(fds);
    release(sleep_command);
    release(missing_tool);
    release(seven);
    ironwood_deallocate(missing);
    ironwood_deallocate(output);
    CHECK(open_descriptors() == baseline && !unreaped());
    return 0;
}
