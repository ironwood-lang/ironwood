// SPDX-License-Identifier: MIT OR Apache-2.0

#if defined(__linux__)
#define _DEFAULT_SOURCE 1
#define _BSD_SOURCE 1
#define _POSIX_C_SOURCE 200809L
#endif

#include "../include/ironwood_runtime.h"
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <ifaddrs.h>
#include <limits.h>
#include <net/if.h>
#include <netinet/in.h>
#include <poll.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>
#if defined(__APPLE__)
#include <net/if_dl.h>
#else
#include <netpacket/packet.h>
#endif

static int64_t host_result(int32_t value, int error) {
    return (int64_t) (((uint64_t) (uint32_t) error << 32) | (uint32_t) value);
}

/* One native index owns getifaddrs storage until all managed records are copied.
 * Sorting groups names without a registry or repeated prefix rescans. */
struct host_row {
    const struct ifaddrs *source;
    int32_t ordinal;
    int32_t group;
};
struct host_capture {
    struct ifaddrs *source;
    int32_t count;
    struct host_row rows[];
};

static int host_compare(const struct host_row *a, const struct host_row *b) {
    int name = strcmp(a->source->ifa_name, b->source->ifa_name);
    return name ? name : (a->ordinal > b->ordinal) - (a->ordinal < b->ordinal);
}

/* In-place heapsort keeps scratch bounded on libc implementations whose qsort
 * allocates a temporary merge buffer. Ordinals preserve per-interface order. */
static void host_sift(struct host_row *rows, size_t root, size_t count) {
    while (root < count / 2) {
        size_t child = root * 2 + 1;
        if (child + 1 < count && host_compare(&rows[child], &rows[child + 1]) < 0) child++;
        if (host_compare(&rows[root], &rows[child]) >= 0) return;
        struct host_row value = rows[root];
        rows[root] = rows[child];
        rows[child] = value;
        root = child;
    }
}

static void host_sort(struct host_row *rows, size_t count) {
    for (size_t root = count / 2; root; root--) host_sift(rows, root - 1, count);
    for (size_t end = count; end > 1; end--) {
        struct host_row value = rows[0];
        rows[0] = rows[end - 1];
        rows[end - 1] = value;
        host_sift(rows, 0, end - 1);
    }
}

int64_t ironwood_tcp_interfaces_start(int64_t *handle, int32_t *count) {
    *handle = 0;
    *count = 0;
    struct ifaddrs *source = NULL;
    if (getifaddrs(&source) < 0) return host_result(-1, errno);
    size_t rows = 0;
    for (const struct ifaddrs *p = source; p; p = p->ifa_next) {
        if (p->ifa_name && p->ifa_name[0]) rows++;
    }
    if (!rows) { freeifaddrs(source); return host_result(-1, ENODEV); }
    if (rows > INT_MAX || rows > (SIZE_MAX - sizeof(struct host_capture)) / sizeof(struct host_row)) {
        freeifaddrs(source);
        return host_result(-1, ENOMEM);
    }
    struct host_capture *capture = calloc(1, sizeof(*capture) + rows * sizeof(struct host_row));
    if (!capture) { freeifaddrs(source); return host_result(-1, ENOMEM); }
    capture->source = source;
    capture->count = (int32_t) rows;
    int32_t index = 0;
    for (const struct ifaddrs *p = source; p; p = p->ifa_next) {
        if (p->ifa_name && p->ifa_name[0]) {
            capture->rows[index] = (struct host_row) {p, index, 0};
            index++;
        }
    }
    host_sort(capture->rows, rows);
    for (int32_t i = 0; i < capture->count; i++) {
        if (!i || strcmp(capture->rows[i - 1].source->ifa_name, capture->rows[i].source->ifa_name)) (*count)++;
        capture->rows[i].group = *count - 1;
    }
    *handle = (int64_t) (uintptr_t) capture;
    return 0;
}

