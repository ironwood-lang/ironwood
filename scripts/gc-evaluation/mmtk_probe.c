// SPDX-License-Identifier: MIT OR Apache-2.0
// Fixed-layout collector experiment. Exact roots are explicitly supplied here;
// this does not implement Ironwood's eventual native stack-map integration.
#include <assert.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef struct Node {
    struct Node *left, *right;
    uint64_t value, noise, padding[4];
} Node;
extern void mm_init(int);
extern uintptr_t mm_new_node(void);
extern void mm_set_roots(uintptr_t, size_t);
extern void mm_collect(void);
extern size_t mm_used_bytes(void);
extern size_t mm_collections(void);
extern _Bool mm_is_live(uintptr_t);
static volatile uint64_t sink;
static uint64_t now_ns(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000000 + (uint64_t)t.tv_nsec;
}
__attribute__((noinline)) static Node *new_node(void) {
    Node *p = (Node *)mm_new_node(); assert(p); return p;
}
__attribute__((noinline)) uint64_t pooled_mutate(Node **pool, uint64_t n) {
    uint64_t sum = 0;
    for (uint64_t i = 0; i < n; ++i) {
        Node *p = pool[(i * 17) & 4095];
        Node *q = pool[(i * 13 + 5) & 4095];
        p->left = q; p->right = q->left;
        p->value += i ^ q->value; sum += p->value;
    }
    return sum;
}
__attribute__((noinline)) uint64_t rare_allocate(Node **pool, uint64_t n) {
    uint64_t sum = 0;
    for (uint64_t i = 0; i < n; ++i) {
        Node *p = pool[(i * 17) & 4095];
        Node *q = pool[(i * 13 + 5) & 4095];
        p->left = q;
        if ((i & 4095) == 0) { Node *fresh = new_node(); fresh->value = i; p->right = fresh; }
        p->value += i ^ q->value; sum += p->value;
    }
    return sum;
}
static void bench(int rare, uint64_t n) {
    Node **pool = calloc(4096, sizeof(Node *)); assert(pool);
    for (size_t i = 0; i < 4096; ++i) { pool[i] = new_node(); pool[i]->value = i; }
    sink = pooled_mutate(pool, 2000000);
    size_t before = mm_collections();
    uint64_t checksum = 0, samples[31];
    for (size_t s = 0; s < 31; ++s) {
        uint64_t t = now_ns();
        checksum += rare ? rare_allocate(pool, n) : pooled_mutate(pool, n);
        samples[s] = now_ns() - t;
    }
    sink = checksum;
    printf("{\"case\":\"%s\",\"iterations\":%" PRIu64 ",\"gc_events\":%zu,\"checksum\":%" PRIu64 ",\"ns\":[", rare ? "rare" : "pooled", n, mm_collections()-before, checksum);
    for (size_t s = 0; s < 31; ++s) printf("%s%" PRIu64, s ? "," : "", samples[s]);
    puts("]}");
}
static void qualify(void) {
    Node **roots = calloc(300000, sizeof(Node *)); assert(roots);
    for (size_t i = 0; i < 300000; ++i) {
        Node *target = new_node(), *holder = new_node();
        target->value = i; holder->noise = (uintptr_t)target; holder->value = i;
        roots[i] = holder;
    }
    uintptr_t dead = (uintptr_t)roots[0]->noise;
    mm_set_roots((uintptr_t)roots, 300000);
    size_t before = mm_used_bytes(); mm_collect();
    size_t after = mm_used_bytes();
    assert(!mm_is_live(dead));
    assert(roots[299999]->value == 299999);
    size_t real_count = 0;
    for (size_t i = 0; i < 300000; ++i) real_count += mm_is_live((uintptr_t)roots[i]);
    printf("{\"case\":\"numeric_fields\",\"used_before\":%zu,\"used_after\":%zu,\"numeric_target_live\":false,\"live_holders\":%zu}\n", before, after, real_count);
    assert(real_count == 300000);
    // A real reference retains the same kind of object that a numeric field does not.
    Node *live = new_node(); live->value = 1234; roots[0]->left = live;
    uintptr_t live_address = (uintptr_t)live;
    mm_collect(); assert(mm_is_live(live_address)); assert(roots[0]->left->value == 1234);
    roots[0]->left = NULL;
    mm_collect(); assert(!mm_is_live(live_address));
    mm_set_roots(0, 0);
    printf("{\"case\":\"cycles\",\"used_bytes\":[");
    for (size_t round = 0; round < 12; ++round) {
        for (size_t i = 0; i < 500000; i += 2) {
            Node *a = new_node(), *b = new_node(); a->left = b; b->left = a;
        }
        mm_collect(); printf("%s%zu", round ? "," : "", mm_used_bytes());
    }
    printf("],\"collections\":%zu}\n", mm_collections());
}
int main(int argc, char **argv) {
    if (argc < 3) return 2;
    mm_init(atoi(argv[1]));
    if (!strcmp(argv[2], "qualify")) qualify();
    else if (!strcmp(argv[2], "bench")) bench(atoi(argv[3]), strtoull(argv[4], NULL, 10));
    else return 2;
    return 0;
}
