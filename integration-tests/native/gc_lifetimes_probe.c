// SPDX-License-Identifier: MIT OR Apache-2.0
/* GC4 assertions use real managed Throwable graphs and native unwind wrappers.
 * Native-boundary locals are deliberately not implicit roots. */
#include "ironwood_gc_lifetimes.h"
#include "gc.h"
#include "gc_mark.h"
#include "layouts.h"
#include <assert.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#if defined(__APPLE__)
#define IW_SYMBOL(name) "_" name
#else
#define IW_SYMBOL(name) name
#endif
extern void phase(void) __asm__(IW_SYMBOL("ironwood.Main.phase"));
extern void clear(void) __asm__(IW_SYMBOL("ironwood.Main.clear"));
extern void lifetime_clear(void) __asm__(IW_SYMBOL("ironwood.Lifetime.clear"));
extern void *saved(void) __asm__(IW_SYMBOL("ironwood.Lifetime.saved"));
extern int64_t sequence(void) __asm__(IW_SYMBOL("ironwood.Lifetime.sequence"));
extern void *pending(void *) __asm__(IW_SYMBOL("ironwood.Main.pending"));
extern void *pending_yield(void *) __asm__(IW_SYMBOL("ironwood.Main.pendingYield"));
extern struct iw_gc_throwable *gc4_catch_unwind(void *, void *, void *);
extern struct iw_gc_throwable *gc4_catch_nested(void *, void *, void *);
extern struct iw_gc_throwable *gc4_catch_live_wrapper(void *, void *, void *);
extern struct iw_gc_throwable *gc4_catch_nested_wrappers(void *, void *, void *);
extern struct iw_gc_throwable *gc4_rethrow(void *, void *, void *);
extern struct iw_gc_throwable *gc4_published_failure(void *);
extern struct iw_gc_throwable *gc4_published_success(void *);
struct node { const void *type; struct node *link; int64_t value; };
extern void *const iw_gc_static_roots[];
extern const uint64_t iw_gc_static_root_count;
const uint64_t iw_gc_native_root_count = 0;
void *const iw_gc_native_roots[] = {NULL};
const struct iw_gc_type *iw_gc_types[IW_GC_TYPE_COUNT];
static const char *selected;
static size_t resources, released, release_order;
static struct iw_gc_acquisition failure_slots[2];
static struct iw_gc_acquisitions failure_owner = {failure_slots, 0, 2};
static int preparation_failure;
static int unwind_failure;
static size_t wrapper_collections, max_wrappers;

static int enumerate(int (*visit)(uintptr_t)) {
    if (iw_gc_unwind_wrapper_count()) ++wrapper_collections;
    if (iw_gc_unwind_wrapper_count() > max_wrappers) max_wrappers = iw_gc_unwind_wrapper_count();
    return iw_gc_lifetime_owners.enumerate(visit);
}
static const struct iw_gc_owner_protocol probe_owners = {1, 7, 0, 0, enumerate, iw_gc_immortal_bases, 1};