static int host_family(const struct ifaddrs *entry) {
    if (!entry->ifa_addr) return 0;
    return entry->ifa_addr->sa_family == AF_INET ? 4 : entry->ifa_addr->sa_family == AF_INET6 ? 6 : 0;
}

static int32_t host_parent(const struct host_capture *capture, const char *name) {
    const char *colon = strchr(name, ':');
    if (!colon) return -1;
    char parent[IF_NAMESIZE];
    size_t length = (size_t) (colon - name);
    if (!length || length >= sizeof(parent)) return -1;
    memcpy(parent, name, length);
    parent[length] = 0;
    int32_t low = 0, high = capture->count;
    while (low < high) {
        int32_t middle = low + (high - low) / 2;
        int cmp = strcmp(capture->rows[middle].source->ifa_name, parent);
        if (!cmp) return capture->rows[middle].group;
        if (cmp < 0) low = middle + 1;
        else high = middle;
    }
    return -1;
}

int64_t ironwood_tcp_interface_next(int64_t handle, int32_t position, void *output,
        int32_t *cursor, int32_t *interface_index, int32_t *parent,
        int32_t *address_count, int32_t *address_cursor) {
    const struct host_capture *capture = (const struct host_capture *) (uintptr_t) handle;
    if (!capture || position < 0 || position >= capture->count) return host_result(-1, EINVAL);
    const char *name = capture->rows[position].source->ifa_name;
    struct ironwood_array *bytes = output;
    size_t length = strlen(name);
    if (length > bytes->length || length > INT_MAX) return host_result(-1, ENAMETOOLONG);
    memcpy(bytes->data, name, length);
    unsigned int native_index = if_nametoindex(name);
    *interface_index = native_index ? (int32_t) native_index : -1;
    *parent = host_parent(capture, name);
    *address_count = 0;
    *address_cursor = position;
    int32_t end = position;
    while (end < capture->count && !strcmp(capture->rows[end].source->ifa_name, name)) {
        if (host_family(capture->rows[end].source)) (*address_count)++;
        end++;
    }
    *cursor = end;
    return host_result((int32_t) length, 0);
}

static int32_t host_word(const unsigned char *bytes) {
    return (int32_t) ((uint32_t) bytes[0] << 24 | (uint32_t) bytes[1] << 16
            | (uint32_t) bytes[2] << 8 | bytes[3]);
}

static const unsigned char *host_octets(const struct sockaddr *address) {
    return address->sa_family == AF_INET
            ? (const unsigned char *) &((const struct sockaddr_in *) address)->sin_addr
            : ((const struct sockaddr_in6 *) address)->sin6_addr.s6_addr;
}

int64_t ironwood_tcp_interface_address(int64_t handle, int32_t position,
        int32_t *family, int32_t *a, int32_t *b, int32_t *c, int32_t *d, int32_t *scope,
        int32_t *prefix, int32_t *broadcast, int32_t *has_broadcast, int32_t *cursor) {
    const struct host_capture *capture = (const struct host_capture *) (uintptr_t) handle;
    if (!capture || position < 0 || position >= capture->count) return host_result(-1, EINVAL);
    int32_t group = capture->rows[position].group;
    while (position < capture->count && capture->rows[position].group == group
            && !host_family(capture->rows[position].source)) position++;
    if (position == capture->count || capture->rows[position].group != group) return host_result(-1, EINVAL);
    const struct ifaddrs *entry = capture->rows[position].source;
    *family = host_family(entry);
    const unsigned char *bits = host_octets(entry->ifa_addr);
    *a = *b = *c = *d = *scope = *prefix = *broadcast = *has_broadcast = 0;
    if (*family == 4) *d = host_word(bits);
    else {
        *a = host_word(bits); *b = host_word(bits + 4); *c = host_word(bits + 8); *d = host_word(bits + 12);
        *scope = (int32_t) ((const struct sockaddr_in6 *) entry->ifa_addr)->sin6_scope_id;
    }
    if (entry->ifa_netmask && entry->ifa_netmask->sa_family == entry->ifa_addr->sa_family) {
        const unsigned char *mask = host_octets(entry->ifa_netmask);
        int length = *family == 4 ? 4 : 16;
        _Bool ended = 0;
        for (int i = 0; i < length; i++) {
            for (unsigned int bit = 128; bit; bit >>= 1) {
                if (!(mask[i] & bit)) ended = 1;
                else if (!ended) (*prefix)++;
            }
        }
    }
    if (*family == 4 && (entry->ifa_flags & IFF_BROADCAST) && entry->ifa_broadaddr
            && entry->ifa_broadaddr->sa_family == AF_INET) {
        *broadcast = host_word(host_octets(entry->ifa_broadaddr));
        *has_broadcast = 1;
    }
    *cursor = position + 1;
    return 0;
}

