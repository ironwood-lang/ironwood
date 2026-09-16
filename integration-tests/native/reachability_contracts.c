// SPDX-License-Identifier: MIT OR Apache-2.0

/* Execute the shipped adapter against deterministic syscall and clock results.
 * No raw sockets, echo service, production hooks, or host privileges are used. */
#if defined(__linux__)
#define _DEFAULT_SOURCE 1
#define _BSD_SOURCE 1
#define _POSIX_C_SOURCE 200809L
#endif
#include <arpa/inet.h>
#include <netinet/in.h>
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

enum fault { NONE, RAW_SOCKET, TCP_SOCKET, FLAGS, TTL, SOURCE, SEND, RECEIVE, WAIT, COMPLETION, CLOCK };
static enum fault fault;
static int error_code, raw_denied, completion, pending, family, opened, closed, active;
static int sends, receives, polls, clocks, options, bindings, connections, raw_calls, tcp_calls;
static int interrupted_send, interrupted_poll, interrupted_receive, interrupted_completion;
static int rejected, reject_kind, timeout_poll, send_blocked, wait_invalid, partial_send;
static int expected_ttl, expected_source, budget, previous_wait, assertions;
static int64_t nanos;
static unsigned char request[16];
static struct sockaddr_storage destination;

static int fail(enum fault operation) {
    if (fault != operation) return 0;
    errno = error_code;
    return 1;
}
static int test_socket(int domain, int type, int protocol) {
    assert(domain == (family == 4 ? AF_INET : AF_INET6));
    if (type == SOCK_RAW) {
        raw_calls++;
        assert(protocol == (family == 4 ? IPPROTO_ICMP : IPPROTO_ICMPV6));
        if (raw_denied) { errno = EACCES; return -1; }
        if (fail(RAW_SOCKET)) return -1;
    } else {
        tcp_calls++;
        assert(type == SOCK_STREAM && protocol == IPPROTO_TCP);
        if (fail(TCP_SOCKET)) return -1;
    }
    assert(!active);
    active = 100 + ++opened;
    return active;
}
static int test_close(int descriptor) {
    assert(descriptor == active && active);
    active = 0;
    closed++;
    errno = EIO; /* Cleanup must not replace a captured native error. */
    return 0;
}
static int test_fcntl(int descriptor, int command, ...) {
    assert(descriptor == active);
    if (fail(FLAGS)) return -1;
    if (command == F_SETFD || command == F_SETFL) {
        va_list args;
        va_start(args, command);
        int value = va_arg(args, int);
        va_end(args);
        assert(value == (command == F_SETFD ? FD_CLOEXEC : O_NONBLOCK));
    } else assert(command == F_GETFD || command == F_GETFL);
    return 0;
}
static int test_setsockopt(int descriptor, int level, int option, const void *value, socklen_t length) {
    assert(descriptor == active && length == sizeof(int) && *(const int *) value == expected_ttl);
    assert(level == (family == 4 ? IPPROTO_IP : IPPROTO_IPV6));
    assert(option == (family == 4 ? IP_TTL : IPV6_UNICAST_HOPS));
    options++;
    return fail(TTL) ? -1 : 0;
}
static int test_bind(int descriptor, const struct sockaddr *source, socklen_t length) {
    assert(descriptor == active && expected_source);
    assert(source->sa_family == (family == 4 ? AF_INET : AF_INET6));
    if (family == 4) {
        assert(length == sizeof(struct sockaddr_in));
        assert(ntohl(((const struct sockaddr_in *) source)->sin_addr.s_addr) == 0x7f000002);
    } else {
        const struct sockaddr_in6 *address = (const struct sockaddr_in6 *) source;
        assert(length == sizeof(*address) && address->sin6_scope_id == 9 && address->sin6_addr.s6_addr[15] == 2);
    }
    bindings++;
    return fail(SOURCE) ? -1 : 0;
}
static int test_connect(int descriptor, const struct sockaddr *address, socklen_t length) {
    assert(descriptor == active);
    assert(length == (family == 4 ? sizeof(struct sockaddr_in) : sizeof(struct sockaddr_in6)));
    assert(ntohs(family == 4 ? ((const struct sockaddr_in *) address)->sin_port
            : ((const struct sockaddr_in6 *) address)->sin6_port) == 7);
    connections++;
    if (pending) { errno = pending; return -1; }
    if (completion) { errno = completion; return -1; }
    return 0;
}
static int test_getsockopt(int descriptor, int level, int option, void *value, socklen_t *length) {
    assert(descriptor == active && level == SOL_SOCKET && option == SO_ERROR && *length == sizeof(int));
    if (interrupted_completion-- > 0) { errno = EINTR; return -1; }
    if (fail(COMPLETION)) return -1;
    *(int *) value = completion;
    return 0;
}
static int test_clock(clockid_t clock, struct timespec *value) {
    assert(clock == CLOCK_MONOTONIC);
    clocks++;
    if (fail(CLOCK)) return -1;
    value->tv_sec = nanos / 1000000000;
    value->tv_nsec = nanos % 1000000000;
    nanos += 1000000;
    return 0;
}
static int test_poll(struct pollfd *fds, nfds_t count, int timeout) {
    assert(count == 1 && fds->fd == active && timeout > 0 && timeout <= budget);
    assert(!previous_wait || timeout <= previous_wait);
    previous_wait = timeout;
    polls++;
    if (interrupted_poll-- > 0) { nanos += 3000000; errno = EINTR; return -1; }
    if (fail(WAIT)) return -1;
    if (timeout_poll) { nanos += (int64_t) timeout * 1000000; return 0; }
    fds->revents = wait_invalid ? POLLNVAL : fds->events;
    return 1;
}
static ssize_t test_sendto(int descriptor, const void *packet, size_t size, int flags,
        const struct sockaddr *address, socklen_t length) {
    assert(descriptor == active && !flags && size == sizeof(request));
    sends++;
    if (interrupted_send-- > 0) { errno = EINTR; return -1; }
    if (send_blocked-- > 0) { errno = EAGAIN; return -1; }
    if (fail(SEND)) return -1;
    memcpy(request, packet, size);
    memcpy(&destination, address, length);
    return partial_send ? 5 : (ssize_t) size;
}
static uint16_t checksum(const unsigned char *data, size_t length) {
    unsigned int total = 0;
    for (size_t index = 0; index < length; index += 2) total += (unsigned) data[index] * 256 + data[index + 1];
    while (total > 65535) total = (total & 65535) + (total >> 16);
    return (uint16_t) ~total;
}
static ssize_t test_recvfrom(int descriptor, void *buffer, size_t capacity, int flags,
        struct sockaddr *sender, socklen_t *length) {
    assert(descriptor == active && !flags && capacity >= 36 && *length >= sizeof(destination));
    receives++;
    if (interrupted_receive-- > 0) { errno = EINTR; return -1; }
    if (fail(RECEIVE)) return -1;
    if (timeout_poll) { errno = EAGAIN; return -1; }
    memcpy(sender, &destination, sizeof(destination));
    unsigned char *packet = buffer;
    size_t header = family == 4 ? 20 : 0;
    memset(packet, 0, header + 16);
    if (header) { packet[0] = 0x45; packet[9] = IPPROTO_ICMP; }
    memcpy(packet + header, request, 16);
    packet[header] = family == 4 ? 0 : 129;
    packet[header + 2] = packet[header + 3] = 0;
    int invalid = rejected-- > 0;
    if (invalid) {
        switch (reject_kind) {
            case 0: packet[header + 4] ^= 1; break;
            case 1: packet[header + 8] ^= 1; break;
            case 2: packet[header] = 3; break;
            case 3: ((unsigned char *) sender)[family == 4 ? 7 : 23] ^= 1; break;
            case 4: packet[header + 1] = 1; break;
            case 5: return (ssize_t) header + 8;
            case 6: if (header) packet[0] = 0x44; else packet[header + 7] ^= 1; break;
            case 7: if (!header) ((struct sockaddr_in6 *) sender)->sin6_scope_id++; break;
        }
    }
    if (header) {
        uint16_t sum = checksum(packet + header, 16);
        packet[header + 2] = (unsigned char) (sum >> 8);
        packet[header + 3] = (unsigned char) sum;
        if (invalid && reject_kind == 7) packet[header + 2] ^= 1;
    }
    return (ssize_t) header + 16;
}

