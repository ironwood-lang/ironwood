// SPDX-License-Identifier: MIT OR Apache-2.0

/* macOS diagnostic-only interposition. No counters or injection enter the
 * shipped runtime. ASCII @tcp:NAME lines delimit individual operations. */
#include <errno.h>
#include <dlfcn.h>
#include <execinfo.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#if !defined(__APPLE__)
#error This focused diagnostic fixture uses macOS dyld interposition
#endif

#define INTERPOSE(replacement, original) \
    __attribute__((used)) static struct { const void *replacement; const void *original; } \
    pair_##original __attribute__((section("__DATA,__interpose"))) = { \
        (const void *) (uintptr_t) &replacement, (const void *) (uintptr_t) &original }

struct counts {
    long receive, send, poll, clock, control, options, available, allocate, bytes_in, bytes_out;
};
static struct counts counts;
static char phase[96], line[128];
static size_t line_size;
static int active, injected;
static long sockets_opened, sockets_closed;
static unsigned char descriptors[4096];
// Track only allocations made inside a named diagnostic window. Frees after
// the window still discharge them. This fixed test table adds no shipped state.
static struct { void *pointer; size_t size; void *caller; } heap[8192];

static void remember(void *pointer, size_t size, void *caller) {
    if (pointer == NULL || !active) return;
    // Darwin's typed allocation wrappers can re-enter an interposed allocator
    // for the same result. Count that live block once, at the outer call site.
    for (size_t index = 0; index < sizeof(heap) / sizeof(heap[0]); index++) {
        if (heap[index].pointer == pointer) {
            heap[index].caller = caller;
            heap[index].size = size;
            return;
        }
    }
    for (size_t index = 0; index < sizeof(heap) / sizeof(heap[0]); index++) {
        if (heap[index].pointer == NULL) {
            heap[index].pointer = pointer;
            heap[index].size = size;
            heap[index].caller = caller;
            if (size == 64 && getenv("IRONWOOD_TEST_HEAP_STACK") != NULL) {
                // Optional attribution run only; exclude profiler work from counts.
                active = 0;
                void *frames[16];
                int depth = backtrace(frames, 16);
                backtrace_symbols_fd(frames, depth, STDERR_FILENO);
                active = 1;
            }
            return;
        }
    }
    abort();
}

static int forget(void *pointer) {
    if (pointer == NULL) return 0;
    for (size_t index = 0; index < sizeof(heap) / sizeof(heap[0]); index++) {
        if (heap[index].pointer == pointer) {
            heap[index].pointer = NULL;
            return 1;
        }
    }
    return 0;
}

static void report(void) {
    if (!active) return;
    active = 0;
    fprintf(stderr, "TCP_COUNT %s recv=%ld send=%ld poll=%ld clock=%ld control=%ld options=%ld available=%ld allocate=%ld in=%ld out=%ld\n",
            phase, counts.receive, counts.send, counts.poll, counts.clock, counts.control,
            counts.options, counts.available, counts.allocate, counts.bytes_in, counts.bytes_out);
}

static int test_putc(int value, FILE *stream) {
    if (stream == stdout) {
        if (value == '@' && line_size == 0) report();
        if (value == '\n') {
            line[line_size] = 0;
            if (strncmp(line, "@tcp:", 5) == 0 && strcmp(line + 5, "end") != 0) {
                snprintf(phase, sizeof(phase), "%s", line + 5);
                memset(&counts, 0, sizeof(counts));
                injected = 0;
                active = 1;
            }
            line_size = 0;
        } else if (line_size + 1 < sizeof(line)) {
            line[line_size++] = (char) value;
        }
    }
    return fputc(value, stream);
}
INTERPOSE(test_putc, fputc);

static ssize_t test_recv(int fd, void *buffer, size_t length, int flags) {
    if (active) {
        counts.receive++;
        if (strcmp(phase, "read_eintr") == 0 && injected++ == 0) { errno = EINTR; return -1; }
        if (strcmp(phase, "read_would_block") == 0 && injected++ == 0) { errno = EAGAIN; return -1; }
        if (strcmp(phase, "read_deadline") == 0) { errno = EAGAIN; return -1; }
        if (strcmp(phase, "short_read") == 0 && length > 2) length = 2;
        if (strcmp(phase, "peer_reset") == 0) { errno = ECONNRESET; return -1; }
    }
    ssize_t result = recv(fd, buffer, length, flags);
    if (active && result > 0) counts.bytes_in += result;
    return result;
}
INTERPOSE(test_recv, recv);

