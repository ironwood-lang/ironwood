// SPDX-License-Identifier: MIT OR Apache-2.0
// GC3's explicit native boundary owns no implicit managed roots. Local addresses
// are intentionally unregistered; only compiler roots and the declared anchor
// may retain graphs. Every dereference follows a successful rooted collection.
#include "ironwood_gc_engine.h"
#include "ironwood_gc_roots.h"
#include "gc.h"
#include "gc_mark.h"
#include "layouts.h"
#include <assert.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(__APPLE__)
#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>
static void protection_fault(int signo) { _exit(signo == SIGBUS || signo == SIGSEGV ? 0 : 99); }
#define IW_SYMBOL(name) "_" name
#else
#define IW_SYMBOL(name) name
#endif
extern void *identity(void *, int32_t) __asm__(IW_SYMBOL("ironwood.Main.identity"));
extern void *merge(void *, void *, _Bool) __asm__(IW_SYMBOL("ironwood.Main.merge"));
extern void *loop(void *, int32_t) __asm__(IW_SYMBOL("ironwood.Main.loop"));
extern void *pending(void *) __asm__(IW_SYMBOL("ironwood.Main.pending"));
extern void *pending_yield(void *) __asm__(IW_SYMBOL("ironwood.Main.pendingYield"));
extern void construct(void *) __asm__(IW_SYMBOL("ironwood.Node.<init>"));
extern void *capture(void *, void *) __asm__(IW_SYMBOL("ironwood.Main.capture"));
extern void *initialize(void *) __asm__(IW_SYMBOL("ironwood.Main.initialize"));
extern void save(void *) __asm__(IW_SYMBOL("ironwood.Main.save"));
extern void *saved(void) __asm__(IW_SYMBOL("ironwood.Main.saved"));
extern void clear(void) __asm__(IW_SYMBOL("ironwood.Main.clear"));
extern void phase(void) __asm__(IW_SYMBOL("ironwood.Main.phase"));
extern int64_t scalar(int64_t, int32_t) __asm__(IW_SYMBOL("ironwood.Main.scalar"));
extern void *gc3_stack(void *);
extern int64_t gc3_stack_clear(void *);
extern void *gc3_allocate(void);
extern void *gc3_dispatch(void *);
extern int64_t gc3_derived(void *);
extern void *gc3_exception(void *);
extern void *const iw_gc_static_roots[];
extern const uint64_t iw_gc_static_root_count;

const struct iw_gc_type *iw_gc_types[IW_GC_TYPE_COUNT];
struct node { const void *type; struct node *link; int64_t value; };
static struct node *native_anchor;
const uint64_t iw_gc_native_root_count = 1;
void *const iw_gc_native_roots[] = {&native_anchor};
static const char *selected;
static int report_refusal;
static void wrong_boundary(void) { abort(); }

/* The ordinary trace injector remains in the pipeline. GC3 does not activate
 * Throwable materialization; this unused registration target has no GC effect.
 * Debug/probe sections and optimized function identities remain in the image.
 */
void ironwood_trace_register(const void *sites, int count, const void *functions, int function_count) {
    (void)sites; (void)count; (void)functions; (void)function_count;
    abort();
}

void iw_gc_experiment_refuse(void) { abort(); }
void *iw_gc_experiment_new(int32_t type, uint64_t length) {
    if (type < 0 || type >= IW_GC_TYPE_COUNT) abort();
    void *result = iw_gc_new(iw_gc_types[type], (size_t)length);
    if (result == NULL) abort();
    return result;
}

static struct node *node(int64_t value) {
    struct node *result = iw_gc_new(iw_gc_types[IW_GC_NODE_TYPE], 0);
    assert(result != NULL);
    result->value = value;
    return result;
}

static void refused_report(void) {
    if (!report_refusal) return;
    struct iw_gc_snapshot state = iw_gc_snapshot();
    assert(state.disabled && state.collections == 0);
    printf("{\"case\":\"%s\",\"refused_before_collection\":true}\n", selected);
}

