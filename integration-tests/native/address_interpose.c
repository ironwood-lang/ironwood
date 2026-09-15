// SPDX-License-Identifier: MIT OR Apache-2.0

/* Controlled resolver results and native-resource accounting for M2 tests.
 * This instrumentation is never included in the shipped runtime. */
#define _GNU_SOURCE 1
#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <netdb.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#if defined(__APPLE__)
#define INTERPOSE(replacement, original) \
    __attribute__((used)) static struct { const void *replacement; const void *original; } \
    pair_##original __attribute__((section("__DATA,__interpose"))) = { \
        (const void *) (uintptr_t) &replacement, (const void *) (uintptr_t) &original }
#define REAL_GETADDRINFO getaddrinfo
#define REAL_FREEADDRINFO freeaddrinfo
#define REAL_GETNAMEINFO getnameinfo
#define REAL_GETHOSTNAME gethostname
#define REAL_SOCKET socket
#define REAL_CLOSE close
#define REAL_ACCEPT accept
#define REAL_CONNECT connect
#define REAL_SEND send
#define REAL_SETSOCKOPT setsockopt
#else
#define INTERPOSE(replacement, original)
#define test_getaddrinfo getaddrinfo
#define test_freeaddrinfo freeaddrinfo
#define test_getnameinfo getnameinfo
#define test_gethostname gethostname
#define test_socket socket
#define test_close close
#define test_accept accept
#define test_connect connect
#define test_send send
#define test_setsockopt setsockopt
#define REAL_GETADDRINFO ((int (*)(const char *, const char *, const struct addrinfo *, struct addrinfo **)) dlsym(RTLD_NEXT, "getaddrinfo"))
#define REAL_FREEADDRINFO ((void (*)(struct addrinfo *)) dlsym(RTLD_NEXT, "freeaddrinfo"))
#define REAL_GETNAMEINFO ((int (*)(const struct sockaddr *, socklen_t, char *, socklen_t, char *, socklen_t, int)) dlsym(RTLD_NEXT, "getnameinfo"))
#define REAL_GETHOSTNAME ((int (*)(char *, size_t)) dlsym(RTLD_NEXT, "gethostname"))
#define REAL_SOCKET ((int (*)(int, int, int)) dlsym(RTLD_NEXT, "socket"))
#define REAL_CLOSE ((int (*)(int)) dlsym(RTLD_NEXT, "close"))
#define REAL_ACCEPT ((int (*)(int, struct sockaddr *, socklen_t *)) dlsym(RTLD_NEXT, "accept"))
#define REAL_CONNECT ((int (*)(int, const struct sockaddr *, socklen_t)) dlsym(RTLD_NEXT, "connect"))
#define REAL_SEND ((ssize_t (*)(int, const void *, size_t, int)) dlsym(RTLD_NEXT, "send"))
#define REAL_SETSOCKOPT ((int (*)(int, int, int, const void *, socklen_t)) dlsym(RTLD_NEXT, "setsockopt"))
#endif

struct answer_block {
    struct addrinfo entries[6];
    struct sockaddr_storage addresses[6];
    struct answer_block *next;
};
static struct answer_block *live;
static int lookups, reverses, opened, released, changed;
static unsigned char descriptors[4096];
static int sockets_opened, sockets_closed, urgent_calls;

static int tcp_mode(const char *expected) {
    const char *value = getenv("IRONWOOD_TEST_TCP_MODE");
    return value && strcmp(value, expected) == 0;
}

static int remember_descriptor(int descriptor) {
    if (descriptor >= 0) {
        if (descriptor >= (int) sizeof(descriptors) || descriptors[descriptor]) abort();
        descriptors[descriptor] = 1;
        sockets_opened++;
    }
    return descriptor;
}

static int mode(const char *expected) {
    const char *value = getenv("IRONWOOD_TEST_DNS_MODE");
    return value && strcmp(value, expected) == 0;
}

static void make_address(struct answer_block *block, int index, int family, int last) {
    struct addrinfo *entry = &block->entries[index];
    entry->ai_family = family;
    entry->ai_socktype = SOCK_STREAM;
    entry->ai_protocol = IPPROTO_TCP;
    entry->ai_addr = (struct sockaddr *) &block->addresses[index];
    if (family == AF_INET) {
        struct sockaddr_in *address = (struct sockaddr_in *) entry->ai_addr;
        address->sin_family = AF_INET;
        address->sin_addr.s_addr = htonl(0x7f000000U | (unsigned) last);
        entry->ai_addrlen = sizeof(*address);
    } else {
        struct sockaddr_in6 *address = (struct sockaddr_in6 *) entry->ai_addr;
        address->sin6_family = AF_INET6;
        address->sin6_addr.s6_addr[15] = (unsigned char) last;
        entry->ai_addrlen = sizeof(*address);
    }
    if (index) block->entries[index - 1].ai_next = entry;
}