int64_t ironwood_tcp_interfaces_release(int64_t handle) {
    struct host_capture *capture = (struct host_capture *) (uintptr_t) handle;
    if (capture) {
        freeifaddrs(capture->source);
        free(capture);
    }
    return 0;
}

static int host_name(const void *input, char output[IF_NAMESIZE]) {
    const struct ironwood_array *bytes = input;
    if (!bytes->length || bytes->length >= IF_NAMESIZE || memchr(bytes->data, 0, bytes->length)) return EINVAL;
    memcpy(output, bytes->data, bytes->length);
    output[bytes->length] = 0;
    return 0;
}

static int64_t host_ioctl(const void *name, _Bool mtu) {
    struct ifreq request;
    memset(&request, 0, sizeof(request));
    int error = host_name(name, request.ifr_name);
    if (error) return host_result(-1, error);
    int descriptor = socket(AF_INET, SOCK_DGRAM, 0);
    if (descriptor < 0 && (errno == EAFNOSUPPORT || errno == EPROTONOSUPPORT)) descriptor = socket(AF_INET6, SOCK_DGRAM, 0);
    if (descriptor < 0) return host_result(-1, errno);
    int result = ioctl(descriptor, mtu ? SIOCGIFMTU : SIOCGIFFLAGS, &request);
    error = result < 0 ? errno : 0;
    close(descriptor);
    if (error) return host_result(-1, error);
    if (mtu) return host_result(request.ifr_mtu, 0);
    unsigned int flags = (unsigned short) request.ifr_flags;
    return host_result(((flags & IFF_UP) && (flags & IFF_RUNNING) ? 1 : 0)
            | (flags & IFF_LOOPBACK ? 2 : 0) | (flags & IFF_POINTOPOINT ? 4 : 0)
            | (flags & IFF_MULTICAST ? 8 : 0), 0);
}

int64_t ironwood_tcp_interface_flags(const void *name) { return host_ioctl(name, 0); }
int64_t ironwood_tcp_interface_mtu(const void *name) { return host_ioctl(name, 1); }

int64_t ironwood_tcp_interface_hardware(const void *input, void *output) {
    char name[IF_NAMESIZE];
    int error = host_name(input, name);
    if (error) return host_result(-1, error);
    struct ifaddrs *source = NULL;
    if (getifaddrs(&source) < 0) return host_result(-1, errno);
    int length = 0;
    _Bool found = 0;
    struct ironwood_array *bytes = output;
    for (const struct ifaddrs *p = source; p; p = p->ifa_next) {
        if (!p->ifa_name || strcmp(p->ifa_name, name)) continue;
        found = 1;
        if (!p->ifa_addr || (p->ifa_flags & IFF_LOOPBACK)) continue;
        const unsigned char *address = NULL;
#if defined(__APPLE__)
        if (p->ifa_addr->sa_family == AF_LINK) {
            const struct sockaddr_dl *link = (const struct sockaddr_dl *) p->ifa_addr;
            length = link->sdl_alen;
            address = (const unsigned char *) LLADDR(link);
        }
#else
        if (p->ifa_addr->sa_family == AF_PACKET) {
            const struct sockaddr_ll *link = (const struct sockaddr_ll *) p->ifa_addr;
            length = link->sll_halen;
            /* The supported glibc getifaddrs ABI stores up to 24 address bytes
             * beyond this offset, including 20-byte InfiniBand addresses. */
            if (length > 24) { error = EOVERFLOW; break; }
            address = (const unsigned char *) p->ifa_addr + offsetof(struct sockaddr_ll, sll_addr);
        }
#endif
        if (address && length) {
            if ((size_t) length > bytes->length) error = EOVERFLOW;
            else memcpy(bytes->data, address, (size_t) length);
            break;
        }
    }
    freeifaddrs(source);
    if (!found) error = ENODEV;
    return host_result(error ? -1 : length, error);
}