__attribute__((noinline)) static void native_boundary(void) {
    if (!strcmp(selected, "allocate")) {
        struct node *result = gc3_allocate();
        assert(result->value == 123 && iw_gc_snapshot().live == 1);
    } else if (!strcmp(selected, "stack-clear")) {
        assert(gc3_stack_clear(node(9)) == 9 && iw_gc_snapshot().live == 0);
    } else if (!strcmp(selected, "missing-unwind")) {
        report_refusal = 1;
        phase();
        abort();
    } else if (!strcmp(selected, "legacy-heap")) {
        report_refusal = 1;
        native_anchor = calloc(1, sizeof(*native_anchor));
        assert(native_anchor != NULL);
        phase();
        abort();
    } else if (!strcmp(selected, "boundary") || !strcmp(selected, "scratch")) {
        report_refusal = 1;
        native_anchor = node(2);
        (void)identity(node(1), 8);
        abort();
    } else if (!strcmp(selected, "interior") || !strcmp(selected, "one-past")) {
        report_refusal = 1;
        struct node *base = node(1);
        native_anchor = (struct node *)((unsigned char *)base + (!strcmp(selected, "interior") ? 8 : sizeof(*base)));
        phase();
        abort();
    } else if (!strcmp(selected, "scalar")) {
        uint64_t before = iw_gc_snapshot().collections;
        uint64_t expected = 7;
        for (unsigned i = 0; i < 1000; ++i) expected = expected * 3 + i;
        assert((uint64_t)scalar(7, 1000) == expected && iw_gc_snapshot().collections == before);
    } else {
        struct node *first = node(41), *child = node(42), *dead = node(99);
        first->link = child;
        first->value = (int64_t)(uintptr_t)dead; /* numeric lookalike, never an edge */
        struct node *result = NULL;
        uint64_t expected = 2;
        if (!strcmp(selected, "recursion")) result = identity(first, 16);
        else if (!strcmp(selected, "merge")) result = merge(first, node(43), 1);
        else if (!strcmp(selected, "loop")) result = loop(first, 5);
        else if (!strcmp(selected, "pending")) result = pending(first);
        else if (!strcmp(selected, "yield")) result = pending_yield(first);
        else if (!strcmp(selected, "constructor")) { construct(first); result = first; }
        else if (!strcmp(selected, "exception")) result = gc3_exception(first);
        else if (!strcmp(selected, "derived")) { assert(gc3_derived(first) == (int64_t)(uintptr_t)dead); result = first; }
        else if (!strcmp(selected, "initialize")) result = initialize(first);
        else if (!strcmp(selected, "stack")) result = gc3_stack(first);
        else if (!strcmp(selected, "dispatch")) result = gc3_dispatch(first);
        else if (!strcmp(selected, "capture")) {
            result = capture(first, node(44)); expected = 3;
        } else if (!strcmp(selected, "static")) {
            save(first); phase(); result = saved();
        } else if (!strcmp(selected, "native")) {
            native_anchor = first; phase(); result = native_anchor; native_anchor = NULL;
        } else abort();
        assert(result != NULL && iw_gc_snapshot().live == expected && !GC_is_marked(dead));
        if (!strcmp(selected, "capture")) assert(result->value == 44);
        else assert(result == first && result->link == child && child->value == 42);
    }
    size_t frames = iw_gc_roots_last_frames(), roots = iw_gc_roots_last_count();
    // Partial inlining at O3 folds one source activation into its caller.
    if (!strcmp(selected, "recursion")) assert(iw_gc_roots_max_frames() >= 16);
    clear();
    phase();
    struct iw_gc_snapshot state = iw_gc_snapshot();
    assert(state.live == 0 && state.disabled);
    printf("{\"case\":\"%s\",\"collections\":%" PRIu64 ",\"rooted_frames\":%zu,\"max_frames\":%zu,\"roots\":%zu,\"unrooted_live\":0}\n",
            selected, state.collections, frames, iw_gc_roots_max_frames(), roots);
}

