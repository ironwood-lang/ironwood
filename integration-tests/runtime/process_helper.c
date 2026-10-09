// SPDX-License-Identifier: MIT OR Apache-2.0

#if defined(__linux__) && !defined(_POSIX_C_SOURCE)
#define _POSIX_C_SOURCE 200809L
#endif

/* A controlled program for the M4.3 process-runner tests. The first argument
 * selects the behavior:
 *   exit N         exit with status N
 *   signal N       end by raising signal N
 *   argv ...       print each further argument as [text] on its own line
 *   cwd            print the working directory
 *   env NAME       print the variable's value, or <unset>
 *   stdin          print how many bytes standard input holds before end of file
 *   large N        write N bytes to standard output and N to standard error,
 *                  interleaved in 4096-byte chunks
 *   fds            print every open descriptor below 1024 except those the
 *                  Rosetta translator holds
 *   sleep-pid FILE write this process's id to FILE, then sleep 30 seconds
 *   group PROGRAM ...  become a new process group's leader with default
 *                  interrupt dispositions, as a shell's foreground job is,
 *                  and execute PROGRAM with the rest */
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/* Whether a descriptor names Rosetta's runtime. Linux under Rosetta, as in the
 * linux-x86_64 platform VM, opens in every translated process the executable it
 * translates and then its runtime, both without close-on-exec, so a translated
 * program inherits its parent's pair and adds its own; native hosts have none. */
static int rosetta_runtime(int descriptor) {
#if defined(__linux__)
    char link[64];
    char target[4096];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", descriptor);
    ssize_t length = readlink(link, target, sizeof(target) - 1);
    if (length <= 0) { return 0; }
    target[length] = '\0';
    const char *name = strrchr(target, '/');
    return name != NULL && strcmp(name, "/rosetta") == 0;
#else
    (void) descriptor;
    return 0;
#endif
}

/* Whether the translator holds a descriptor: its runtime, or the executable it
 * opened just before it. */
static int translator_descriptor(int descriptor) {
    return rosetta_runtime(descriptor) || rosetta_runtime(descriptor + 1);
}

int main(int argc, char **argv) {
    if (argc < 2) { return 2; }
    const char *mode = argv[1];
    if (strcmp(mode, "exit") == 0 && argc == 3) { return atoi(argv[2]); }
    if (strcmp(mode, "signal") == 0 && argc == 3) {
        signal(atoi(argv[2]), SIG_DFL);
        raise(atoi(argv[2]));
        return 3;
    }
    if (strcmp(mode, "argv") == 0) {
        for (int index = 2; index < argc; index++) { printf("[%s]\n", argv[index]); }
        return 0;
    }
    if (strcmp(mode, "cwd") == 0) {
        char directory[4096];
        if (getcwd(directory, sizeof(directory)) == NULL) { return 4; }
        printf("%s\n", directory);
        return 0;
    }
    if (strcmp(mode, "env") == 0 && argc == 3) {
        const char *value = getenv(argv[2]);
        printf("%s\n", value == NULL ? "<unset>" : value);
        return 0;
    }
    if (strcmp(mode, "stdin") == 0) {
        char buffer[256];
        long total = 0;
        ssize_t count;
        while ((count = read(0, buffer, sizeof(buffer))) > 0) { total += count; }
        printf("%ld\n", count < 0 ? -1L : total);
        return 0;
    }
    if (strcmp(mode, "large") == 0 && argc == 3) {
        long size = atol(argv[2]);
        char chunk[4096];
        for (long written = 0; written < size; written += (long) sizeof(chunk)) {
            size_t length = size - written < (long) sizeof(chunk) ? (size_t) (size - written) : sizeof(chunk);
            memset(chunk, 'o', length);
            if (write(1, chunk, length) != (ssize_t) length) { return 5; }
            memset(chunk, 'e', length);
            if (write(2, chunk, length) != (ssize_t) length) { return 6; }
        }
        return 0;
    }
    if (strcmp(mode, "fds") == 0) {
        for (int descriptor = 0; descriptor < 1024; descriptor++) {
            if (fcntl(descriptor, F_GETFD) >= 0 && !translator_descriptor(descriptor)) {
                printf("%d\n", descriptor);
            }
        }
        return 0;
    }
    if (strcmp(mode, "sleep-pid") == 0 && argc == 3) {
        FILE *file = fopen(argv[2], "w");
        if (file == NULL) { return 7; }
        fprintf(file, "%ld\n", (long) getpid());
        fclose(file);
        struct timespec delay = {30, 0};
        while (nanosleep(&delay, &delay) != 0 && errno == EINTR) { }
        return 0;
    }
    if (strcmp(mode, "group") == 0 && argc >= 3) {
        /* A shell's foreground job starts with default dispositions even when
         * the shell itself was started with interrupts ignored. */
        signal(SIGINT, SIG_DFL);
        signal(SIGQUIT, SIG_DFL);
        if (setpgid(0, 0) != 0) { return 8; }
        execv(argv[2], argv + 2);
        return 9;
    }
    return 2;
}
