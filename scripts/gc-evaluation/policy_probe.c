// SPDX-License-Identifier: MIT OR Apache-2.0
// Exhaustion must fail without collection; an explicit collection enables reuse.
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include "gc.h"
extern void mm_init(int);
extern uintptr_t mm_new_node(void);
extern void mm_collect(void);
int main(void) {
    mm_init(0);
    GC_set_max_heap_size(32*1024*1024); GC_set_max_retries(2);
    size_t initial=GC_get_gc_no(),count=0;
    for(;count<1000000;++count) if(!mm_new_node()) break;
    assert(count<1000000 && GC_get_gc_no()==initial);
    mm_collect();
    assert(GC_get_gc_no()==initial+1 && mm_new_node());
    struct GC_prof_stats_s before,after;
    GC_get_prof_stats(&before,sizeof(before));
    for(size_t i=0;i<200000;++i) assert(mm_new_node());
    GC_get_prof_stats(&after,sizeof(after));
    assert(after.bytes_reclaimed_since_gc==before.bytes_reclaimed_since_gc);
    printf("{\"case\":\"compiler_roots_policy\",\"startup_collections\":%zu,\"allocations_until_null\":%zu,\"automatic_collections\":0,\"explicit_collections\":1,\"replacement_allocations\":200000,\"deferred_reclaimed_bytes\":0}\n",initial,count);
    return 0;
}