int main(int argc, char **argv) {
    assert(argc == 2);
    selected = argv[1];
    assert(iw_gc_init(iw_gc_configuration(), 64 * 1024 * 1024, 0));
    assert(layouts[IW_GC_NODE_TYPE]->size == sizeof(struct node)
            && layouts[IW_GC_NODE_TYPE]->reference_count == 1
            && layouts[IW_GC_NODE_TYPE]->references[0] == offsetof(struct node, link));
    for (size_t i = 0; i < IW_GC_TYPE_COUNT; ++i) {
        iw_gc_types[i] = iw_gc_describe(layouts[i]); assert(iw_gc_types[i] != NULL);
    }
    struct iw_gc_root_config config = {1, 8, 1, 3, native_boundary, iw_gc_static_roots,
            (size_t)iw_gc_static_root_count, iw_gc_native_roots, (size_t)iw_gc_native_root_count, 4096, 0, 0, NULL};
    int refuse_start = 0;
    if (!strcmp(selected, "version")) { config.version = 2; refuse_start = 1; }
    if (!strcmp(selected, "width")) { config.pointer_bytes = 4; refuse_start = 1; }
    if (!strcmp(selected, "endian")) { config.little_endian = 0; refuse_start = 1; }
    if (!strcmp(selected, "exceptions")) { config.exception_owners = 1; refuse_start = 1; }
    if (!strcmp(selected, "foreign")) { config.foreign_owners = 1; refuse_start = 1; }
    if (!strcmp(selected, "static-count")) { --config.static_count; refuse_start = 1; }
    if (!strcmp(selected, "native-count")) { --config.native_count; refuse_start = 1; }
    if (!strcmp(selected, "boundary")) config.native_boundary = wrong_boundary;
    if (!strcmp(selected, "missing-map") || !strcmp(selected, "stale-version") || !strcmp(selected, "bad-count")
            || !strcmp(selected, "bad-location") || !strcmp(selected, "stale-abi") || !strcmp(selected, "stale-pc")
            || !strcmp(selected, "stale-slot") || !strcmp(selected, "stale-function") || !strcmp(selected, "writable-map")
            || !strcmp(selected, "missing-boundaries") || !strcmp(selected, "bad-boundaries")) refuse_start = 1;
    if (!strcmp(selected, "scratch")) config.scratch_capacity = 1;
    if (refuse_start) {
        assert(!iw_gc_roots_start(&config));
        assert(iw_gc_snapshot().collections == 0 && iw_gc_snapshot().disabled);
        printf("{\"case\":\"%s\",\"refused_before_collection\":true}\n", selected);
        return 0;
    }
    assert(iw_gc_roots_start(&config));
#if defined(__APPLE__)
    if (!strcmp(selected, "readonly-map")) {
        extern const unsigned char map[] __asm__("section$start$__DATA_CONST$__llvm_stackmaps");
        pid_t child = fork();
        assert(child >= 0);
        if (child == 0) {
            /* Qualification only: the loaded map must reject an actual store. */
            volatile unsigned char *target = (volatile unsigned char *)(uintptr_t)map;
            assert(*target == 3);
            assert(signal(SIGBUS, protection_fault) != SIG_ERR && signal(SIGSEGV, protection_fault) != SIG_ERR);
            *target = *target;
            _exit(99);
        }
        int status;
        assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
        assert(iw_gc_snapshot().disabled && iw_gc_snapshot().collections == 0);
        puts("{\"case\":\"readonly-map\",\"write_protected\":true}");
        iw_gc_roots_stop();
        return 0;
    }
#endif
    assert(atexit(refused_report) == 0);
    native_boundary();
    iw_gc_roots_stop();
    return 0;
}