/* Reachability has bounded stack storage and one monotonic deadline shared by
 * ICMP and its TCP fallback. No production test dispatch or ownership registry. */
static int64_t probe_clock(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) < 0) return -1;
    return (int64_t) now.tv_sec * 1000000000LL + now.tv_nsec;
}

static int probe_wait(int descriptor, short events, int64_t deadline) {
    for (;;) {
        int64_t now = probe_clock();
        if (now < 0) return -1;
        int64_t remaining = deadline - now;
        if (remaining <= 0) return 0;
        int64_t millis = remaining / 1000000 + (remaining % 1000000 != 0);
        struct pollfd p = {descriptor, events, 0};
        int result = poll(&p, 1, millis > INT_MAX ? INT_MAX : (int) millis);
        if (result < 0 && errno == EINTR) continue;
        if (result > 0 && (p.revents & POLLNVAL)) { errno = EBADF; return -1; }
        return result;
    }
}

static socklen_t probe_address(struct sockaddr_storage *output, int family,
        int32_t a, int32_t b, int32_t c, int32_t d, int scope, int port) {
    memset(output, 0, sizeof(*output));
    if (family == 4) {
        struct sockaddr_in *v4 = (struct sockaddr_in *) output;
        v4->sin_family = AF_INET;
        v4->sin_port = htons((uint16_t) port);
        v4->sin_addr.s_addr = htonl((uint32_t) d);
#if defined(__APPLE__)
        v4->sin_len = sizeof(*v4);
#endif
        return sizeof(*v4);
    }
    struct sockaddr_in6 *v6 = (struct sockaddr_in6 *) output;
    v6->sin6_family = AF_INET6;
    v6->sin6_port = htons((uint16_t) port);
    v6->sin6_scope_id = (uint32_t) scope;
    uint32_t words[4] = {htonl((uint32_t) a), htonl((uint32_t) b), htonl((uint32_t) c), htonl((uint32_t) d)};
    memcpy(v6->sin6_addr.s6_addr, words, sizeof(words));
#if defined(__APPLE__)
    v6->sin6_len = sizeof(*v6);
#endif
    return sizeof(*v6);
}

static int probe_prepare(int descriptor, int family, int ttl,
        const struct sockaddr_storage *source, socklen_t source_size) {
    int flags = fcntl(descriptor, F_GETFD);
    if (flags < 0 || fcntl(descriptor, F_SETFD, flags | FD_CLOEXEC) < 0) return -1;
    flags = fcntl(descriptor, F_GETFL);
    if (flags < 0 || fcntl(descriptor, F_SETFL, flags | O_NONBLOCK) < 0) return -1;
    if (ttl && setsockopt(descriptor, family == 4 ? IPPROTO_IP : IPPROTO_IPV6,
            family == 4 ? IP_TTL : IPV6_UNICAST_HOPS, &ttl, sizeof(ttl)) < 0) return -1;
    if (source_size && bind(descriptor, (const struct sockaddr *) source, source_size) < 0) return -1;
    return 0;
}

