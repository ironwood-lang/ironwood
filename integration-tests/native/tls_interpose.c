// SPDX-License-Identifier: MIT OR Apache-2.0

/* Test-only syscall observations and faults around the shipped executable.
 * Descriptor accounting covers the whole process; named windows isolate setup
 * from steady-state transfer. No instrumentation enters distribution sources. */
#define _GNU_SOURCE 1
#include <assert.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#if defined(__APPLE__)
#define INTERPOSE(replacement, original) \
    __attribute__((used)) static struct { const void *replacement; const void *original; } \
    pair_##original __attribute__((section("__DATA,__interpose"))) = { \
        (const void *) (uintptr_t) &replacement, (const void *) (uintptr_t) &original }
#define REAL(name, type) name
#else
#define INTERPOSE(replacement, original)
#define test_socket socket
#define test_connect connect
#define test_close close
#define test_recv recv
#define test_send send
#define test_poll poll
#define test_fcntl fcntl
#define test_clock_gettime clock_gettime
#define test_fputc fputc
#define test_getaddrinfo getaddrinfo
#define test_malloc malloc
#define test_calloc calloc
#define test_realloc realloc
#define test_free free
#define test_write write
#define REAL(name, type) ((type) dlsym(RTLD_NEXT, #name))
#endif

static char phase[32], line[64];
static size_t used;
static int active, injected, restores, opened, closed;
static unsigned char descriptors[4096];
static unsigned char received[4096], written[4096];
static long receives, sends, peeks, polls, clocks, controls, received_bytes, sent_bytes;
static long file_writes;
static int64_t fake_clock;
static long allocations, heap_balance, cycle_start;

/* Test-only table counts observed malloc-family blocks. libc may allocate
 * internally without interposition and later call public free; ignore those
 * unobserved pointers rather than reporting a spurious negative balance. */
