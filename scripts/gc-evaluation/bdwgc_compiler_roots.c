// SPDX-License-Identifier: MIT OR Apache-2.0
// Restricted fixed-layout experiment: compiler-provided roots and a two-field
// bitmap replace ambient native root scanning. No per-object typed footer.
// This does not qualify arbitrary C pointers, other layouts, or production use.
#include <assert.h>
#include <stddef.h>
#include <stdint.h>
#include "gc.h"
#include "gc_mark.h"

static unsigned kind;
static void **registered;
static size_t registered_count;
void mm_init(int unused) {
    (void)unused;
    GC_set_dont_precollect(1);
    GC_INIT(); GC_disable();
    GC_word bitmap=((GC_word)3 << (sizeof(GC_word)*8-2)) | GC_DS_BITMAP;
    kind=GC_new_kind(GC_new_free_list(),bitmap,0,1);
}
uintptr_t mm_new_node(void) { return (uintptr_t)GC_generic_malloc(64,kind); }
void mm_set_roots(uintptr_t base,size_t count) {
    if (registered_count) GC_remove_roots(registered,registered+registered_count);
    registered=(void **)base; registered_count=count;
    if(count) GC_add_roots(registered,registered+count);
}
void mm_collect(void) { GC_enable(); GC_gcollect(); GC_disable(); }
size_t mm_used_bytes(void) { return GC_get_heap_size()-GC_get_free_bytes(); }
size_t mm_collections(void) { return GC_get_gc_no(); }
_Bool mm_is_live(uintptr_t p) { return GC_is_marked((const void *)p)!=0; }