void ironwood_trace_register(const void *sites, int count, const void *functions, int function_count) {
    (void)sites; (void)count; (void)functions; (void)function_count;
    abort();
}
void iw_gc_experiment_refuse(void) { abort(); }
void *iw_gc_experiment_new(int32_t type, uint64_t length) {
    assert(type >= 0 && type < IW_GC_TYPE_COUNT);
    void *result = iw_gc_new(iw_gc_types[type], (size_t)length);
    assert(result != NULL);
    return result;
}
static struct iw_gc_throwable *exception(void) {
    struct iw_gc_throwable *result = iw_gc_new(iw_gc_types[IW_GC_THROWABLE_TYPE], 0);
    assert(result != NULL);
    return result;
}
static void release_fd(uintptr_t fd) {
    assert(close((int)fd) == 0);
    --resources; ++released;
    release_order = release_order * 10 + 1;
}
static void release_memory(uintptr_t memory) {
    free((void *)memory);
    --resources; ++released;
    release_order = release_order * 10 + 2;
}
static void acquire(struct iw_gc_acquisitions *owner, unsigned index) {
    struct iw_gc_acquisition *slot = iw_gc_acquisition_reserve(owner);
    assert(slot != NULL);
    if (index == 0) {
        int fd = open("/dev/null", O_RDONLY);
        assert(fd >= 0);
        *slot = (struct iw_gc_acquisition){(uintptr_t)fd, release_fd};
    } else {
        void *memory = malloc(64);
        assert(memory != NULL);
        *slot = (struct iw_gc_acquisition){(uintptr_t)memory, release_memory};
    }
    ++resources;
}
static void preparation_exit(void) {
    if (unwind_failure) {
        assert(iw_gc_snapshot().collections == 0 && iw_gc_snapshot().disabled);
        printf("{\"case\":\"%s\",\"refused_before_collection\":true}\n", selected);
    }
    if (!preparation_failure) return;
    iw_gc_acquisitions_abort(&failure_owner);
    assert(resources == 0 && released == 2 && release_order == 21);
    assert(iw_gc_snapshot().collections == 0 && iw_gc_snapshot().disabled);
    printf("{\"case\":\"%s\",\"refused_before_collection\":true,\"native_resources\":0}\n", selected);
}
static void resource_cases(void) {
    for (unsigned fail = 0; fail <= 2; ++fail) {
        struct iw_gc_acquisition slots[2];
        struct iw_gc_acquisitions owner = {slots, 0, 2};
        released = release_order = 0;
        for (unsigned i = 0; i < fail; ++i) acquire(&owner, i);
        iw_gc_lifetimes_budget(0);
        iw_gc_acquisitions_abort(&owner);
        iw_gc_acquisitions_abort(&owner);
        assert(resources == 0 && released == fail && (fail < 2 || release_order == 21));
    }
    struct iw_gc_acquisition slots[2];
    struct iw_gc_acquisitions owner = {slots, 0, 2};
    acquire(&owner, 0); acquire(&owner, 1);
    size_t before = released;
    phase();
    assert(resources == 2 && released == before && fcntl((int)slots[0].handle, F_GETFD) >= 0);
    assert(iw_gc_acquisition_reserve(&owner) == NULL);
    uintptr_t committed = slots[0].handle;
    iw_gc_acquisition_commit(&slots[0]);
    iw_gc_acquisitions_abort(&owner);
    assert(resources == 1); /* Transferred native ownership survives failure. */
    release_fd(committed);
    assert(resources == 0);
    iw_gc_lifetimes_budget(SIZE_MAX);
}

