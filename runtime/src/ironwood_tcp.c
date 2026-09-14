// SPDX-License-Identifier: MIT OR Apache-2.0

#if defined(__linux__) && !defined(_POSIX_C_SOURCE)
#define _POSIX_C_SOURCE 200809L
#endif

#include "../include/ironwood_runtime.h"
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>

/* Each attempt captures errno before any cleanup. No TLS, heap allocation,
 * clock reads, readiness work, or descriptor-mode work occurs on I/O success. */
static int64_t tcp_result(int32_t value, int error) {
    return (int64_t) (((uint64_t) (uint32_t) error << 32) | (uint32_t) value);
}

static int64_t tcp_status(int value) {
    return value < 0 ? tcp_result(-1, errno) : tcp_result(value, 0);
}

static int prepare_descriptor(int descriptor, int accepted) {
    int flags = fcntl(descriptor, F_GETFD);
    if (flags < 0 || fcntl(descriptor, F_SETFD, flags | FD_CLOEXEC) < 0) return -1;
#if defined(__APPLE__)
    int enabled = 1;
    if (setsockopt(descriptor, SOL_SOCKET, SO_NOSIGPIPE, &enabled, sizeof(enabled)) < 0) return -1;
#endif
    if (accepted) {
        flags = fcntl(descriptor, F_GETFL);
        if (flags < 0) return -1;
        if ((flags & O_NONBLOCK) != 0 && fcntl(descriptor, F_SETFL, flags & ~O_NONBLOCK) < 0) return -1;
    }
    return 0;
}

int64_t ironwood_tcp_create(int32_t family) {
    int native_family = family == 6 ? AF_INET6 : family == 4 ? AF_INET : -1;
    if (native_family < 0) return tcp_result(-1, EAFNOSUPPORT);
    int descriptor = socket(native_family, SOCK_STREAM, IPPROTO_TCP);
    if (descriptor < 0) return tcp_result(-1, errno);
    int failed = prepare_descriptor(descriptor, 0);
    if (!failed && native_family == AF_INET6) {
        int disabled = 0;
        failed = setsockopt(descriptor, IPPROTO_IPV6, IPV6_V6ONLY, &disabled, sizeof(disabled));
    }
    if (failed) {
        int error = errno;
        close(descriptor);
        return tcp_result(-1, error);
    }
    return tcp_result(descriptor, 0);
}

static socklen_t endpoint_address(struct sockaddr_storage *storage, int socket_family,
        int address_family, int32_t a, int32_t b, int32_t c, int32_t d, int port) {
    memset(storage, 0, sizeof(*storage));
    if (socket_family == 4) {
        if (address_family == 6) return 0;
        struct sockaddr_in *address = (struct sockaddr_in *) storage;
        address->sin_family = AF_INET;
        address->sin_port = htons((uint16_t) port);
        address->sin_addr.s_addr = htonl((uint32_t) d);
        return sizeof(*address);
    }
    struct sockaddr_in6 *address = (struct sockaddr_in6 *) storage;
    address->sin6_family = AF_INET6;
    address->sin6_port = htons((uint16_t) port);
    uint32_t words[4] = {htonl((uint32_t) a), htonl((uint32_t) b),
        htonl((uint32_t) c), htonl((uint32_t) d)};
    if (address_family == 4 && d != 0) {
        words[0] = 0;
        words[1] = 0;
        words[2] = htonl(65535);
    }
    memcpy(&address->sin6_addr, words, sizeof(words));
    return sizeof(*address);
}

int64_t ironwood_tcp_bind(int32_t descriptor, int32_t socket_family, int32_t address_family,
        int32_t a, int32_t b, int32_t c, int32_t d, int32_t port) {
    struct sockaddr_storage address;
    socklen_t size = endpoint_address(&address, socket_family, address_family, a, b, c, d, port);
    if (!size) return tcp_result(-1, EAFNOSUPPORT);
    return tcp_status(bind(descriptor, (struct sockaddr *) &address, size));
}

int64_t ironwood_tcp_connect(int32_t descriptor, int32_t socket_family, int32_t address_family,
        int32_t a, int32_t b, int32_t c, int32_t d, int32_t port) {
    struct sockaddr_storage address;
    socklen_t size = endpoint_address(&address, socket_family, address_family, a, b, c, d, port);
    if (!size) return tcp_result(-1, EAFNOSUPPORT);
    return tcp_status(connect(descriptor, (struct sockaddr *) &address, size));
}

int64_t ironwood_tcp_listen(int32_t descriptor, int32_t backlog) {
    return tcp_status(listen(descriptor, backlog > 0 ? backlog : 50));
}

