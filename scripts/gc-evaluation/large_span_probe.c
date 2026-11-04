// SPDX-License-Identifier: MIT OR Apache-2.0
// Tests >4 GiB allocation metadata and end addressing, not 5 GiB resident use.
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include "gc.h"
int main(void) {
    GC_INIT(); GC_disable();
    size_t bytes=(size_t)5*1024*1024*1024;
    unsigned char *root=GC_malloc_atomic(bytes); assert(root);
    GC_add_roots(&root,&root+1);
    root[0]=17; root[bytes-1]=29;
    GC_enable(); GC_gcollect(); GC_disable();
    assert(root[0]==17 && root[bytes-1]==29);
    size_t heap=GC_get_heap_size();
    GC_remove_roots(&root,&root+1); root=NULL;
    GC_enable(); GC_gcollect(); GC_disable();
    size_t available=GC_get_free_bytes(); assert(available>=bytes);
    printf("{\"case\":\"large_virtual_span\",\"allocation_bytes\":%zu,\"heap_bytes\":%zu,\"available_after_collect\":%zu,\"payload_pages_touched\":2}\n",bytes,heap,available);
    return 0;
}