#define socket test_socket
#define close test_close
#define fcntl test_fcntl
#define setsockopt test_setsockopt
#define bind test_bind
#define connect test_connect
#define getsockopt test_getsockopt
#define clock_gettime test_clock
#define poll test_poll
#define sendto test_sendto
#define recvfrom test_recvfrom
#include "../../runtime/src/ironwood_host.c"

static void reset(int target_family) {
    assert(opened == closed && !active);
    family = target_family;
    fault = NONE;
    error_code = EIO;
    raw_denied = completion = pending = 0;
    opened = closed = sends = receives = polls = clocks = options = bindings = connections = raw_calls = tcp_calls = 0;
    interrupted_send = interrupted_poll = interrupted_receive = interrupted_completion = 0;
    rejected = reject_kind = timeout_poll = send_blocked = wait_invalid = partial_send = 0;
    expected_ttl = expected_source = previous_wait = 0;
    budget = 20;
    nanos = 1000000000;
}
static void check(int value, int error) {
    int64_t result = ironwood_tcp_reachable(family, family == 6 ? (int32_t) 0xfe800000 : 0, 0, 0,
            family == 4 ? 0x7f000001 : 1, 0, expected_source ? family : 0,
            family == 6 ? (int32_t) 0xfe800000 : 0, 0, 0, family == 4 ? 0x7f000002 : 2, 9,
            expected_source ? 9 : 0, expected_ttl, budget);
    assert((int32_t) result == value && (int) ((uint64_t) result >> 32) == error);
    assert(opened == closed && !active && nanos <= 1050000000);
    assertions++;
}
int main(void) {
    for (int f = 4; f <= 6; f += 2) {
        reset(f); check(1, 0); assert(raw_calls == 1 && sends == 1 && receives == 1 && !polls);
        reset(f); expected_ttl = 7; expected_source = 1; check(1, 0); assert(options == 1 && bindings == 1);
        if (f == 6) assert(((struct sockaddr_in6 *) &destination)->sin6_scope_id == 9);
        for (int kind = 0; kind < 8; kind++) {
            reset(f); expected_source = 1; rejected = 1; reject_kind = kind; check(1, 0); assert(receives == 2);
            reset(f); expected_source = 1; rejected = 100; reject_kind = kind; check(0, 0);
        }
        reset(f); budget = 0; check(1, 0); assert(!polls);
        reset(f); budget = 0; rejected = 1; check(0, 0); assert(!polls);
        reset(f); interrupted_send = 1; interrupted_receive = 1; interrupted_poll = 2; check(1, 0);
        reset(f); send_blocked = 1; check(1, 0); assert(polls == 1 && sends == 2);
        reset(f); timeout_poll = 1; check(0, 0); assert(polls == 1);
        reset(f); partial_send = 1; check(-1, EIO);
        reset(f); fault = SEND; error_code = EPERM; completion = ECONNREFUSED; check(1, 0); assert(opened == 2);
        for (int immediate = 0; immediate < 2; immediate++) {
            for (int outcome = 0; outcome < 3; outcome++) {
                reset(f); raw_denied = 1; pending = immediate ? 0 : EINPROGRESS;
                completion = outcome == 0 ? 0 : outcome == 1 ? ECONNREFUSED : ETIMEDOUT;
                check(outcome < 2, 0); assert(connections == 1 && tcp_calls == 1);
            }
        }
        reset(f); raw_denied = 1; expected_ttl = 3; expected_source = 1; check(1, 0); assert(options == 1 && bindings == 1);
        reset(f); raw_denied = 1; pending = EINTR; interrupted_poll = 1; interrupted_completion = 1; check(1, 0);
        reset(f); raw_denied = 1; pending = EINPROGRESS; timeout_poll = 1; check(0, 0);
        reset(f); raw_denied = 1; pending = EINPROGRESS; wait_invalid = 1; check(-1, EBADF);
        reset(f); raw_denied = 1; pending = EINPROGRESS; budget = 0; check(0, 0); assert(!polls);
        for (int unavailable = 0; unavailable < 4; unavailable++) {
            reset(f); fault = RAW_SOCKET;
            error_code = unavailable == 0 ? EPERM : unavailable == 1 ? EAFNOSUPPORT
                    : unavailable == 2 ? EPROTONOSUPPORT : ESOCKTNOSUPPORT;
            check(1, 0); assert(tcp_calls == 1);
        }
        for (enum fault operation = RAW_SOCKET; operation <= CLOCK; operation++) {
            reset(f); fault = operation;
            if (operation == TCP_SOCKET || operation == COMPLETION) raw_denied = 1;
            if (operation == WAIT || operation == COMPLETION) { raw_denied = 1; pending = EINPROGRESS; }
            if (operation == SOURCE) expected_source = 1;
            if (operation == TTL) expected_ttl = 1;
            check(-1, EIO);
        }
        reset(f); fault = RECEIVE; error_code = ENETUNREACH; check(0, 0);
        reset(f); raw_denied = 1; completion = EHOSTUNREACH; check(0, 0);
        reset(f); raw_denied = 1; completion = EACCES; check(-1, EACCES);
    }
    reset(4);
    assert(ironwood_tcp_reachable(4, 0, 0, 0, 1, 0, 6, 0, 0, 0, 2, 0, 0, 0, 20) == 0);
    assert(!clocks && !opened);
    assert((uint64_t) ironwood_tcp_reachable(4, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, -1, 20) >> 32 == EINVAL);
    assert(!clocks && !opened);
    printf("REACHABILITY_CONTRACTS %d scenarios; both families; balanced descriptors\n", assertions + 2);
    return 0;
}