static _Bool probe_unavailable(int error) {
    return error == EPERM || error == EACCES || error == EAFNOSUPPORT
            || error == EPROTONOSUPPORT || error == ESOCKTNOSUPPORT;
}

static int64_t probe_error(int error) {
    if (error == ETIMEDOUT || error == ENETUNREACH || error == EHOSTUNREACH) return 0;
    return host_result(-1, error);
}

static int64_t probe_tcp(int family, struct sockaddr_storage *destination, socklen_t size,
        const struct sockaddr_storage *source, socklen_t source_size, int ttl, int64_t deadline) {
    int descriptor = socket(family == 4 ? AF_INET : AF_INET6, SOCK_STREAM, IPPROTO_TCP);
    if (descriptor < 0) return probe_error(errno);
    int64_t result = 0;
    if (probe_prepare(descriptor, family, ttl, source, source_size) < 0) {
        result = probe_error(errno);
    } else {
        if (family == 4) ((struct sockaddr_in *) destination)->sin_port = htons(7);
        else ((struct sockaddr_in6 *) destination)->sin6_port = htons(7);
        int status = connect(descriptor, (const struct sockaddr *) destination, size);
        int error = status < 0 ? errno : 0;
        if (!error || error == ECONNREFUSED) result = 1;
        else if (error == EINPROGRESS || error == EALREADY || error == EINTR || error == EWOULDBLOCK) {
            for (;;) {
                int ready = probe_wait(descriptor, POLLOUT, deadline);
                if (ready <= 0) { result = ready < 0 ? probe_error(errno) : 0; break; }
                socklen_t length = sizeof(error);
                if (getsockopt(descriptor, SOL_SOCKET, SO_ERROR, &error, &length) < 0) {
                    if (errno == EINTR) continue;
                    result = probe_error(errno);
                    break;
                }
                if (error == EINPROGRESS || error == EALREADY || error == EINTR) continue;
                result = !error || error == ECONNREFUSED ? 1 : probe_error(error);
                break;
            }
        } else result = probe_error(error);
    }
    close(descriptor);
    return result;
}

static uint16_t probe_checksum(const unsigned char *packet, size_t length) {
    uint32_t sum = 0;
    for (size_t i = 0; i + 1 < length; i += 2) sum += (uint32_t) packet[i] << 8 | packet[i + 1];
    if (length & 1) sum += (uint32_t) packet[length - 1] << 8;
    while (sum >> 16) sum = (sum & 65535) + (sum >> 16);
    return (uint16_t) ~sum;
}

static _Bool probe_matches(int family, const struct sockaddr_storage *destination,
        const struct sockaddr_storage *sender, const unsigned char *request,
        const unsigned char *packet, size_t length) {
    if (sender->ss_family != destination->ss_family) return 0;
    if (memcmp(host_octets((const struct sockaddr *) sender),
            host_octets((const struct sockaddr *) destination), family == 4 ? 4 : 16)) return 0;
    if (family == 6) {
        const struct sockaddr_in6 *target = (const struct sockaddr_in6 *) destination;
        const struct sockaddr_in6 *reply = (const struct sockaddr_in6 *) sender;
        if (IN6_IS_ADDR_LINKLOCAL(&target->sin6_addr) && target->sin6_scope_id
                && target->sin6_scope_id != reply->sin6_scope_id) return 0;
    }
    if (family == 4) {
        if (length < 20 || (packet[0] >> 4) != 4 || packet[9] != IPPROTO_ICMP) return 0;
        size_t header = (packet[0] & 15) * 4;
        if (header < 20 || header > length) return 0;
        packet += header;
        length -= header;
    }
    if (length != 16 || packet[0] != (family == 4 ? 0 : 129) || packet[1] != 0) return 0;
    if (family == 4 && probe_checksum(packet, length)) return 0;
    return !memcmp(packet + 4, request + 4, 12);
}