static ssize_t test_send(int fd, const void *buffer, size_t length, int flags) {
    if (active) {
        counts.send++;
        if (strcmp(phase, "write_eintr") == 0 && injected++ == 0) { errno = EINTR; return -1; }
        if (strcmp(phase, "write_partial") == 0 && length > 2) length = 2;
        if (strcmp(phase, "write_would_block") == 0 && injected++ == 0) { errno = EAGAIN; return -1; }
    }
    ssize_t result = send(fd, buffer, length, flags);
    if (active && result > 0) counts.bytes_out += result;
    return result;
}
INTERPOSE(test_send, send);

static int test_poll(struct pollfd *fds, nfds_t count, int timeout) {
    if (active) {
        counts.poll++;
        if (strcmp(phase, "read_deadline") == 0 || strcmp(phase, "accept_deadline") == 0
                || strcmp(phase, "connect_deadline") == 0) {
            struct timespec delay = {0, 3000000};
            nanosleep(&delay, NULL);
            if ((injected++ % 2) == 0) { errno = EINTR; return -1; }
            return 1; /* Spurious readiness must not restart a deadline. */
        }
    }
    return poll(fds, count, timeout);
}
INTERPOSE(test_poll, poll);

static int test_clock(clockid_t id, struct timespec *value) {
    if (active) counts.clock++;
    return clock_gettime(id, value);
}
INTERPOSE(test_clock, clock_gettime);

static int test_fcntl(int fd, int command, ...) {
    if (active) counts.control++;
    if (command == F_GETFD || command == F_GETFL) return fcntl(fd, command);
    va_list values;
    va_start(values, command);
    if (command == F_GETPATH) {
        char *path = va_arg(values, char *);
        va_end(values);
        return fcntl(fd, command, path);
    }
    if (command != F_SETFD && command != F_SETFL && command != F_SETNOSIGPIPE) {
        fprintf(stderr, "unsupported diagnostic fcntl %d\n", command);
        abort();
    }
    int argument = va_arg(values, int);
    va_end(values);
    return fcntl(fd, command, argument);
}
INTERPOSE(test_fcntl, fcntl);

static int test_get_option(int fd, int level, int option, void *value, socklen_t *length) {
    if (active) {
        counts.options++;
        if (strcmp(phase, "connect_deadline") == 0 && level == SOL_SOCKET && option == SO_ERROR) {
            *(int *) value = EINPROGRESS;
            *length = sizeof(int);
            return 0;
        }
    }
    return getsockopt(fd, level, option, value, length);
}
INTERPOSE(test_get_option, getsockopt);

static int test_set_option(int fd, int level, int option, const void *value, socklen_t length) {
    if (active) counts.options++;
    return setsockopt(fd, level, option, value, length);
}
INTERPOSE(test_set_option, setsockopt);

static int test_ioctl(int fd, unsigned long request, ...) {
    if (active) counts.available++;
    va_list values;
    va_start(values, request);
    void *argument = va_arg(values, void *);
    va_end(values);
    return ioctl(fd, request, argument);
}
INTERPOSE(test_ioctl, ioctl);

static int test_local_endpoint(int fd, struct sockaddr *address, socklen_t *length) {
    if (active && strcmp(phase, "after_accept_failure") == 0) { errno = ECONNRESET; return -1; }
    return getsockname(fd, address, length);
}
INTERPOSE(test_local_endpoint, getsockname);

static void *test_malloc(size_t size) {
    if (active) counts.allocate++;
    void *result = malloc(size);
    remember(result, size, __builtin_return_address(0));
    return result;
}
INTERPOSE(test_malloc, malloc);

static void *test_calloc(size_t count, size_t size) {
    if (active) counts.allocate++;
    if (active && strcmp(phase, "wrapper_failure") == 0) {
        const char *failure = getenv("IRONWOOD_TEST_CALLOC_FAILURE");
        if (failure != NULL && injected++ == atoi(failure)) { errno = ENOMEM; return NULL; }
    }
    void *result = calloc(count, size);
    remember(result, count * size, __builtin_return_address(0));
    return result;
}
INTERPOSE(test_calloc, calloc);

