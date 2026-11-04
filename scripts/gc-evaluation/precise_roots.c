// SPDX-License-Identifier: MIT OR Apache-2.0
// Original experimental LLVM stack-map reader for this fixed-layout fixture.
// No shadow-stack updates, root registration, or stack walking occur until GC.
// Nonmoving collection permits copying base-root values into this scratch array.
#include <assert.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <libunwind.h>

extern const unsigned char stackmaps[] __asm__("section$start$__LLVM_STACKMAPS$__llvm_stackmaps");
extern void mm_init(int);
extern void mm_set_roots(uintptr_t, size_t);
extern void mm_collect(void);
extern size_t mm_collections(void);
extern _Bool mm_is_live(uintptr_t);
extern uint64_t root_chain(uint64_t);

static uintptr_t roots[2048];
static size_t count, frames_found;
static int omit_roots;
uint64_t read_interior(const uint64_t *p) { return *p; }
static uint16_t u16(const unsigned char *p) { uint16_t x; memcpy(&x,p,2); return x; }
static uint32_t u32(const unsigned char *p) { uint32_t x; memcpy(&x,p,4); return x; }
static uint64_t u64(const unsigned char *p) { uint64_t x; memcpy(&x,p,8); return x; }
static const unsigned char *aligned(const unsigned char *p) {
    return (const unsigned char *)(((uintptr_t)p+7)&~(uintptr_t)7);
}

static uintptr_t location(const unsigned char *p, unw_cursor_t *cursor, const unsigned char *constants) {
    unsigned kind=p[0], reg=u16(p+4);
    int32_t offset=(int32_t)u32(p+8);
    unw_word_t value=0;
    if (kind==4) return (uintptr_t)offset;
    if (kind==5) return u64(constants+8*(uint32_t)offset);
    assert(unw_get_reg(cursor,(unw_regnum_t)reg,&value)==0);
    if (kind==1) return value;
    if (kind==2) return value+offset;
    assert(kind==3);
    uintptr_t root; memcpy(&root,(const void *)(value+offset),sizeof(root));
    return root;
}

static void scan_frame(unw_cursor_t *cursor, uintptr_t pc) {
    assert(stackmaps[0]==3);
    uint32_t nf=u32(stackmaps+4), nc=u32(stackmaps+8);
    const unsigned char *functions=stackmaps+16;
    const unsigned char *constants=functions+24*nf;
    const unsigned char *record=constants+8*nc;
    for (uint32_t f=0; f<nf; ++f) {
        uintptr_t start=u64(functions+24*f);
        uint64_t nr=u64(functions+24*f+16);
        for (uint64_t r=0; r<nr; ++r) {
            unsigned nl=u16(record+14);
            const unsigned char *locations=record+16;
            const unsigned char *tail=aligned(locations+12*nl);
            unsigned no=u16(tail+2);
            if (pc==start+u32(record+8)) {
                ++frames_found;
                // Statepoint locations 0..2 describe calling convention,
                // flags and deoptimization count. Then come base/derived pairs.
                assert(nl>=3 && (nl-3)%2==0);
                assert(location(locations+24,cursor,constants)==0);
                for (unsigned i=3; i<nl; i+=2) {
                    uintptr_t root=location(locations+12*i,cursor,constants);
                    if (root) { assert(count<2048); roots[count++]=root; }
                }
            }
            record=aligned(tail+4+4*no);
        }
    }
}

__attribute__((noinline)) void collect_from_stack(void) {
    unw_context_t context; unw_cursor_t cursor;
    assert(unw_getcontext(&context)==0);
    assert(unw_init_local(&cursor,&context)==0);
    count=0; frames_found=0;
    do {
        unw_word_t pc;
        assert(unw_get_reg(&cursor,UNW_REG_IP,&pc)==0);
        scan_frame(&cursor,pc);
    } while (unw_step(&cursor)>0);
    assert(frames_found==17 && count==17);
    mm_set_roots((uintptr_t)roots,omit_roots ? 0 : count);
    mm_collect();
    mm_set_roots(0,0);
    if (omit_roots) {
        for (size_t i=0; i<count; ++i) assert(!mm_is_live(roots[i]));
        printf("{\"case\":\"llvm_omitted_roots\",\"dead_base_roots\":%zu}\n",count);
        exit(0); // Do not resume managed code with reclaimed references.
    }
}

int main(int argc, char **argv) {
    omit_roots=argc>2 && !strcmp(argv[2],"omit");
    mm_init(argc>1 && !strcmp(argv[1],"immix"));
    uint64_t expected=17*123+16*17/2;
    for (unsigned i=0; i<30; ++i) assert(root_chain(16)==expected);
    printf("{\"case\":\"llvm_precise_roots\",\"caller_frames\":%zu,\"base_roots\":%zu,\"collections\":%zu,\"checksum\":%" PRIu64 "}\n", frames_found,count,mm_collections(),expected);
    return 0;
}
