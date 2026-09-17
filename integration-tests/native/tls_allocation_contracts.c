/* SPDX-License-Identifier: MIT OR Apache-2.0 */
/* Test-only allocator hooks surround the real adapter and pinned OpenSSL. */
#include <openssl/ssl.h>
#include <openssl/err.h>
#include <openssl/pem.h>
#include <openssl/x509v3.h>
#include <openssl/provider.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <assert.h>
#include <sys/socket.h>
#include <unistd.h>
#include <fcntl.h>

union allocation_header {
    max_align_t alignment;
    struct { size_t size, generation; const char *file; int line;
             union allocation_header *previous, *next; } info;
};
static union allocation_header *allocations;
static size_t generation;
static size_t live, attempts, failed_at;
static void *tracked_malloc(size_t size) {
    attempts++;
    if (failed_at && attempts == failed_at) return NULL;
    union allocation_header *block = malloc(sizeof(*block) + size);
    if (!block) return NULL;
    block->info.size = size;
    block->info.generation = generation;
    block->info.file = "adapter";
    block->info.line = 0;
    block->info.previous = NULL;
    block->info.next = allocations;
    if (allocations) allocations->info.previous = block;
    allocations = block;
    live++;
    return block + 1;
}
static void tracked_free(void *pointer) {
    if (!pointer) return;
    assert(live > 0);
    live--;
    union allocation_header *block = (union allocation_header *)pointer - 1;
    if (block->info.previous) block->info.previous->info.next = block->info.next;
    else allocations = block->info.next;
    if (block->info.next) block->info.next->info.previous = block->info.previous;
    free(block);
}
static void *tracked_realloc(void *pointer, size_t size) {
    if (!size) { tracked_free(pointer); return NULL; }
    void *replacement = tracked_malloc(size);
    if (!replacement) return NULL;
    if (pointer) {
        size_t old = ((union allocation_header *)pointer - 1)->info.size;
        memcpy(replacement, pointer, old < size ? old : size);
        tracked_free(pointer);
    }
    return replacement;
}
static void *tracked_calloc(size_t count, size_t size) {
    if (size && count > SIZE_MAX / size) return NULL;
    void *result = tracked_malloc(count * size);
    if (result) memset(result, 0, count * size);
    return result;
}
static void *crypto_malloc(size_t size, const char *file, int line) {
    void *result = tracked_malloc(size);
    if (result) {
        union allocation_header *block = (union allocation_header *)result - 1;
        block->info.file = file; block->info.line = line;
    }
    return result;
}
static void *crypto_realloc(void *pointer, size_t size, const char *file, int line) {
    void *result = tracked_realloc(pointer, size);
    if (result) {
        union allocation_header *block = (union allocation_header *)result - 1;
        block->info.file = file; block->info.line = line;
    }
    return result;
}
static void crypto_free(void *pointer, const char *file, int line) {
    (void)file; (void)line; tracked_free(pointer);
}
#define malloc tracked_malloc
#define calloc tracked_calloc
#define free tracked_free
#include "../../runtime/src/ironwood_tls.c"
#undef malloc
#undef calloc
#undef free

static struct ironwood_array *bytes(const char *text) {
    size_t length = strlen(text);
    struct ironwood_array *result = malloc(sizeof(*result) + length);
    result->length = length;
    memcpy(result->data, text, length);
    return result;
}

int main(int argc, char **argv) {
    assert(argc == 3);
    assert(CRYPTO_set_mem_functions(crypto_malloc, crypto_realloc, crypto_free));
    struct ironwood_array *path = bytes(argv[1]);
    struct ironwood_array *identity = bytes("localhost");
    int64_t handle = ironwood_tls_create(path);
    assert(handle > 0);
    assert(sk_X509_OBJECT_num(X509_STORE_get0_objects(SSL_CTX_get_cert_store(connection(handle)->context))) == 1);
    assert(ironwood_tls_close(handle, false) == 0);
    handle = ironwood_tls_create(NULL);
    assert(handle > 0);
    assert(ironwood_tls_close(handle, false) == 0);
    /* OpenSSL keeps reusable error-message buffers in process/thread state.
     * Drain that state only in this allocator test, separately from connection
     * ownership. Production leaves it reusable until OpenSSL process cleanup. */
    OPENSSL_thread_stop();
    size_t baseline = live;
    attempts = 0;
    handle = ironwood_tls_create(path);
    assert(handle > 0);
    assert(ironwood_tls_configure(handle, identity, NULL) == 0);
    int descriptors[2];
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, descriptors) == 0);
    assert(ironwood_tls_attach(handle, descriptors[0]) == 0);
    size_t successful = attempts;
    assert(ironwood_tls_close(handle, false) == 0);
    assert(fcntl(descriptors[0], F_GETFD) >= 0); /* Adapter does not own the socket. */
    close(descriptors[0]); close(descriptors[1]);
    OPENSSL_thread_stop();
    assert(live == baseline);
    size_t failures = 0;
    for (size_t fail = 1; fail <= successful; fail++) {
        attempts = 0; failed_at = fail; generation = fail;
        handle = ironwood_tls_create(path);
        if (handle > 0) {
            int64_t configured = ironwood_tls_configure(handle, identity, NULL);
            if (configured < 0) failures++;
            if (configured == 0) {
                assert(socketpair(AF_UNIX, SOCK_STREAM, 0, descriptors) == 0);
                if (ironwood_tls_attach(handle, descriptors[0]) < 0) failures++;
                assert(ironwood_tls_close(handle, false) == 0);
                assert(fcntl(descriptors[0], F_GETFD) >= 0);
                close(descriptors[0]); close(descriptors[1]);
            } else {
                assert(ironwood_tls_close(handle, false) == 0);
            }
        } else failures++;
        failed_at = 0;
        OPENSSL_thread_stop();
        if (live != baseline) {
            fprintf(stderr, "failure index %zu retained %zu allocations (baseline %zu)\n", fail, live, baseline);
            for (union allocation_header *b = allocations; b; b = b->info.next)
                if (b->info.generation == generation)
                    fprintf(stderr, "retained %zu bytes at %s:%d\n", b->info.size, b->info.file, b->info.line);
            return 1;
        }
    }
    handle = ironwood_tls_create(NULL);
    assert(handle > 0);
    int bundled = sk_X509_OBJECT_num(X509_STORE_get0_objects(SSL_CTX_get_cert_store(connection(handle)->context)));
    assert(bundled == atoi(argv[2]));
    assert(ironwood_tls_close(handle, false) == 0);
    OPENSSL_thread_stop();
    assert(live == baseline);
    printf("TLS_NATIVE allocations=%zu injected=%zu rejected=%zu baseline=%zu bundled=%d custom=1 retained=0\n",
            successful, successful, failures, baseline, bundled);
    free(path); free(identity);
    OPENSSL_cleanup();
    assert(live == 0);
    return 0;
}
