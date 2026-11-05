// SPDX-License-Identifier: MIT OR Apache-2.0
// Exact GC5 native-graph and weak emergency ownership controls. No ambient roots.
#include "ironwood_gc_engine.h"
#include "gc_mark.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct node { const struct iw_gc_type *type; void *first, *next; uintptr_t number; };
struct leaf { const struct iw_gc_type *type; uintptr_t number; };
#define WIDTH 65536
struct wide { const struct iw_gc_type *type; void *children[WIDTH]; uintptr_t number; };
static struct node native_first, native_second;
static struct wide native_wide;
static unsigned native_flag;
static void *slots[8];
static size_t slot_count;
static const struct iw_gc_type *node_type, *leaf_type;
extern int GC_print_stats; /* Pinned collector diagnostic, as in the GC2 overflow control. */

static int append(uintptr_t value) {
    if (!value) return 1;
    if (slot_count == sizeof(slots) / sizeof(slots[0])) return 0;
    slots[slot_count++] = (void *)value;
    return 1;
}

static void collect(void *base, int native) {
    iw_gc_prepare_native_graphs();
    memset(slots, 0, sizeof(slots)); slot_count = 0;
    if (native) assert(iw_gc_expand_native_roots(base, append));
    else if (base) slots[slot_count++] = base;
    assert(iw_gc_roots(slot_count ? slots : NULL, slot_count));
    assert(iw_gc_collect());
    assert(iw_gc_roots(NULL, 0));
    memset(slots, 0, sizeof(slots));
}

static struct leaf *new_leaf(uintptr_t number) {
    struct leaf *value = iw_gc_new(leaf_type, 0);
    assert(value && value->number == 0); value->number = number; return value;
}

static void basic(void) {
    struct node *parent = iw_gc_new(node_type, 0); assert(parent);
    native_first.first = new_leaf(17);
    native_first.next = &native_second;
    native_second.first = new_leaf(29);
    native_second.next = &native_first;
    native_first.number = (uintptr_t)new_leaf(99); // An address-shaped integer is not a reference.
    native_flag = 1;
    parent->first = &native_first;
    for (int pass = 0; pass < 3; ++pass) {
        collect(parent, 0);
        assert(iw_gc_live_count() == 3 && native_flag == 1);
        assert(((struct leaf *)native_first.first)->number == 17);
        assert(((struct leaf *)native_second.first)->number == 29);
    }
    collect(NULL, 0);
    assert(iw_gc_live_count() == 0 && !native_first.first && !native_first.next
            && !native_second.first && !native_second.next && !native_flag);
    native_first.first = new_leaf(41);
    collect(&native_first, 1);
    assert(iw_gc_live_count() == 1 && ((struct leaf *)native_first.first)->number == 41);
    collect(NULL, 0);
    assert(iw_gc_live_count() == 0 && !native_first.first);
    puts("{\"case\":\"native_graphs\",\"cycles\":true,\"weak_state_cleared\":true,\"numeric_target_reclaimed\":true}");
}

static void overflow(void) {
    struct node *parent = iw_gc_new(node_type, 0); assert(parent);
    parent->first = &native_wide;
    for (size_t i = 0; i < WIDTH; ++i) {
        struct node *child = iw_gc_new(node_type, 0); assert(child);
        child->first = new_leaf(i + 1);
        native_wide.children[i] = child;
    }
    native_wide.number = (uintptr_t)new_leaf(99);
    GC_set_log_fd(2);
    GC_print_stats = 1;
    for (int pass = 0; pass < 2; ++pass) {
        collect(parent, 0);
        assert(iw_gc_live_count() == 1 + 2 * WIDTH);
        for (size_t i = 0; i < WIDTH; ++i) {
            struct node *child = native_wide.children[i];
            assert(((struct leaf *)child->first)->number == i + 1);
        }
    }
    GC_print_stats = 0;
    collect(NULL, 0);
    assert(iw_gc_live_count() == 0);
    for (size_t i = 0; i < WIDTH; ++i) assert(!native_wide.children[i]);
    puts("{\"case\":\"native_overflow\",\"exact_live_before_drop\":131073,\"live_after_drop\":0}");
}

int main(int argc, char **argv) {
    assert(argc == 2 && iw_gc_init(iw_gc_configuration(), 64 * 1024 * 1024, 0));
    const size_t refs[] = {offsetof(struct node, first), offsetof(struct node, next)};
    const struct iw_gc_layout node_layout = {1, IW_GC_FIXED, 1, 0, sizeof(struct node), 2, refs};
    const struct iw_gc_layout native_layout = {1, IW_GC_FIXED, 0, 0, sizeof(struct node), 2, refs};
    const struct iw_gc_layout leaf_layout = {1, IW_GC_FIXED, 1, 0, sizeof(struct leaf), 0, NULL};
    node_type = iw_gc_describe(&node_layout); leaf_type = iw_gc_describe(&leaf_layout);
    native_first.type = native_second.type = iw_gc_describe(&native_layout);
    size_t *offsets = calloc(WIDTH, sizeof(*offsets)); assert(offsets);
    for (size_t i = 0; i < WIDTH; ++i) offsets[i] = offsetof(struct wide, children) + i * sizeof(void *);
    const struct iw_gc_layout wide_layout = {1, IW_GC_FIXED, 0, 0, sizeof(struct wide), WIDTH, offsets};
    native_wide.type = iw_gc_describe(&wide_layout); free(offsets);
    assert(node_type && leaf_type && native_first.type && native_wide.type);
    void *const bases[] = {&native_first, &native_second, &native_wide};
    assert(iw_gc_register_immortals(bases, 3));
    assert(iw_gc_weak_native(&native_first) && iw_gc_weak_native(&native_second) && iw_gc_weak_native(&native_wide));
    assert(iw_gc_weak_native_slot(&native_first, &native_flag, sizeof(native_flag)));
    if (!strcmp(argv[1], "basic")) basic();
    else { assert(!strcmp(argv[1], "overflow")); overflow(); }
    return 0;
}