int64_t ironwood_tcp_reachable(int32_t family, int32_t a, int32_t b, int32_t c, int32_t d, int32_t scope,
        int32_t source_family, int32_t sa, int32_t sb, int32_t sc, int32_t sd, int32_t source_scope,
        int32_t interface_index, int32_t ttl, int32_t timeout) {
    if ((family != 4 && family != 6) || ttl < 0 || timeout < 0) return host_result(-1, EINVAL);
    if (source_family && source_family != family) return 0;
    int64_t start = probe_clock();
    if (start < 0) return host_result(-1, errno);
    int64_t deadline = start + (int64_t) timeout * 1000000;
    struct sockaddr_storage destination, source;
    if (family == 6 && !scope && interface_index > 0 && ((uint32_t) a >> 22) == 1018) scope = interface_index;
    socklen_t size = probe_address(&destination, family, a, b, c, d, scope, 0);
    socklen_t source_size = source_family
            ? probe_address(&source, source_family, sa, sb, sc, sd, source_scope, 0) : 0;
    int descriptor = socket(family == 4 ? AF_INET : AF_INET6, SOCK_RAW,
            family == 4 ? IPPROTO_ICMP : IPPROTO_ICMPV6);
    if (descriptor < 0) {
        int error = errno;
        return probe_unavailable(error) ? probe_tcp(family, &destination, size, &source, source_size, ttl, deadline)
                : probe_error(error);
    }
    if (probe_prepare(descriptor, family, ttl, &source, source_size) < 0) {
        int error = errno;
        close(descriptor);
        return probe_error(error);
    }
    unsigned char request[16] = {0};
    request[0] = family == 4 ? 8 : 128;
    unsigned int id = (unsigned int) getpid();
    request[4] = (unsigned char) (id >> 8);
    request[5] = (unsigned char) id;
    request[7] = 1;
    memcpy(request + 8, &start, sizeof(start));
    if (family == 4) {
        uint16_t sum = probe_checksum(request, sizeof(request));
        request[2] = (unsigned char) (sum >> 8);
        request[3] = (unsigned char) sum;
    }
    int64_t result = 0;
    _Bool fallback = 0;
    for (;;) {
        ssize_t sent = sendto(descriptor, request, sizeof(request), 0, (const struct sockaddr *) &destination, size);
        if (sent == (ssize_t) sizeof(request)) break;
        int error = sent < 0 ? errno : EIO;
        if (error == EINTR) {
            int64_t now = probe_clock();
            if (now < 0) { result = probe_error(errno); goto done; }
            if (now < deadline) continue;
        } else if (error == EAGAIN || error == EWOULDBLOCK) {
            int ready = probe_wait(descriptor, POLLOUT, deadline);
            if (ready > 0) continue;
            if (ready < 0) result = probe_error(errno);
        } else if (probe_unavailable(error)) fallback = 1;
        else result = probe_error(error);
        goto done;
    }
    for (;;) {
        // First attempt is nonblocking even for timeout zero. A rejected packet
        // cannot extend the deadline or turn into a positive result.
        unsigned char packet[256];
        struct sockaddr_storage sender;
        socklen_t sender_size = sizeof(sender);
        memset(&sender, 0, sizeof(sender));
        ssize_t received = recvfrom(descriptor, packet, sizeof(packet), 0, (struct sockaddr *) &sender, &sender_size);
        if (received >= 0) {
            if (probe_matches(family, &destination, &sender, request, packet, (size_t) received)) { result = 1; break; }
            int64_t now = probe_clock();
            if (now < 0) { result = probe_error(errno); break; }
            if (now >= deadline) break;
            continue;
        }
        int error = errno;
        if (error != EINTR && error != EAGAIN && error != EWOULDBLOCK) { result = probe_error(error); break; }
        int ready = probe_wait(descriptor, POLLIN, deadline);
        if (ready > 0) continue;
        if (ready < 0) result = probe_error(errno);
        break;
    }
done:
    close(descriptor);
    return fallback ? probe_tcp(family, &destination, size, &source, source_size, ttl, deadline) : result;
}