__attribute__((noinline)) static void native_boundary(void) {
    if (!strcmp(selected, "missing-unwind")) {
        unwind_failure = 1;
        phase();
        abort();
    }
    if (!strcmp(selected, "preparation")) {
        acquire(&failure_owner, 0); acquire(&failure_owner, 1);
        preparation_failure = 1;
        struct iw_gc_native_root root = {0}, second = {0};
        assert(iw_gc_native_root_open(&root, exception()) && iw_gc_native_root_open(&second, exception()));
        phase();
        abort();
    }
    if (!strcmp(selected, "null-throw")) {
        struct iw_gc_throwable *later = exception();
        struct iw_gc_throwable *result = gc4_catch_unwind(NULL, NULL, later);
        assert(result != NULL && result->type == iw_gc_types[IW_GC_NPE_TYPE] && iw_gc_trace_count(result) > 0);
        assert(saved() == later && sequence() == 21 && iw_gc_snapshot().live == 2);
    } else if (!strcmp(selected, "resources")) resource_cases();
    else if (!strcmp(selected, "anchors")) {
        struct iw_gc_native_root a = {0}, b = {0};
        for (unsigned i = 0; i < 100; ++i) {
            struct iw_gc_throwable *first = exception(), *second = exception();
            assert(iw_gc_native_root_open(&a, first) && iw_gc_native_root_open(&b, first));
            assert(!iw_gc_native_root_open(&a, first));
            assert(!iw_gc_native_root_replace(&b, (char *)second + 8));
            assert(iw_gc_native_root_replace(&b, second));
            phase(); assert(iw_gc_snapshot().live == 2);
            iw_gc_native_root_close(&a); iw_gc_native_root_close(&a);
            phase(); assert(iw_gc_snapshot().live == 1 && GC_is_marked(second));
            iw_gc_native_root_close(&b);
            assert(!iw_gc_native_root_replace(&b, first));
            phase(); assert(iw_gc_snapshot().live == 0);
        }
    } else if (!strcmp(selected, "carrier")) {
        struct iw_gc_native_root root = {0};
        struct iw_gc_carrier *a = iw_gc_new(iw_gc_carrier_type(), 0), *b = iw_gc_new(iw_gc_carrier_type(), 0);
        struct iw_gc_throwable *dead = exception();
        assert(a && b);
        a->next = b; b->next = a; b->failure = exception();
        a->native_cookie = (uintptr_t)dead;
        assert(iw_gc_native_root_open(&root, a));
        phase();
        assert(iw_gc_snapshot().live == 1 && iw_gc_snapshot().sidecars == 2 && !GC_is_marked(dead));
        iw_gc_native_root_close(&root);
    } else if (!strcmp(selected, "cycles") || !strcmp(selected, "trace") || !strcmp(selected, "trace-fail")) {
        struct iw_gc_native_root root = {0};
        struct iw_gc_throwable *a = exception(), *b = exception(), *dead = exception();
        a->cause = b; b->cause = a;
        iw_gc_trace_capture(a); iw_gc_trace_capture(b);
        assert(iw_gc_trace_count(a) > 0 && iw_gc_trace_count(b) > 0);
        assert(iw_gc_add_secondary(a, b) && iw_gc_add_secondary(b, a));
        assert(iw_gc_secondary_at(a, 0) == b && iw_gc_secondary_at(b, 0) == a);
        assert(iw_gc_native_root_open(&root, a));
        uint64_t total = iw_gc_snapshot().total;
        phase(); assert(iw_gc_snapshot().live == 2 && !GC_is_marked(dead));
        if (!strcmp(selected, "trace-fail")) iw_gc_lifetimes_budget(0);
        for (unsigned i = 0; i < 5; ++i) {
            iw_gc_trace_capture(a);
            phase();
            assert(iw_gc_snapshot().live == 2 && iw_gc_snapshot().total == total);
        }
        if (!strcmp(selected, "trace-fail")) assert(iw_gc_trace_count(a) == 0);
        else assert(iw_gc_trace_count(a) > 0);
        iw_gc_lifetimes_budget(SIZE_MAX);
        iw_gc_native_root_close(&root);
    } else if (!strcmp(selected, "emergency-steps")) {
        for (size_t step = 0; step <= 4; ++step) {
            struct iw_gc_throwable *oom = iw_gc_oom_object(iw_gc_types[IW_GC_THROWABLE_TYPE]);
            struct iw_gc_throwable *primary = exception(), *secondary = exception(), *child = exception();
            assert(iw_gc_add_secondary(secondary, child));
            assert(iw_gc_emergency_begin(oom));
            iw_gc_lifetimes_budget(step);
            assert(iw_gc_add_secondary(primary, secondary));
            assert(gc4_catch_live_wrapper(primary, NULL, NULL) == primary);
            assert(iw_gc_secondary_count(primary) == 2 && iw_gc_secondary_at(primary, 0) == secondary
                    && iw_gc_secondary_at(primary, 1) == child && iw_gc_snapshot().live == 3);
            iw_gc_emergency_end();
            iw_gc_lifetimes_budget(SIZE_MAX);
            phase(); assert(iw_gc_snapshot().live == 0 && iw_gc_snapshot().sidecars == 0);
        }
    } else if (!strcmp(selected, "emergency") || !strcmp(selected, "oom") || !strcmp(selected, "immortal")) {
        int immortal = !strcmp(selected, "immortal");
        struct iw_gc_throwable *oom = immortal ? iw_gc_oom_object(iw_gc_types[IW_GC_THROWABLE_TYPE]) : exception();
        struct iw_gc_throwable *primary = exception(), *later = exception();
        if (immortal) {
            oom->cause = primary; oom->owned_message = later;
            iw_gc_trace_capture(oom);
            assert(iw_gc_trace_count(oom) > 0);
        }
        assert(iw_gc_emergency_begin(oom) && !iw_gc_emergency_begin(oom));
        if (!strcmp(selected, "oom")) {
            acquire(&failure_owner, 0); acquire(&failure_owner, 1);
            size_t successes = 0;
            while (iw_gc_new(iw_gc_types[IW_GC_THROWABLE_TYPE], 0) != NULL) ++successes;
            assert(successes > 100 && iw_gc_snapshot().collections == 0 && iw_gc_snapshot().disabled);
            iw_gc_lifetimes_budget(0);
            iw_gc_acquisitions_abort(&failure_owner);
            assert(resources == 0 && iw_gc_snapshot().collections == 0);
        }
        iw_gc_lifetimes_budget(0);
        assert(iw_gc_add_secondary(primary, later));
        assert(gc4_catch_live_wrapper(immortal ? oom : primary, NULL, NULL) == (immortal ? oom : primary));
        assert(iw_gc_secondary_at(primary, 0) == later && iw_gc_snapshot().live == (immortal ? 2 : 3));
        assert(iw_gc_unwind_wrapper_count() == 0);
        iw_gc_emergency_end();
        iw_gc_lifetimes_budget(SIZE_MAX);
    } else if (!strcmp(selected, "published") || !strcmp(selected, "published-success")) {
        struct iw_gc_throwable *primary = exception();
        struct iw_gc_throwable *result = !strcmp(selected, "published") ? gc4_published_failure(primary) : gc4_published_success(primary);
        assert(result == primary && iw_gc_snapshot().live == 3);
        struct node *object = saved();
        assert(object != NULL && object->link != NULL && object->link->value == 0);
        phase(); assert(iw_gc_snapshot().live == 2); /* The committed graph survives. */
    } else if (!strcmp(selected, "pending") || !strcmp(selected, "yield")) {
        struct iw_gc_throwable *primary = exception();
        void *result = !strcmp(selected, "pending") ? pending(primary) : pending_yield(primary);
        assert(result == primary && iw_gc_snapshot().live == 1);
    } else {
        struct iw_gc_throwable *primary = exception(), *secondary = exception(), *later = exception();
        struct iw_gc_throwable *result;
        if (!strcmp(selected, "unwind")) result = gc4_catch_unwind(primary, secondary, later);
        else if (!strcmp(selected, "nested")) result = gc4_catch_nested(primary, secondary, later);
        else if (!strcmp(selected, "wrapper")) result = gc4_catch_live_wrapper(primary, secondary, later);
        else if (!strcmp(selected, "nested-wrappers")) result = gc4_catch_nested_wrappers(primary, secondary, later);
        else if (!strcmp(selected, "rethrow")) result = gc4_rethrow(primary, secondary, later);
        else abort();
        assert(result == primary && iw_gc_unwind_wrapper_count() == 0);
        if (!strcmp(selected, "wrapper") || !strcmp(selected, "rethrow")) assert(wrapper_collections > 0);
        if (!strcmp(selected, "nested-wrappers")) assert(max_wrappers == 2);
        if (!strcmp(selected, "unwind") || !strcmp(selected, "nested")) {
            assert(saved() == later && sequence() == (!strcmp(selected, "nested") ? 213 : 21));
            if (!strcmp(selected, "nested")) assert(iw_gc_secondary_at(primary, 0) == secondary);
        }
    }
    clear(); lifetime_clear(); phase();
    struct iw_gc_snapshot state = iw_gc_snapshot();
    assert(state.live == 0 && state.sidecars == 0 && state.disabled && resources == 0);
    assert(iw_gc_active_native_roots() == 0 && iw_gc_unwind_wrapper_count() == 0);
    printf("{\"case\":\"%s\",\"collections\":%" PRIu64 ",\"language_live\":0,\"sidecars\":0,\"native_resources\":0}\n", selected, state.collections);
}