int64_t ironwood_tcp_accept(int32_t listener) {
    int descriptor = accept(listener, NULL, NULL);
    if (descriptor < 0) return tcp_result(-1, errno);
    if (prepare_descriptor(descriptor, 1) < 0) {
        int error = errno;
        close(descriptor);
        return tcp_result(-1, error);
    }
    return tcp_result(descriptor, 0);
}

int64_t ironwood_tcp_complete_connect(int32_t descriptor) {
    int error = 0;
    socklen_t size = sizeof(error);
    if (getsockopt(descriptor, SOL_SOCKET, SO_ERROR, &error, &size) < 0) return tcp_result(-1, errno);
    return tcp_result(error ? -1 : 0, error);
}

static int64_t receive_bytes(int descriptor, void *buffer, int offset, int length, int flags) {
    /* Typed IR proves the byte-array range, including direct internal calls. */
    if (length == 0) return tcp_result(0, 0);
    struct ironwood_array *array = buffer;
    ssize_t count = recv(descriptor, array->data + offset, (size_t) length, flags);
    if (count < 0) return tcp_result(-1, errno);
    return tcp_result(count == 0 ? -1 : (int32_t) count, 0);
}

static int64_t receive_byte(int descriptor, int flags) {
    unsigned char value;
    ssize_t count = recv(descriptor, &value, 1, flags);
    if (count < 0) return tcp_result(-1, errno);
    return tcp_result(count == 0 ? -1 : value, 0);
}

int64_t ironwood_tcp_read_byte(int32_t descriptor) { return receive_byte(descriptor, 0); }
int64_t ironwood_tcp_try_read_byte(int32_t descriptor) { return receive_byte(descriptor, MSG_DONTWAIT); }
int64_t ironwood_tcp_read_bytes(int32_t descriptor, void *buffer, int32_t offset, int32_t length) {
    return receive_bytes(descriptor, buffer, offset, length, 0);
}
int64_t ironwood_tcp_try_read_bytes(int32_t descriptor, void *buffer, int32_t offset, int32_t length) {
    return receive_bytes(descriptor, buffer, offset, length, MSG_DONTWAIT);
}

#if defined(__APPLE__)
#define IRONWOOD_TCP_SEND_FLAGS 0
#else
#define IRONWOOD_TCP_SEND_FLAGS MSG_NOSIGNAL
#endif

int64_t ironwood_tcp_write_byte(int32_t descriptor, int32_t value) {
    unsigned char byte = (unsigned char) value;
    return tcp_status((int) send(descriptor, &byte, 1, IRONWOOD_TCP_SEND_FLAGS));
}

int64_t ironwood_tcp_write_bytes(int32_t descriptor, const void *buffer, int32_t offset, int32_t length) {
    if (length == 0) return tcp_result(0, 0);
    const struct ironwood_array *array = buffer;
    return tcp_status((int) send(descriptor, array->data + offset, (size_t) length, IRONWOOD_TCP_SEND_FLAGS));
}

int64_t ironwood_tcp_available(int32_t descriptor) {
    int available = 0;
    if (ioctl(descriptor, FIONREAD, &available) < 0) return tcp_result(-1, errno);
    return tcp_result(available, 0);
}

int64_t ironwood_tcp_shutdown(int32_t descriptor, int32_t direction) {
    return tcp_status(shutdown(descriptor, direction == 0 ? SHUT_RD : SHUT_WR));
}

int64_t ironwood_tcp_close(int32_t descriptor) { return tcp_status(close(descriptor)); }

int64_t ironwood_tcp_wait(int32_t descriptor, _Bool write_ready, int64_t remaining_nanos) {
    int timeout = -1;
    if (remaining_nanos >= 0) {
        int64_t milliseconds = remaining_nanos / 1000000 + (remaining_nanos % 1000000 != 0);
        timeout = milliseconds > INT_MAX ? INT_MAX : (int) milliseconds;
    }
    struct pollfd descriptor_event = {descriptor, write_ready ? POLLOUT : POLLIN, 0};
    return tcp_status(poll(&descriptor_event, 1, timeout));
}

int64_t ironwood_tcp_blocking(int32_t descriptor, _Bool blocking) {
    int flags = fcntl(descriptor, F_GETFL);
    if (flags < 0) return tcp_result(-1, errno);
    int next = blocking ? flags & ~O_NONBLOCK : flags | O_NONBLOCK;
    if (flags != next && fcntl(descriptor, F_SETFL, next) < 0) return tcp_result(-1, errno);
    return tcp_result(flags, 0);
}

int64_t ironwood_tcp_restore_flags(int32_t descriptor, int32_t flags) {
    return tcp_status(fcntl(descriptor, F_SETFL, flags));
}

static int boolean_option(int code, int *level) {
    *level = SOL_SOCKET;
    switch (code) {
        case 1: *level = IPPROTO_TCP; return TCP_NODELAY;
        case 2: return SO_KEEPALIVE;
        case 3: return SO_REUSEADDR;
        default: return -1;
    }
}

