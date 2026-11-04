// SPDX-License-Identifier: MIT OR Apache-2.0
// Warmed allocation latency and mixed-lifetime reuse, separate from throughput.
#include <assert.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <mach/mach_time.h>
#include "gc.h"
#include "gc_typed.h"
extern void mm_init(int);
extern uintptr_t mm_new_node(void);
extern void mm_set_roots(uintptr_t,size_t);
extern void mm_collect(void);
extern size_t mm_used_bytes(void);

typedef struct Node { struct Node *left,*right; uint64_t value,noise,padding[4]; } Node;
static int backend;
static GC_descr descriptor;
static volatile uintptr_t sink;
static uint64_t now_ns(void) {
    static mach_timebase_info_data_t timebase;
    if (!timebase.denom) mach_timebase_info(&timebase);
    return mach_absolute_time()*timebase.numer/timebase.denom;
}
__attribute__((noinline)) static Node *allocate(void) {
    Node *p=backend==0 ? calloc(1,64) : backend==1 ? GC_malloc_explicitly_typed(64,descriptor) : (Node *)mm_new_node();
    assert(p); return p;
}
static void collect(void) {
    if (backend==1) { GC_enable(); GC_gcollect(); GC_disable(); }
    else if (backend>=2) mm_collect();
}
static int compare(const void *a,const void *b) {
    uint64_t x=*(const uint64_t *)a,y=*(const uint64_t *)b;
    return (x>y)-(x<y);
}
__attribute__((noinline)) static void warm(void) {
    if (!backend) {
        void **items=calloc(65536,sizeof(void *)); assert(items);
        for(size_t i=0;i<65536;++i) items[i]=allocate();
        for(size_t i=0;i<65536;++i) free(items[i]);
        free(items);
    } else {
        for(size_t i=0;i<65536;++i) sink=(uintptr_t)allocate();
        sink=0; collect();
    }
}
static void latency(void) {
    warm();
    uint64_t *samples=calloc(50000,sizeof(uint64_t)); assert(samples);
    for(size_t i=0;i<50000;++i) {
        uint64_t t=now_ns(); Node *p=allocate(); samples[i]=now_ns()-t;
        p->value=i; sink=(uintptr_t)p;
    }
    qsort(samples,50000,sizeof(uint64_t),compare);
    printf("{\"case\":\"allocation_latency\",\"backend\":%d,\"median_ns\":%" PRIu64 ",\"p99_ns\":%" PRIu64 ",\"max_ns\":%" PRIu64 ",\"events\":50000}\n",backend,samples[25000],samples[49500],samples[49999]);
}
static void holes(void) {
    Node **roots=backend==1 ? GC_malloc_uncollectable(300000*sizeof(void *)) : calloc(300000,sizeof(void *));
    assert(roots);
    for(size_t i=0;i<300000;++i) {
        Node *dead=allocate(),*live=allocate(); live->value=i;
        live->noise=(uintptr_t)dead; roots[i]=live;
    }
    if(backend>=2) mm_set_roots((uintptr_t)roots,300000);
    collect();
    size_t before=backend==1 ? GC_get_heap_size() : mm_used_bytes();
    for(size_t i=0;i<300000;++i) { Node *p=allocate(); p->value=i; sink=(uintptr_t)p; }
    size_t after=backend==1 ? GC_get_heap_size() : mm_used_bytes();
    for(size_t i=0;i<300000;++i) assert(roots[i]->value==i);
    printf("{\"case\":\"mixed_lifetime_holes\",\"backend\":%d,\"bytes_before_refill\":%zu,\"bytes_after_refill\":%zu,\"growth_bytes\":%zu,\"retained_objects\":300000,\"replacement_objects\":300000}\n",backend,before,after,after-before);
    GC_reachable_here(roots);
}
int main(int argc,char **argv) {
    assert(argc==3); backend=atoi(argv[1]);
    if(backend>=2) mm_init(backend-2);
    else {
        GC_INIT(); GC_disable(); GC_word bitmap[GC_BITMAP_SIZE(Node)]={0};
        GC_set_bit(bitmap,0); GC_set_bit(bitmap,1);
        descriptor=GC_make_descriptor(bitmap,GC_WORD_LEN(Node));
    }
    if(!strcmp(argv[2],"latency")) latency();
    else { assert(backend); holes(); }
    return 0;
}
