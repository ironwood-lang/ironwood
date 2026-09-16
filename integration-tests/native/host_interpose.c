// SPDX-License-Identifier: MIT OR Apache-2.0

/* Synthetic interfaces and port-7 completion at the real executable's syscall
 * boundary. No packets leave the process. All native storage is counted. */
#define _GNU_SOURCE 1
#include <arpa/inet.h>
#include <assert.h>
#include <dlfcn.h>
#include <errno.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <poll.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>
#if defined(__APPLE__)
#include <net/if_dl.h>
#else
#include <netpacket/packet.h>
#endif
#if defined(HOST_NATIVE_CONTRACTS)
#define INTERPOSE(replacement, original)
#define REAL_SOCKET socket
#define REAL_CLOSE close
#define REAL_IOCTL ioctl
#elif defined(__APPLE__)
#define INTERPOSE(replacement, original) \
    __attribute__((used)) static struct { const void *replacement; const void *original; } \
    pair_##original __attribute__((section("__DATA,__interpose"))) = { \
        (const void *) (uintptr_t) &replacement, (const void *) (uintptr_t) &original }
#define REAL_SOCKET socket
#define REAL_CLOSE close
#define REAL_IOCTL ioctl
#else
#define INTERPOSE(replacement, original)
#define test_getifaddrs getifaddrs
#define test_freeifaddrs freeifaddrs
#define test_if_nametoindex if_nametoindex
#define test_socket socket
#define test_close close
#define test_ioctl ioctl
#define test_connect connect
#define test_getsockopt getsockopt
#define test_bind bind
#define test_poll poll
#define REAL_SOCKET ((int (*)(int, int, int)) dlsym(RTLD_NEXT, "socket"))
#define REAL_CLOSE ((int (*)(int)) dlsym(RTLD_NEXT, "close"))
#define REAL_IOCTL ((int (*)(int, unsigned long, ...)) dlsym(RTLD_NEXT, "ioctl"))
#endif