static void *test_realloc(void *memory, size_t size) {
    if (active) counts.allocate++;
    void *result = realloc(memory, size);
    if (result != NULL) {
        int tracked = forget(memory);
        int was_active = active;
        if (tracked) active = 1;
        remember(result, size, __builtin_return_address(0));
        active = was_active;
    }
    return result;
}
INTERPOSE(test_realloc, realloc);

static void test_free(void *memory) {
    forget(memory);
    free(memory);
}
INTERPOSE(test_free, free);

static void acquired(int fd) {
    if (fd >= 0 && fd < (int) sizeof(descriptors)) {
        if (descriptors[fd] == 1) abort();
        descriptors[fd] = 1;
        sockets_opened++;
        if (getenv("IRONWOOD_TEST_FD_LOG") != NULL) fprintf(stderr, "TCP_FD acquire %d %s\n", fd, phase);
    }
}

static int test_socket(int family, int type, int protocol) {
    const char *fallback = getenv("IRONWOOD_TEST_IPV4_ONLY");
    if (family == AF_INET6 && fallback != NULL) { errno = EAFNOSUPPORT; return -1; }
    int fd = socket(family, type, protocol);
    if (getenv("IRONWOOD_TEST_FD_LOG") != NULL) fprintf(stderr, "TCP_SOCKET fd=%d family=%d type=%d protocol=%d\n", fd, family, type, protocol);
    // macOS may open a process-lifetime logging datagram socket before main.
    // Attribute only the internet stream descriptors owned by this fixture.
    if ((family == AF_INET || family == AF_INET6) && type == SOCK_STREAM) acquired(fd);
    return fd;
}
INTERPOSE(test_socket, socket);

static int test_accept(int fd, struct sockaddr *address, socklen_t *length) {
    if (active && strcmp(phase, "accept_deadline") == 0) { errno = EAGAIN; return -1; }
    int accepted = accept(fd, address, length);
    if (fd >= 0 && fd < (int) sizeof(descriptors) && descriptors[fd] == 1) acquired(accepted);
    return accepted;
}
INTERPOSE(test_accept, accept);

static int test_connect(int fd, const struct sockaddr *address, socklen_t length) {
    if (active && strcmp(phase, "connect_deadline") == 0) { errno = EINPROGRESS; return -1; }
    return connect(fd, address, length);
}
INTERPOSE(test_connect, connect);

static int test_close(int fd) {
    int injected_failure = active && strcmp(phase, "after_accept_failure") == 0;
    int already_closed = fd >= 0 && fd < (int) sizeof(descriptors) && descriptors[fd] == 2;
    if (fd >= 0 && fd < (int) sizeof(descriptors) && descriptors[fd] == 1) {
        descriptors[fd] = 2;
        sockets_closed++;
        if (getenv("IRONWOOD_TEST_FD_LOG") != NULL) fprintf(stderr, "TCP_FD close %d %s\n", fd, phase);
    }
    int result = close(fd);
    if (already_closed && result < 0 && errno == EBADF) abort();
    if (injected_failure) { errno = EIO; return -1; }
    return result;
}
INTERPOSE(test_close, close);

__attribute__((destructor)) static void finish(void) {
    report();
    fprintf(stderr, "TCP_FDS opened=%ld closed=%ld\n", sockets_opened, sockets_closed);
    size_t retained = 0, bytes = 0;
    for (size_t index = 0; index < sizeof(heap) / sizeof(heap[0]); index++) {
        if (heap[index].pointer != NULL) {
            retained++; bytes += heap[index].size;
            if (getenv("IRONWOOD_TEST_HEAP_LOG") != NULL) {
                Dl_info info = {0};
                dladdr(heap[index].caller, &info);
                fprintf(stderr, "TCP_HEAP_SITE size=%zu image=%s symbol=%s offset=%zu\n",
                        heap[index].size, info.dli_fname == NULL ? "?" : info.dli_fname,
                        info.dli_sname == NULL ? "?" : info.dli_sname,
                        (size_t) ((uintptr_t) heap[index].caller - (uintptr_t) info.dli_saddr));
            }
        }
    }
    fprintf(stderr, "TCP_HEAP retained=%zu bytes=%zu\n", retained, bytes);
}
