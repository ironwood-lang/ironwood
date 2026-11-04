// SPDX-License-Identifier: MIT OR Apache-2.0
// Original, isolated qualification harness. This is not the Ironwood runtime.
#include <assert.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "gc.h"
#include "gc_typed.h"

typedef struct Node {
    struct Node *left;
    struct Node *right;
    uint64_t value;
    uint64_t noise;
    uint64_t padding[4];
} Node;

static GC_descr node_descriptor;
static volatile uint64_t sink;
static unsigned collect_events;
static int collecting;
static unsigned unexpected_events;
static void **roots;

static uint64_t now_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000000 + (uint64_t)t.tv_nsec;
}

static void collection_event(GC_EventType event) {
    if (event == GC_EVENT_START) {
        ++collect_events;
        if (!collecting) ++unexpected_events;
    }
}

static void *oom(size_t size) { (void)size; return NULL; }

static void initialize(void) {
    GC_INIT();
    GC_set_on_collection_event(collection_event);
    GC_set_oom_fn(oom);
    GC_disable();
    GC_word bitmap[GC_BITMAP_SIZE(Node)] = {0};
    GC_set_bit(bitmap, GC_WORD_OFFSET(Node, left));
    GC_set_bit(bitmap, GC_WORD_OFFSET(Node, right));
    node_descriptor = GC_make_descriptor(bitmap, GC_WORD_LEN(Node));
}

void explicit_collect(void) {
    collecting = 1;
    GC_enable();
    GC_gcollect();
    GC_disable();
    collecting = 0;
}

__attribute__((noinline)) static Node *new_node(int allocator) {
    Node *p = allocator == 0 ? calloc(1, sizeof(Node))
        : allocator == 1 ? GC_malloc(sizeof(Node))
        : GC_malloc_explicitly_typed(sizeof(Node), node_descriptor);
    if (p == NULL) { fprintf(stderr, "unexpected allocation failure\n"); exit(2); }
    return p;
}

__attribute__((noinline)) uint64_t pooled_mutate(Node **pool, uint64_t n) {
    uint64_t sum = 0;
    for (uint64_t i = 0; i < n; ++i) {
        Node *p = pool[(i * 17) & 4095];
        Node *q = pool[(i * 13 + 5) & 4095];
        p->left = q;
        p->right = q->left;
        p->value += i ^ q->value;
        sum += p->value;
    }
    return sum;
}

__attribute__((noinline)) uint64_t rare_allocate(Node **pool, uint64_t n, int allocator) {
    uint64_t sum = 0;
    for (uint64_t i = 0; i < n; ++i) {
        Node *p = pool[(i * 17) & 4095];
        Node *q = pool[(i * 13 + 5) & 4095];
        p->left = q;
        if ((i & 4095) == 0) {
            Node *fresh = new_node(allocator);
            fresh->value = i;
            p->right = fresh;
        }
        p->value += i ^ q->value;
        sum += p->value;
    }
    return sum;
}

static void bench(int allocator, int rare, uint64_t n) {
    Node **pool = GC_malloc_uncollectable(4096 * sizeof(Node *));
    for (size_t i = 0; i < 4096; ++i) { pool[i] = new_node(allocator); pool[i]->value = i; }
    sink = pooled_mutate(pool, 2000000);
    unsigned before = collect_events;
    uint64_t samples[31];
    uint64_t checksum = 0;
    for (size_t s = 0; s < 31; ++s) {
        uint64_t t = now_ns();
        checksum += rare ? rare_allocate(pool, n, allocator) : pooled_mutate(pool, n);
        samples[s] = now_ns() - t;
    }
    printf("{\"case\":\"%s\",\"allocator\":%d,\"iterations\":%" PRIu64 ",\"gc_events\":%u,\"checksum\":%" PRIu64 ",\"ns\":[", rare ? "rare" : "pooled", allocator, n, collect_events - before, checksum);
    for (size_t s = 0; s < 31; ++s) printf("%s%" PRIu64, s ? "," : "", samples[s]);
    printf("]}\n");
    sink = checksum;
    GC_reachable_here(pool);
}

__attribute__((noinline)) static void generate_cycles(size_t count) {
    for (size_t i = 0; i < count; i += 2) {
        Node *a = new_node(2), *b = new_node(2);
        a->left = b; b->left = a;
    }
}

static void cycles(void) {
    printf("{\"case\":\"cycles\",\"heaps\":[");
    for (size_t round = 0; round < 12; ++round) {
        generate_cycles(500000);
        explicit_collect();
        printf("%s%zu", round ? "," : "", GC_get_heap_size());
    }
    printf("],\"collections\":%u,\"unexpected\":%u}\n", collect_events, unexpected_events);
}

// Encode addresses outside the collector so that integers are the only roots.
static uint64_t *integer_roots;
__attribute__((noinline)) static void make_integer_roots(int typed, size_t count) {
    integer_roots = calloc(count, sizeof(uint64_t));
    GC_add_roots(integer_roots, integer_roots + count);
    for (size_t i = 0; i < count; ++i) {
        Node *p = new_node(typed ? 2 : 1);
        p->value = i;
        integer_roots[i] = (uintptr_t)p;
    }
}