static const struct iw_gc_owner_protocol stale_owners = {0, 7, 0, 0, NULL, NULL, 0};
static const struct iw_gc_owner_protocol foreign_owners = {1, 7, 1, 0, NULL, NULL, 0};
static const struct iw_gc_owner_protocol incomplete_owners = {1, 3, 0, 0, NULL, NULL, 0};
int main(int argc, char **argv) {
    assert(argc == 2); selected = argv[1];
    assert(iw_gc_init(iw_gc_configuration(), 16 * 1024 * 1024, 0));
    assert(iw_gc_lifetimes_start());
    for (size_t i = 0; i < IW_GC_TYPE_COUNT; ++i) {
        iw_gc_types[i] = iw_gc_describe(layouts[i]); assert(iw_gc_types[i]);
    }
    const struct iw_gc_layout *throwable = layouts[IW_GC_THROWABLE_TYPE];
    assert(throwable->size == sizeof(struct iw_gc_throwable) && throwable->reference_count == 4);
    assert(throwable->references[2] == offsetof(struct iw_gc_throwable, metadata));
    struct iw_gc_root_config config = {2, 8, 1, 3, native_boundary, iw_gc_static_roots,
        (size_t)iw_gc_static_root_count, iw_gc_native_roots, 0, 4096, 3, 4, &probe_owners};
    int refusal = 1;
    if (!strcmp(selected, "stale")) config.version = 1;
    else if (!strcmp(selected, "missing")) config.owners = NULL;
    else if (!strcmp(selected, "owner-version")) config.owners = &stale_owners;
    else if (!strcmp(selected, "coverage")) config.owners = &incomplete_owners;
    else if (!strcmp(selected, "foreign")) config.owners = &foreign_owners;
    else if (!strcmp(selected, "owner-mask")) config.exception_owners = 0;
    else if (!strcmp(selected, "stale-pc") || !strcmp(selected, "stale-slot") || !strcmp(selected, "stale-function")
            || !strcmp(selected, "writable-map")) { /* Mutated image. */ }
    else refusal = 0;
    if (refusal) {
        assert(!iw_gc_roots_start(&config));
        assert(iw_gc_snapshot().collections == 0 && iw_gc_snapshot().disabled);
        printf("{\"case\":\"%s\",\"refused_before_collection\":true}\n", selected);
        return 0;
    }
    if (!strcmp(selected, "preparation")) config.scratch_capacity = 1;
    assert(iw_gc_roots_start(&config));
    assert(atexit(preparation_exit) == 0);
    native_boundary();
    iw_gc_roots_stop();
    assert(iw_gc_lifetimes_stop());
    return 0;
}
