// SPDX-License-Identifier: MIT OR Apache-2.0
// One collector kind, 1024 immutable type descriptors, existing object-header
// pointer, exact field maps, and no footer or per-reference bookkeeping.
#include <assert.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include "gc.h"
#include "gc_mark.h"
typedef struct TypeInfo { uintptr_t id; GC_word descriptor; } TypeInfo;
typedef struct Object { TypeInfo *type; uintptr_t words[7]; } Object;
static TypeInfo types[1024], leaf={1024,0};
int main(void) {
    GC_INIT(); GC_disable();
    GC_word descriptor=(GC_word)(-(intptr_t)offsetof(TypeInfo,descriptor)-GC_INDIR_PER_OBJ_BIAS+GC_DS_PER_OBJECT);
    unsigned kind=GC_new_kind(GC_new_free_list(),descriptor,0,1);
    Object **roots=calloc(1024,sizeof(void *)); assert(roots);
    GC_add_roots(roots,roots+1024);
    for(size_t i=0;i<1024;++i) {
        size_t field=1+i%6;
        types[i].id=i;
        types[i].descriptor=((GC_word)1<<(sizeof(GC_word)*8-1-field))|GC_DS_BITMAP;
        Object *holder=GC_generic_malloc(64,kind),*live=GC_generic_malloc(64,kind),*dead=GC_generic_malloc(64,kind);
        assert(holder && live && dead);
        holder->type=&types[i]; live->type=&leaf; dead->type=&leaf;
        holder->words[field-1]=(uintptr_t)live;
        holder->words[6]=(uintptr_t)dead; // Numeric field deliberately resembles a pointer.
        live->words[2]=i+77; roots[i]=holder;
    }
    GC_enable(); GC_gcollect(); GC_disable();
    for(size_t i=0;i<1024;++i) {
        Object *holder=roots[i],*live=(Object *)holder->words[i%6],*dead=(Object *)holder->words[6];
        assert(GC_is_marked(holder)); assert(GC_is_marked(live));
        assert(!GC_is_marked(dead)); assert(live->words[2]==i+77);
    }
    printf("{\"case\":\"typeinfo_maps\",\"types\":1024,\"collector_kinds\":1,\"live_children\":1024,\"numeric_targets_reclaimed\":1024,\"object_bytes\":64}\n");
    return 0;
}