struct interface_block {
    struct ifaddrs entries[6];
    struct sockaddr_storage addresses[6], masks[6], broadcasts[6];
};
static int captures, releases, queries, flag_queries, sockets, closes, probes, binds, ioctls;
static unsigned char descriptors[4096];
static int mode(const char *name) {
    const char *value = getenv("IRONWOOD_TEST_HOST_MODE");
    return value && !strcmp(value, name);
}
static void v4(struct sockaddr_storage *storage, unsigned int bits) {
    struct sockaddr_in *address = (struct sockaddr_in *) storage;
    address->sin_family = AF_INET;
    address->sin_addr.s_addr = htonl(bits);
}
static void v6(struct sockaddr_storage *storage, int scope, int mask) {
    struct sockaddr_in6 *address = (struct sockaddr_in6 *) storage;
    address->sin6_family = AF_INET6;
    address->sin6_scope_id = (unsigned) scope;
    if (mask) memset(address->sin6_addr.s6_addr, 255, 8);
    else {
        address->sin6_addr.s6_addr[0] = 0xfe;
        address->sin6_addr.s6_addr[1] = 0x80;
        address->sin6_addr.s6_addr[15] = 1;
    }
}
int test_getifaddrs(struct ifaddrs **result) {
    queries++;
    *result = NULL;
    if (mode("query_error")) { errno = EIO; return -1; }
    if (mode("query_oom")) { errno = ENOMEM; return -1; }
    if (mode("empty")) return 0;
    struct interface_block *block = calloc(1, sizeof(*block));
    if (!block) { errno = ENOMEM; return -1; }
    static char *names[] = {"beta0", "alpha0:1", "alpha0", "alpha0", "alpha0", "beta0"};
    for (int i = 0; i < 6; i++) {
        struct ifaddrs *entry = &block->entries[i];
        entry->ifa_name = names[i];
        entry->ifa_addr = (struct sockaddr *) &block->addresses[i];
        entry->ifa_netmask = (struct sockaddr *) &block->masks[i];
        entry->ifa_flags = IFF_UP | IFF_RUNNING | IFF_MULTICAST;
        if (i == 0 || i == 5) entry->ifa_flags |= IFF_LOOPBACK;
        if (i < 5) entry->ifa_next = &block->entries[i + 1];
        if (i == 2) { v6(&block->addresses[i], 7, 0); v6(&block->masks[i], 0, 1); }
        else if (i < 4) {
            v4(&block->addresses[i], i == 0 ? 0x7f000001 : i == 1 ? 0xc0000202 : 0xc0000201);
            v4(&block->masks[i], i == 0 ? 0xff000000 : 0xffffff00);
            if (i) {
                entry->ifa_flags |= IFF_BROADCAST;
                entry->ifa_broadaddr = (struct sockaddr *) &block->broadcasts[i];
                v4(&block->broadcasts[i], 0xc00002ff);
            }
        } else {
            unsigned char *hardware;
            int hardware_length = mode("long_hardware") ? 20 : 6;
#if defined(__APPLE__)
            struct sockaddr_dl *link = (struct sockaddr_dl *) entry->ifa_addr;
            link->sdl_family = AF_LINK;
            link->sdl_alen = (unsigned char) hardware_length;
            hardware = (unsigned char *) LLADDR(link);
#else
            struct sockaddr_ll *link = (struct sockaddr_ll *) entry->ifa_addr;
            link->sll_family = AF_PACKET;
            link->sll_halen = (unsigned char) hardware_length;
            hardware = (unsigned char *) &block->addresses[i] + offsetof(struct sockaddr_ll, sll_addr);
#endif
            for (int n = 0; n < hardware_length; n++) hardware[n] = (unsigned char) (2 + n);
        }
        if (mode("no_addresses") && i < 4) entry->ifa_addr = NULL;
    }
    captures++;
    *result = block->entries;
    return 0;
}
void test_freeifaddrs(struct ifaddrs *source) {
    if (source) { releases++; free(source); }
    errno = EBADF;
}
unsigned int test_if_nametoindex(const char *name) {
    return !strcmp(name, "alpha0") ? 7 : !strcmp(name, "beta0") ? 8 : 0;
}
int test_socket(int family, int type, int protocol) {
    if (type == SOCK_RAW) { errno = EPERM; return -1; }
    int descriptor = REAL_SOCKET(family, type, protocol);
    if (descriptor >= 0) {
        assert(descriptor < (int) sizeof(descriptors) && !descriptors[descriptor]);
        descriptors[descriptor] = 1;
        sockets++;
    }
    return descriptor;
}
int test_close(int descriptor) {
    if (descriptor >= 0 && descriptor < (int) sizeof(descriptors) && descriptors[descriptor]) {
        descriptors[descriptor] = 0;
        closes++;
    }
    return REAL_CLOSE(descriptor);
}
int test_ioctl(int descriptor, unsigned long operation, ...) {
    va_list args;
    va_start(args, operation);
    struct ifreq *request = va_arg(args, struct ifreq *);
    va_end(args);
    if (descriptor < 0 || descriptor >= (int) sizeof(descriptors) || !descriptors[descriptor])
        return REAL_IOCTL(descriptor, operation, request);
    ioctls++;
    if (mode("live_error")) { errno = ENODEV; return -1; }
    assert(!strcmp(request->ifr_name, "alpha0") || !strcmp(request->ifr_name, "beta0") || !strcmp(request->ifr_name, "alpha0:1"));
    if (operation == SIOCGIFMTU) request->ifr_mtu = 1500;
    else {
        assert(operation == SIOCGIFFLAGS);
        request->ifr_flags = (short) (IFF_UP | IFF_RUNNING | IFF_MULTICAST);
        if (!strcmp(request->ifr_name, "beta0")) request->ifr_flags |= IFF_LOOPBACK;
        if (mode("changing") && flag_queries++ % 2) request->ifr_flags &= ~IFF_UP;
    }
    return 0;
}
int test_connect(int descriptor, const struct sockaddr *address, socklen_t length) {
    assert(descriptors[descriptor] && length >= sizeof(struct sockaddr_in));
    unsigned int port = address->sa_family == AF_INET ? ((const struct sockaddr_in *) address)->sin_port
            : ((const struct sockaddr_in6 *) address)->sin6_port;
    assert(ntohs((uint16_t) port) == 7);
    probes++;
    if (mode("probe_error")) { errno = EIO; return -1; }
    errno = mode("pending") ? EINPROGRESS : ECONNREFUSED;
    return -1;
}
int test_getsockopt(int descriptor, int level, int option, void *value, socklen_t *length) {
    assert(descriptors[descriptor] && level == SOL_SOCKET && option == SO_ERROR && *length == sizeof(int));
    *(int *) value = ECONNREFUSED;
    return 0;
}
int test_bind(int descriptor, const struct sockaddr *address, socklen_t length) {
    assert(descriptors[descriptor] && length >= sizeof(struct sockaddr_in));
    assert(address->sa_family == AF_INET || address->sa_family == AF_INET6);
    binds++;
    return 0;
}
int test_poll(struct pollfd *fds, nfds_t count, int timeout) {
    assert(count == 1 && descriptors[fds->fd] && timeout > 0);
    fds->revents = fds->events;
    return 1;
}
INTERPOSE(test_getifaddrs, getifaddrs);
INTERPOSE(test_freeifaddrs, freeifaddrs);
INTERPOSE(test_if_nametoindex, if_nametoindex);
INTERPOSE(test_socket, socket);
INTERPOSE(test_close, close);
INTERPOSE(test_ioctl, ioctl);
INTERPOSE(test_connect, connect);
INTERPOSE(test_getsockopt, getsockopt);
INTERPOSE(test_bind, bind);
INTERPOSE(test_poll, poll);

__attribute__((destructor)) static void report(void) {
    fprintf(stderr, "HOST_COUNTS queries=%d captured=%d released=%d sockets=%d closed=%d probes=%d binds=%d ioctls=%d\n",
            queries, captures, releases, sockets, closes, probes, binds, ioctls);
    if (captures != releases || sockets != closes) abort();
}