int64_t ironwood_tcp_get_boolean(int32_t descriptor, int32_t code) {
    int level, option = boolean_option(code, &level), value = 0;
    if (option < 0) return tcp_result(-1, EINVAL);
    socklen_t size = sizeof(value);
    if (getsockopt(descriptor, level, option, &value, &size) < 0) return tcp_result(-1, errno);
    return tcp_result(value != 0, 0);
}

int64_t ironwood_tcp_set_boolean(int32_t descriptor, int32_t code, _Bool enabled) {
    int level, option = boolean_option(code, &level), value = enabled;
    if (option < 0) return tcp_result(-1, EINVAL);
    return tcp_status(setsockopt(descriptor, level, option, &value, sizeof(value)));
}

int64_t ironwood_tcp_get_integer(int32_t descriptor, int32_t code) {
    if (code == 6) {
        struct linger value;
        socklen_t size = sizeof(value);
        if (getsockopt(descriptor, SOL_SOCKET, SO_LINGER, &value, &size) < 0) return tcp_result(-1, errno);
        // Darwin exposes the kernel's 16-bit linger value with sign extension.
        // The source contract uses the unsigned 0..65535 seconds range.
        return tcp_result(value.l_onoff ? (uint16_t) value.l_linger : -1, 0);
    }
    int option = code == 4 ? SO_SNDBUF : code == 5 ? SO_RCVBUF : -1, value = 0;
    if (option < 0) return tcp_result(-1, EINVAL);
    socklen_t size = sizeof(value);
    if (getsockopt(descriptor, SOL_SOCKET, option, &value, &size) < 0) return tcp_result(-1, errno);
    return tcp_result(value, 0);
}

int64_t ironwood_tcp_set_integer(int32_t descriptor, int32_t code, int32_t value) {
    if (code == 6) {
        struct linger linger_value = {value >= 0, value < 0 ? 0 : value};
        return tcp_status(setsockopt(descriptor, SOL_SOCKET, SO_LINGER, &linger_value, sizeof(linger_value)));
    }
    int option = code == 4 ? SO_SNDBUF : code == 5 ? SO_RCVBUF : -1;
    if (option < 0) return tcp_result(-1, EINVAL);
    return tcp_status(setsockopt(descriptor, SOL_SOCKET, option, &value, sizeof(value)));
}

int64_t ironwood_tcp_endpoint(int32_t descriptor, _Bool peer, int32_t *family,
        int32_t *a, int32_t *b, int32_t *c, int32_t *d, int32_t *port) {
    struct sockaddr_storage storage;
    socklen_t size = sizeof(storage);
    int status = peer ? getpeername(descriptor, (struct sockaddr *) &storage, &size)
            : getsockname(descriptor, (struct sockaddr *) &storage, &size);
    if (status < 0) return tcp_result(-1, errno);
    uint32_t words[4] = {0, 0, 0, 0};
    int result_family, result_port;
    if (storage.ss_family == AF_INET) {
        const struct sockaddr_in *address = (const struct sockaddr_in *) &storage;
        result_family = 4;
        result_port = ntohs(address->sin_port);
        words[3] = ntohl(address->sin_addr.s_addr);
    } else if (storage.ss_family == AF_INET6) {
        const struct sockaddr_in6 *address = (const struct sockaddr_in6 *) &storage;
        result_family = 6;
        result_port = ntohs(address->sin6_port);
        memcpy(words, &address->sin6_addr, sizeof(words));
        for (int index = 0; index < 4; index++) words[index] = ntohl(words[index]);
        if (words[0] == 0 && words[1] == 0 && words[2] == 65535) {
            result_family = 4;
            words[2] = 0;
        }
    } else { return tcp_result(-1, EAFNOSUPPORT); }
    *family = result_family; *port = result_port;
    *a = (int32_t) words[0]; *b = (int32_t) words[1];
    *c = (int32_t) words[2]; *d = (int32_t) words[3];
    return tcp_result(0, 0);
}

int32_t ironwood_tcp_error_kind(int32_t error) {
    switch (error) {
        case 0: return 0;
        case EINTR: return 1;
        case EAGAIN: return 2;
#if EAGAIN != EWOULDBLOCK
        case EWOULDBLOCK: return 2;
#endif
        case EINPROGRESS: case EALREADY: return 3;
        case ECONNREFUSED: return 4;
        case ETIMEDOUT: return 5;
        case EADDRINUSE: case EADDRNOTAVAIL: return 6;
        case EAFNOSUPPORT: case EPROTONOSUPPORT: return 7;
        case ECONNRESET: return 8;
        case EBADF: case ENOTSOCK: return 9;
        default: return 10;
    }
}