static void integer_retention(void) {
    make_integer_roots(1, 500000);
    explicit_collect();
    size_t retained = GC_get_heap_size() - GC_get_free_bytes();
    GC_remove_roots(integer_roots, integer_roots + 500000);
    memset(integer_roots, 0, 500000 * sizeof(uint64_t));
    explicit_collect();
    size_t cleared = GC_get_heap_size() - GC_get_free_bytes();
    printf("{\"case\":\"integer_roots\",\"retained_bytes\":%zu,\"after_clear_bytes\":%zu}\n", retained, cleared);
}

__attribute__((noinline)) static void make_numeric_holders(int typed, size_t count) {
    roots = GC_malloc_uncollectable(count * sizeof(void *));
    for (size_t i = 0; i < count; ++i) {
        Node *target = new_node(2);
        Node *holder = new_node(typed ? 2 : 1);
        holder->noise = (uintptr_t)target;
        roots[i] = holder;
    }
}

static void heap_retention(int typed) {
    make_numeric_holders(typed, 300000);
    explicit_collect();
    struct GC_prof_stats_s stats;
    GC_get_prof_stats(&stats, sizeof(stats));
    printf("{\"case\":\"numeric_fields\",\"typed\":%d,\"heap_bytes\":%zu,\"free_bytes\":%zu,\"used_block_bytes\":%zu,\"reclaimed_bytes\":%lu}\n", typed, GC_get_heap_size(), GC_get_free_bytes(), GC_get_heap_size()-GC_get_free_bytes(), (unsigned long)stats.bytes_reclaimed_since_gc);
    GC_reachable_here(roots);
}

static void exhaustion(int heuristic_only) {
    GC_set_max_heap_size(32 * 1024 * 1024);
    GC_set_max_retries(2);
    if (heuristic_only) { GC_enable(); GC_set_disable_automatic_collection(1); }
    unsigned before = collect_events;
    unsigned unexpected_before = unexpected_events;
    size_t count = 0;
    for (; count < 100000; ++count) {
        void *p = GC_malloc_atomic(64 * 1024);
        if (!p) break;
        ((volatile unsigned char *)p)[0] = (unsigned char)count;
    }
    printf("{\"case\":\"exhaustion\",\"heuristic_only\":%d,\"allocations\":%zu,\"gc_events\":%u,\"outside_collect\":%u,\"null_returned\":%s}\n", heuristic_only, count, collect_events-before, unexpected_events-unexpected_before, count<100000 ? "true" : "false");
}

__attribute__((noinline)) static void make_sparse_heap(void) {
    size_t count = 1000000;
    roots = GC_malloc_uncollectable((count / 16 + 1) * sizeof(void *));
    for (size_t i = 0; i < count; ++i) {
        Node *p = new_node(2);
        if ((i & 15) == 0) roots[i / 16] = p;
    }
}

static void sweep(void) {
    make_sparse_heap();
    uint64_t t = now_ns();
    explicit_collect();
    uint64_t collect_ns = now_ns() - t;
    struct GC_prof_stats_s before, after;
    GC_get_prof_stats(&before, sizeof(before));
    t = now_ns();
    for (size_t i = 0; i < 500000; ++i) {
        Node *p = new_node(2); p->value = i; sink += p->value;
    }
    uint64_t allocate_ns = now_ns() - t;
    GC_get_prof_stats(&after, sizeof(after));
    printf("{\"case\":\"sweep\",\"collect_ns\":%" PRIu64 ",\"allocate_ns\":%" PRIu64 ",\"reclaimed_at_return\":%lu,\"reclaimed_during_allocation\":%lu,\"unexpected\":%u}\n", collect_ns, allocate_ns, (unsigned long)before.bytes_reclaimed_since_gc, (unsigned long)(after.bytes_reclaimed_since_gc-before.bytes_reclaimed_since_gc), unexpected_events);
}

extern int llvm_root_tests(void);
#ifndef LLVM_TESTS
int llvm_root_tests(void) { return 0; }
#endif

int main(int argc, char **argv) {
    initialize();
    if (argc < 2) return 2;
    if (!strcmp(argv[1], "bench")) bench(atoi(argv[2]), atoi(argv[3]), strtoull(argv[4], NULL, 10));
    else if (!strcmp(argv[1], "cycles")) cycles();
    else if (!strcmp(argv[1], "integer")) integer_retention();
    else if (!strcmp(argv[1], "heap")) heap_retention(atoi(argv[2]));
    else if (!strcmp(argv[1], "oom")) exhaustion(atoi(argv[2]));
    else if (!strcmp(argv[1], "sweep")) sweep();
    else if (!strcmp(argv[1], "llvm")) { int r = llvm_root_tests(); printf("{\"case\":\"llvm_roots\",\"result\":%d}\n", r); return r; }
    else return 2;
    return unexpected_events ? 3 : 0;
}