int test_getaddrinfo(const char *name, const char *service, const struct addrinfo *hints, struct addrinfo **result) {
    lookups++;
    if (!name || !strstr(name, ".ironwood.test")) return REAL_GETADDRINFO(name, service, hints, result);
    *result = NULL;
    if (mode("oom")) return EAI_MEMORY;
    if (mode("system_oom")) { errno = ENOMEM; return EAI_SYSTEM; }
    if (mode("empty")) return 0;
    if (strcmp(name, "missing.ironwood.test") == 0) return EAI_NONAME;
    if (strcmp(name, "changing.ironwood.test") == 0 && changed++ % 2 == 1) return EAI_NONAME;
    struct answer_block *block = calloc(1, sizeof(*block));
    if (!block) return EAI_MEMORY;
    block->next = live;
    live = block;
    opened++;
    *result = block->entries;
    if (strcmp(name, "reverse.ironwood.test") == 0) {
        make_address(block, 0, AF_INET, mode("unconfirmed") ? 99 : 42);
    } else if (strcmp(name, "local.ironwood.test") == 0) {
        make_address(block, 0, AF_INET, 43);
    } else if (strcmp(name, "changing.ironwood.test") == 0) {
        make_address(block, 0, AF_INET, changed);
    } else if (mode("v4")) {
        make_address(block, 0, AF_INET, 2);
        make_address(block, 1, AF_INET, 1);
    } else if (mode("v6")) {
        make_address(block, 0, AF_INET6, 2);
        make_address(block, 1, AF_INET6, 1);
    } else {
        make_address(block, 0, AF_INET6, 2);
        make_address(block, 1, AF_INET, 2);
        make_address(block, 2, AF_INET6, 1);
        make_address(block, 3, AF_INET, 1);
        make_address(block, 4, AF_INET, 2);
        make_address(block, 5, AF_INET6, 2);
    }
    return mode("partial_error") ? EAI_FAIL : 0;
}
INTERPOSE(test_getaddrinfo, getaddrinfo);

void test_freeaddrinfo(struct addrinfo *result) {
    for (struct answer_block **slot = &live; *slot; slot = &(*slot)->next) {
        struct answer_block *block = *slot;
        if (block->entries == result) {
            *slot = block->next;
            released++;
            free(block);
            return;
        }
    }
    REAL_FREEADDRINFO(result);
}
INTERPOSE(test_freeaddrinfo, freeaddrinfo);

int test_getnameinfo(const struct sockaddr *address, socklen_t size, char *host,
        socklen_t host_size, char *service, socklen_t service_size, int flags) {
    reverses++;
    if (address->sa_family == AF_INET
            && ntohl(((const struct sockaddr_in *) address)->sin_addr.s_addr) == 0x7f00002aU) {
        const char *name = "reverse.ironwood.test";
        if (host_size <= strlen(name)) return EAI_OVERFLOW;
        strcpy(host, name);
        return 0;
    }
    return REAL_GETNAMEINFO(address, size, host, host_size, service, service_size, flags);
}
INTERPOSE(test_getnameinfo, getnameinfo);

int test_gethostname(char *name, size_t size) {
    if (!getenv("IRONWOOD_TEST_LOCAL_NAME")) return REAL_GETHOSTNAME(name, size);
    const char *value = "local.ironwood.test";
    if (size <= strlen(value)) { errno = ENAMETOOLONG; return -1; }
    strcpy(name, value);
    return 0;
}
INTERPOSE(test_gethostname, gethostname);

int test_socket(int family, int type, int protocol) {
    if (family == AF_INET && getenv("IRONWOOD_TEST_IPV6_ONLY")) { errno = EAFNOSUPPORT; return -1; }
    int result = REAL_SOCKET(family, type, protocol);
    // Native name services can retain process-lifetime datagram connections.
    // The library under test owns only these internet TCP descriptors.
    return (family == AF_INET || family == AF_INET6) && type == SOCK_STREAM
        ? remember_descriptor(result) : result;
}
INTERPOSE(test_socket, socket);

int test_close(int descriptor) {
    int result = REAL_CLOSE(descriptor);
    if (result == 0 && descriptor >= 0 && descriptor < (int) sizeof(descriptors) && descriptors[descriptor]) {
        descriptors[descriptor] = 0;
        sockets_closed++;
    }
    return result;
}
INTERPOSE(test_close, close);

int test_accept(int descriptor, struct sockaddr *address, socklen_t *size) {
    return remember_descriptor(REAL_ACCEPT(descriptor, address, size));
}
INTERPOSE(test_accept, accept);

int test_connect(int descriptor, const struct sockaddr *address, socklen_t size) {
    if (tcp_mode("no-route")) { errno = EHOSTUNREACH; return -1; }
    return REAL_CONNECT(descriptor, address, size);
}
INTERPOSE(test_connect, connect);

ssize_t test_send(int descriptor, const void *data, size_t size, int flags) {
    if (flags & MSG_OOB) {
        urgent_calls++;
        if (urgent_calls == 1 && (tcp_mode("urgent-eintr") || tcp_mode("urgent-would-block"))) {
            errno = tcp_mode("urgent-eintr") ? EINTR : EAGAIN;
            return -1;
        }
    }
    return REAL_SEND(descriptor, data, size, flags);
}
INTERPOSE(test_send, send);

int test_setsockopt(int descriptor, int level, int option, const void *value, socklen_t size) {
    if (tcp_mode("traffic-error") && ((level == IPPROTO_IP && option == IP_TOS)
            || (level == IPPROTO_IPV6 && option == IPV6_TCLASS))) { errno = EACCES; return -1; }
#if defined(SO_REUSEPORT)
    if (tcp_mode("no-reuse-port") && level == SOL_SOCKET && option == SO_REUSEPORT) {
        errno = ENOPROTOOPT;
        return -1;
    }
#endif
    return REAL_SETSOCKOPT(descriptor, level, option, value, size);
}
INTERPOSE(test_setsockopt, setsockopt);

__attribute__((destructor)) static void report(void) {
    fprintf(stderr, "ADDRESS_COUNTS lookup=%d reverse=%d opened=%d released=%d\n", lookups, reverses, opened, released);
    fprintf(stderr, "ADDRESS_TCP opened=%d closed=%d urgent=%d\n", sockets_opened, sockets_closed, urgent_calls);
    if (live || sockets_opened != sockets_closed) abort();
}