static void *blocks[65536];
static void remember(void *pointer) {
    if (!pointer) return;
    size_t slot = ((uintptr_t)pointer >> 4) & 65535;
    while (blocks[slot] && blocks[slot] != (void *)1) slot = (slot + 1) & 65535;
    blocks[slot] = pointer;
    heap_balance++;
}
static void forget(void *pointer) {
    if (!pointer) return;
    size_t slot = ((uintptr_t)pointer >> 4) & 65535;
    while (blocks[slot]) {
        if (blocks[slot] == pointer) { blocks[slot] = (void *)1; heap_balance--; return; }
        slot = (slot + 1) & 65535;
    }
}
#if !defined(__APPLE__)
extern void *__libc_malloc(size_t);
extern void *__libc_calloc(size_t, size_t);
extern void *__libc_realloc(void *, size_t);
extern void __libc_free(void *);
#endif
void *test_malloc(size_t size) {
    if (active) allocations++;
#if defined(__APPLE__)
    void *result = malloc(size);
#else
    void *result = __libc_malloc(size);
#endif
    remember(result);
    return result;
}
void *test_calloc(size_t count, size_t size) {
    if (active) allocations++;
#if defined(__APPLE__)
    void *result = calloc(count, size);
#else
    void *result = __libc_calloc(count, size);
#endif
    remember(result);
    return result;
}
void *test_realloc(void *pointer, size_t size) {
    if (active) allocations++;
#if defined(__APPLE__)
    void *result = realloc(pointer, size);
#else
    void *result = __libc_realloc(pointer, size);
#endif
    if (result || !size) { forget(pointer); remember(result); }
    return result;
}
void test_free(void *pointer) {
    forget(pointer);
#if defined(__APPLE__)
    free(pointer);
#else
    __libc_free(pointer);
#endif
}
static int mode(const char *name) {
    const char *selected = getenv("IRONWOOD_TEST_TLS_MODE");
    return active && !strcmp(phase, "steady") && selected && !strcmp(selected, name);
}
static void report(void) {
    if (!active) return;
    active = 0;
    fprintf(stderr, "TLS_COUNT %s recv=%ld send=%ld peek=%ld poll=%ld clock=%ld control=%ld alloc=%ld in=%ld out=%ld filewrite=%ld\n",
            phase, receives, sends, peeks, polls, clocks, controls, allocations, received_bytes, sent_bytes, file_writes);
}
int test_fputc(int value, FILE *stream) {
    if (stream == stdout) {
        if (value == '@' && used == 0) report();
        if (value == '\n') {
            line[used] = 0;
            if (!strcmp(line, "@tls:setup")) cycle_start = heap_balance;
            if (!strcmp(line, "@tls:end")) fprintf(stderr, "TLS_HEAP retained=%ld\n", heap_balance - cycle_start);
            if (!strncmp(line, "@tls:", 5) && strcmp(line + 5, "end")) {
                snprintf(phase, sizeof(phase), "%.31s", line + 5);
                receives = sends = peeks = polls = clocks = controls = received_bytes = sent_bytes = 0;
                file_writes = 0;
                allocations = 0;
                injected = restores = 0;
                fake_clock = 0;
                active = 1;
            }
            used = 0;
        } else if (used + 1 < sizeof(line)) line[used++] = (char) value;
    }
    return REAL(fputc, int (*)(int, FILE *))(value, stream);
}
int test_socket(int family, int type, int protocol) {
    int descriptor = REAL(socket, int (*)(int, int, int))(family, type, protocol);
    if (descriptor >= 0 && (family == AF_INET || family == AF_INET6) && type == SOCK_STREAM) {
        assert(descriptor < (int) sizeof(descriptors) && !descriptors[descriptor]);
        descriptors[descriptor] = 1;
        opened++;
    }
    return descriptor;
}
int test_connect(int descriptor, const struct sockaddr *address, socklen_t length) {
    assert(!getenv("IRONWOOD_TEST_FORBID_CONNECT") || (address->sa_family != AF_INET && address->sa_family != AF_INET6));
    return REAL(connect, int (*)(int, const struct sockaddr *, socklen_t))(descriptor, address, length);
}
int test_close(int descriptor) {
    int tracked = descriptor >= 0 && descriptor < (int) sizeof(descriptors) && descriptors[descriptor];
    if (tracked) { descriptors[descriptor] = 0; closed++; }
    int result = REAL(close, int (*)(int))(descriptor);
    const char *wget_fault = getenv("IRONWOOD_TEST_WGET_FAULT");
    if (wget_fault && descriptor >= 3 && descriptor < 4096) {
        int fail = (!strcmp(wget_fault, "socket-close") && tracked && received[descriptor])
                || (!strcmp(wget_fault, "output-close") && written[descriptor]);
        received[descriptor] = written[descriptor] = 0;
        if (fail) { errno = EIO; return -1; }
    }
    if (tracked && mode("read-error")) { errno = EIO; return -1; }
    return result;
}
ssize_t test_recv(int descriptor, void *buffer, size_t length, int flags) {
    if (active) { receives++; if (flags & MSG_PEEK) peeks++; }
    if (mode("read-error")) { errno = ECONNRESET; return -1; }
    if (mode("read-eintr") && injected++ == 0) { errno = EINTR; return -1; }
    if (mode("read-would-block") && injected++ == 0) { errno = EAGAIN; return -1; }
    if (mode("short-read") && length > 2) length = 2;
    ssize_t result = REAL(recv, ssize_t (*)(int, void *, size_t, int))(descriptor, buffer, length, flags);
    if (result > 0 && descriptor >= 0 && descriptor < 4096) received[descriptor] = 1;
    if (active && result > 0 && !(flags & MSG_PEEK)) received_bytes += result;
    return result;
}
ssize_t test_write(int descriptor, const void *buffer, size_t length) {
    if (active) file_writes++;
    if (descriptor >= 3 && descriptor < 4096 && !descriptors[descriptor]) written[descriptor] = 1;
    return REAL(write, ssize_t (*)(int, const void *, size_t))(descriptor, buffer, length);
}
ssize_t test_send(int descriptor, const void *buffer, size_t length, int flags) {
    if (active) sends++;
    if (mode("write-eintr") && injected++ == 0) { errno = EINTR; return -1; }
    if (mode("write-would-block") && injected++ == 0) { errno = EAGAIN; return -1; }
    if (mode("short-write") && length > 2) length = 2;
    if (mode("write-deadline")) { errno = EAGAIN; return -1; }
    ssize_t result = REAL(send, ssize_t (*)(int, const void *, size_t, int))(descriptor, buffer, length, flags);
    if (active && result > 0) sent_bytes += result;
    return result;
}
int test_poll(struct pollfd *fds, nfds_t count, int timeout) {
    if (active) polls++;
    if (mode("poll-eintr") && injected++ == 0) { errno = EINTR; return -1; }
    return REAL(poll, int (*)(struct pollfd *, nfds_t, int))(fds, count, timeout);
}
int test_fcntl(int descriptor, int operation, ...) {
    if (active) controls++;
    if (operation == F_GETFL || operation == F_GETFD)
        return REAL(fcntl, int (*)(int, int, ...))(descriptor, operation);
    va_list args;
    va_start(args, operation);
    int value = va_arg(args, int);
    va_end(args);
    if (operation == F_SETFL && !(value & O_NONBLOCK) && mode("restore-error") && ++restores == 2) {
        errno = EIO;
        return -1;
    }
    return REAL(fcntl, int (*)(int, int, ...))(descriptor, operation, value);
}
int test_clock_gettime(clockid_t clock, struct timespec *result) {
    if (active) clocks++;
    if (mode("write-deadline") || mode("resolver-deadline")) {
        fake_clock += 10000000;
        result->tv_sec = (time_t) (fake_clock / 1000000000);
        result->tv_nsec = (long) (fake_clock % 1000000000);
        return 0;
    }
    return REAL(clock_gettime, int (*)(clockid_t, struct timespec *))(clock, result);
}
int test_getaddrinfo(const char *name, const char *service, const struct addrinfo *hints, struct addrinfo **result) {
    assert(!getenv("IRONWOOD_TEST_FORBID_DNS"));
    if (mode("proxy-dns") || mode("resolver-deadline") || mode("resolver-error")) {
        assert(!strcmp(name, "proxy.invalid"));
        if (mode("resolver-error")) { *result = NULL; return EAI_NONAME; }
        if (mode("resolver-deadline")) fake_clock += 1000000000;
        return REAL(getaddrinfo, int (*)(const char *, const char *, const struct addrinfo *, struct addrinfo **))(
                "127.0.0.1", service, hints, result);
    }
    return REAL(getaddrinfo, int (*)(const char *, const char *, const struct addrinfo *, struct addrinfo **))(
            name, service, hints, result);
}
__attribute__((destructor)) static void finish(void) {
    report();
    fprintf(stderr, "TLS_FDS opened=%d closed=%d\n", opened, closed);
    assert(opened == closed);
}
INTERPOSE(test_fputc, fputc);
INTERPOSE(test_socket, socket);
INTERPOSE(test_connect, connect);
INTERPOSE(test_close, close);
INTERPOSE(test_recv, recv);
INTERPOSE(test_send, send);
INTERPOSE(test_poll, poll);
INTERPOSE(test_fcntl, fcntl);
INTERPOSE(test_clock_gettime, clock_gettime);
INTERPOSE(test_getaddrinfo, getaddrinfo);

INTERPOSE(test_malloc, malloc);
INTERPOSE(test_calloc, calloc);
INTERPOSE(test_realloc, realloc);
INTERPOSE(test_free, free);
INTERPOSE(test_write, write);
