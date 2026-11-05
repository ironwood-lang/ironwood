// SPDX-License-Identifier: MIT OR Apache-2.0
// Collection-time native roots for explicit GC3 development images only.
#if defined(__linux__)
#define _GNU_SOURCE 1
#endif
#include "ironwood_gc_roots.h"
#include "ironwood_gc_engine.h"
#include "gc.h"
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(__APPLE__) && defined(__aarch64__)
#include <libunwind.h>
#include <mach-o/dyld.h>
#include <mach-o/loader.h>
extern const unsigned char iw_map_begin[] __asm__("section$start$__LLVM_STACKMAPS$__llvm_stackmaps");
extern const unsigned char iw_map_end[] __asm__("section$end$__LLVM_STACKMAPS$__llvm_stackmaps");
#if defined(__arm64e__)
#define IW_ROOT_TARGET_QUALIFIED 0
#else
#define IW_ROOT_TARGET_QUALIFIED 1
#endif
#elif defined(__linux__) && (defined(__aarch64__) || defined(__x86_64__))
#include <link.h>
#include <unwind.h>
#if defined(__x86_64__)
#define IW_ROOT_TARGET_QUALIFIED 1
#else
#define IW_ROOT_TARGET_QUALIFIED 0
#endif
#else
#define IW_ROOT_TARGET_QUALIFIED 0
#endif

#define IW_MAX_MAP_ITEMS 1048576u
#define IW_REGION_MAGIC 1230455603u
struct image_range { uintptr_t begin, end; unsigned readable, writable, executable; };
struct root_record {
    uintptr_t function, pc;
    uint64_t stack_size;
    const unsigned char *locations;
    uint16_t count, deopt;
};
static struct image_range ranges[64];
static size_t range_count;
static struct root_record *records;
static size_t record_count;
static const unsigned char *constants;
static size_t constant_count;
static struct iw_gc_root_config configuration;
static void **scratch;
static size_t root_count, last_count, last_frames, max_frames;
static int started;
extern const uint32_t iw_gc_root_abi;
extern const uint32_t iw_gc_owner_requirements;
extern const uint64_t iw_gc_static_root_count;
extern void *const iw_gc_static_roots[];
extern const uint64_t iw_gc_native_root_count;
extern void *const iw_gc_native_roots[];
extern const uint64_t iw_gc_map_hash, iw_gc_function_catalog_count;
struct iw_gc_function { uint64_t guid; uintptr_t address; };
extern const struct iw_gc_function iw_gc_function_catalog[];

static uint16_t read16(const void *p) { uint16_t v; memcpy(&v, p, sizeof(v)); return v; }
static uint32_t read32(const void *p) { uint32_t v; memcpy(&v, p, sizeof(v)); return v; }
static uint64_t read64(const void *p) { uint64_t v; memcpy(&v, p, sizeof(v)); return v; }

static int in_image(uintptr_t address, size_t bytes, int write, int execute) {
    if (address > UINTPTR_MAX - bytes) return 0;
    for (size_t i = 0; i < range_count; ++i) {
        if (address >= ranges[i].begin && address + bytes <= ranges[i].end
                && ranges[i].readable && (!write || ranges[i].writable)
                && (!execute || ranges[i].executable)) return 1;
    }
    return 0;
}

static int add_range(uintptr_t begin, uint64_t size, unsigned read, unsigned write, unsigned execute) {
    if (size > UINTPTR_MAX - begin || range_count == sizeof(ranges) / sizeof(ranges[0])) return 0;
    ranges[range_count++] = (struct image_range){begin, begin + (uintptr_t)size, read, write, execute};
    return 1;
}

static int map_integrity(const unsigned char *map, size_t bytes) {
    if (bytes < 16 || iw_gc_function_catalog_count > IW_MAX_MAP_ITEMS
            || !in_image((uintptr_t)iw_gc_function_catalog,
            (size_t)iw_gc_function_catalog_count * sizeof(struct iw_gc_function), 0, 0)) return 0;
    uint32_t functions = read32(map + 4);
    if (functions > (bytes - 16) / 24) return 0;
    uint64_t hash = UINT64_C(0xcbf29ce484222325);
    for (size_t offset = 0; offset < bytes;) {
        if (offset >= 16 && (offset - 16) % 24 == 0 && (offset - 16) / 24 < functions) {
            uintptr_t address = (uintptr_t)read64(map + offset);
            uint64_t identity = UINT64_MAX;
            int found = 0;
            for (uint64_t i = 0; i < iw_gc_function_catalog_count; ++i) {
                if (iw_gc_function_catalog[i].address == address) {
                    if (iw_gc_function_catalog[i].guid < identity) identity = iw_gc_function_catalog[i].guid;
                    found = 1;
                }
            }
            if (!found) return 0;
            for (unsigned i = 0; i < 8; ++i) hash = (hash ^ ((identity >> (8 * i)) & 255)) * UINT64_C(0x100000001b3);
            offset += 8;
        } else {
            hash = (hash ^ map[offset++]) * UINT64_C(0x100000001b3);
        }
    }
    return hash == iw_gc_map_hash;
}

#if defined(__linux__) && (defined(__aarch64__) || defined(__x86_64__))
struct elf_image_search { int found, valid; uintptr_t map; size_t map_bytes; };

static int elf_image(struct dl_phdr_info *image, size_t bytes, void *context) {
    struct elf_image_search *search = context;
    int owns_reader = 0, unwind = 0, roots_readonly = 0;
    (void)bytes;
    for (ElfW(Half) i = 0; i < image->dlpi_phnum; ++i) {
        const ElfW(Phdr) *p = image->dlpi_phdr + i;
        if (p->p_type != PT_LOAD || p->p_vaddr > UINTPTR_MAX - image->dlpi_addr) continue;
        uintptr_t begin = image->dlpi_addr + p->p_vaddr;
        uintptr_t reader = (uintptr_t)&iw_gc_roots_start;
        if (reader >= begin && reader - begin < p->p_memsz) owns_reader = 1;
    }
    if (!owns_reader) return 0;
    search->found = 1;
    range_count = 0;
    for (ElfW(Half) i = 0; i < image->dlpi_phnum; ++i) {
        const ElfW(Phdr) *p = image->dlpi_phdr + i;
        if (p->p_type == PT_GNU_EH_FRAME && p->p_memsz != 0) unwind = 1;
        if (p->p_type == PT_GNU_RELRO && p->p_vaddr <= UINTPTR_MAX - image->dlpi_addr) {
            uintptr_t begin = image->dlpi_addr + p->p_vaddr;
            if (search->map >= begin && search->map - begin <= p->p_memsz
                    && search->map_bytes <= p->p_memsz - (search->map - begin)) roots_readonly = 1;
        }
        if (p->p_type != PT_LOAD) continue;
        if (p->p_filesz > p->p_memsz || p->p_vaddr > UINTPTR_MAX - image->dlpi_addr
                || !add_range(image->dlpi_addr + p->p_vaddr, p->p_memsz,
                (unsigned)(p->p_flags & PF_R), (unsigned)(p->p_flags & PF_W), (unsigned)(p->p_flags & PF_X))) return 1;
    }
    search->valid = unwind && roots_readonly;
    return 1;
}
#endif

static int image_maps(const unsigned char **begin, size_t *bytes) {
#if defined(__APPLE__) && defined(__aarch64__)
    for (uint32_t image = 0; image < _dyld_image_count(); ++image) {
        const struct mach_header *header = _dyld_get_image_header(image);
        if (header->magic != MH_MAGIC_64) continue;
        const struct mach_header_64 *wide = (const struct mach_header_64 *)header;
        intptr_t slide = _dyld_get_image_vmaddr_slide(image);
        const unsigned char *command = (const unsigned char *)(wide + 1);
        const unsigned char *limit = command + wide->sizeofcmds;
        int owns_reader = 0;
        range_count = 0;
        *begin = NULL; *bytes = 0;
        for (uint32_t i = 0; i < wide->ncmds; ++i) {
            if ((size_t)(limit - command) < sizeof(struct load_command)) return 0;
            const struct load_command *load = (const struct load_command *)command;
            if (load->cmdsize < sizeof(*load) || load->cmdsize > (size_t)(limit - command)) return 0;
            if (load->cmd == LC_SEGMENT_64) {
                if (load->cmdsize < sizeof(struct segment_command_64)) return 0;
                const struct segment_command_64 *segment = (const struct segment_command_64 *)load;
                if (segment->nsects > (load->cmdsize - sizeof(*segment)) / sizeof(struct section_64)) return 0;
                uintptr_t address = (uintptr_t)segment->vmaddr + (uintptr_t)slide;
                if (!add_range(address, segment->vmsize, (unsigned)(segment->initprot & 1),
                        (unsigned)(segment->initprot & 2), (unsigned)(segment->initprot & 4))) return 0;
                uintptr_t reader = (uintptr_t)&iw_gc_roots_start;
                if (reader >= address && reader - address < segment->vmsize) owns_reader = 1;
                const struct section_64 *sections = (const struct section_64 *)(segment + 1);
                for (uint32_t n = 0; n < segment->nsects; ++n) {
                    if (strncmp(sections[n].segname, "__LLVM_STACKMAPS", 16) == 0
                            && strncmp(sections[n].sectname, "__llvm_stackmaps", 16) == 0) {
                        if (*begin != NULL || sections[n].size > SIZE_MAX) return 0;
                        *begin = (const unsigned char *)((uintptr_t)sections[n].addr + (uintptr_t)slide);
                        *bytes = (size_t)sections[n].size;
                    }
                }
            }
            command += load->cmdsize;
        }
        if (command != limit) return 0;
        if (owns_reader) return *begin == iw_map_begin && *bytes == (size_t)(iw_map_end - iw_map_begin)
                && in_image((uintptr_t)*begin, *bytes, 0, 0);
    }
    return 0;
#elif defined(__linux__) && (defined(__aarch64__) || defined(__x86_64__))
    /* Link preparation renames .llvm_stackmaps to this retained SHF_ALLOC section.
     * These linker boundaries are image-relative relocations, including PIE/ASLR.
     */
    extern const unsigned char __start_ironwood_gc_maps[], __stop_ironwood_gc_maps[];
    *begin = __start_ironwood_gc_maps;
    *bytes = (size_t)(__stop_ironwood_gc_maps - __start_ironwood_gc_maps);
    struct elf_image_search search = {0, 0, (uintptr_t)*begin, *bytes};
    (void)dl_iterate_phdr(elf_image, &search);
    return search.found && search.valid && *bytes != 0 && in_image((uintptr_t)*begin, *bytes, 0, 0);
#else
    (void)begin; (void)bytes;
    return 0;
#endif
}

static int constant_value(const unsigned char *location, uint64_t *value) {
    if (location[0] == 4) { *value = (uint64_t)(int64_t)(int32_t)read32(location + 8); return 1; }
    if (location[0] == 5) {
        uint32_t index = read32(location + 8);
        if (index >= constant_count) return 0;
        *value = read64(constants + 8 * index);
        return 1;
    }
    return 0;
}

static int validate_location(const unsigned char *p) {
    if (p[1] != 0 || read16(p + 2) != 8 || read16(p + 6) != 0) return 0;
    if (p[0] == 4) return read16(p + 4) == 0;
    if (p[0] == 5) return read16(p + 4) == 0 && read32(p + 8) < constant_count;
    // GC statepoints spill base/derived values; native region addresses use Direct.
    // Registers and arbitrary target-specific location expressions are refused.
    if (p[0] != 2 && p[0] != 3) return 0;
#if defined(__aarch64__)
    return read16(p + 4) == 31 || read16(p + 4) == 29;
#elif defined(__x86_64__)
    return read16(p + 4) == 7 || read16(p + 4) == 6;
#else
    return 0;
#endif
}

static int parse_maps(const unsigned char *map, size_t bytes) {
    if (bytes < 16 || map[0] != 3 || map[1] != 0 || read16(map + 2) != 0) return 0;
    uint32_t nf = read32(map + 4), nc = read32(map + 8), nr = read32(map + 12);
    if (!nf || nf > IW_MAX_MAP_ITEMS || nc > IW_MAX_MAP_ITEMS || !nr || nr > IW_MAX_MAP_ITEMS
            || nf > (bytes - 16) / 24) return 0;
    size_t offset = 16 + (size_t)nf * 24;
    if (nc > (bytes - offset) / 8) return 0;
    constants = map + offset; constant_count = nc;
    offset += (size_t)nc * 8;
    records = calloc(nr, sizeof(*records));
    if (records == NULL) return 0;
    for (uint32_t function = 0; function < nf; ++function) {
        const unsigned char *f = map + 16 + 24 * function;
        uintptr_t address = (uintptr_t)read64(f);
        uint64_t stack_size = read64(f + 8), count = read64(f + 16);
        if (!in_image(address, 1, 0, 1) || stack_size == UINT64_MAX || stack_size > 16 * 1024 * 1024
                || count > nr - record_count) return 0;
        for (uint64_t record = 0; record < count; ++record) {
            if (bytes - offset < 16) return 0;
            const unsigned char *r = map + offset;
            uint16_t nl = read16(r + 14);
            uint32_t delta = read32(r + 8);
            if (read16(r + 12) != 0 || nl < 3 || nl > (bytes - offset - 16) / 12
                    || delta > UINTPTR_MAX - address || !in_image(address + delta, 1, 0, 1)) return 0;
            const unsigned char *locations = r + 16;
            for (uint16_t i = 0; i < nl; ++i) if (!validate_location(locations + 12 * i)) return 0;
            uint64_t cc, flags, deopt;
            if (!constant_value(locations, &cc) || (cc != 0 && cc != 8) || !constant_value(locations + 12, &flags)
                    || flags != 0 || !constant_value(locations + 24, &deopt) || deopt > nl - 3u
                    || (nl - 3u - deopt) % 2 != 0) return 0;
            if (deopt != 0) {
                uint64_t magic, slots;
                if (deopt < 2 || !constant_value(locations + 36, &magic) || magic != IW_REGION_MAGIC
                        || !constant_value(locations + 48, &slots) || slots != deopt - 2) return 0;
            }
            for (size_t i = 3 + (size_t)deopt; i < nl; i += 2) {
                uint64_t value;
                if (locations[12 * i] != 3 && (!constant_value(locations + 12 * i, &value) || value != 0)) return 0;
            }
            offset += 16 + (size_t)nl * 12;
            if (offset > SIZE_MAX - 7) return 0;
            offset = (offset + 7) & ~(size_t)7;
            if (offset > bytes || bytes - offset < 4 || read16(map + offset) != 0) return 0;
            uint16_t liveouts = read16(map + offset + 2);
            if (liveouts != 0) return 0;
            offset = (offset + 4 + 7) & ~(size_t)7;
            if (offset > bytes) return 0;
            for (size_t i = 0; i < record_count; ++i) if (records[i].pc == address + delta) return 0;
            records[record_count++] = (struct root_record){address, address + delta, stack_size, locations, nl, (uint16_t)deopt};
        }
    }
    return record_count == nr && offset == bytes;
}

static int slot_table(void *const *slots, size_t count) {
    if (count > IW_MAX_MAP_ITEMS || (count != 0 && !in_image((uintptr_t)slots, count * sizeof(void *), 0, 0))) return 0;
    for (size_t i = 0; i < count; ++i) {
        if ((uintptr_t)slots[i] % sizeof(void *) != 0 || !in_image((uintptr_t)slots[i], sizeof(void *), 1, 0)) return 0;
    }
    return 1;
}

void iw_gc_roots_stop(void) {
    if (started) (void)iw_gc_roots(NULL, 0);
    if (scratch != NULL) memset(scratch, 0, configuration.scratch_capacity * sizeof(void *));
    free(scratch); free(records);
    scratch = NULL; records = NULL; started = 0; record_count = 0;
    configuration = (struct iw_gc_root_config){0};
}

int iw_gc_roots_start(const struct iw_gc_root_config *config) {
    const unsigned char *map = NULL;
    size_t bytes = 0;
    const uint16_t endian = 1;
#if !(defined(__APPLE__) && defined(__aarch64__) && !defined(__arm64e__)) \
        && !(defined(__linux__) && defined(__x86_64__))
    if (config != NULL && config->version == 2) return 0; /* GC4 native qualification gate. */
#endif
    if (started || !IW_ROOT_TARGET_QUALIFIED || config == NULL || !iw_gc_snapshot().disabled
            || sizeof(void *) != 8 || *(const unsigned char *)&endian != 1
            || (config->version != 1 && config->version != 2) || config->pointer_bytes != 8 || config->little_endian != 1
            || config->stackmap_version != 3 || iw_gc_root_abi != config->version || config->native_boundary == NULL
            || config->static_slots != iw_gc_static_roots || config->static_count != iw_gc_static_root_count
            || config->native_slots != iw_gc_native_roots || config->native_count != iw_gc_native_root_count
            || config->scratch_capacity == 0
            || config->scratch_capacity > IW_MAX_MAP_ITEMS) return 0;
    if (config->version == 1 && (iw_gc_owner_requirements || config->exception_owners
            || config->foreign_owners || config->owners != NULL)) return 0;
    if (config->version == 2 && (iw_gc_owner_requirements != 7 || config->exception_owners != 3
            || config->foreign_owners != 4 || config->owners == NULL)) return 0;
    configuration = *config;
    if (!image_maps(&map, &bytes) || !map_integrity(map, bytes) || !parse_maps(map, bytes)
            || !in_image((uintptr_t)config->native_boundary, 1, 0, 1)
            || !slot_table(config->static_slots, config->static_count)
            || !slot_table(config->native_slots, config->native_count)) {
        iw_gc_roots_stop(); return 0;
    }
    if (config->version == 2 && (!in_image((uintptr_t)config->owners, sizeof(*config->owners), 0, 0)
            || config->owners->version != 1 || config->owners->coverage != iw_gc_owner_requirements
            || config->owners->foreign_segments || config->owners->reserved
            || !in_image((uintptr_t)config->owners->enumerate, 1, 0, 1)
            || config->owners->immortal_count > IW_MAX_MAP_ITEMS
            || !in_image((uintptr_t)config->owners->immortal_bases,
                    config->owners->immortal_count * sizeof(void *), 0, 0))) {
        iw_gc_roots_stop(); return 0;
    }
    if (config->version == 2) for (size_t i = 0; i < config->owners->immortal_count; ++i) {
        if (!in_image((uintptr_t)config->owners->immortal_bases[i], sizeof(void *), 1, 0)) {
            iw_gc_roots_stop(); return 0;
        }
    }
    scratch = calloc(config->scratch_capacity, sizeof(void *));
    if (scratch == NULL) { iw_gc_roots_stop(); return 0; }
    started = 1;
    return 1;
}

static int append_root(uintptr_t root) {
    if (root == 0) return 1;
    if (configuration.owners != NULL) for (size_t i = 0; i < configuration.owners->immortal_count; ++i) {
        // Only fixed image-owned bases are admitted. Their reference slots are
        // enumerated by the same owner protocol; no arbitrary native heap scan.
        if (root == (uintptr_t)configuration.owners->immortal_bases[i]) return 1;
    }
    if (root_count == configuration.scratch_capacity || GC_base((void *)root) != (void *)root) return 0;
    scratch[root_count++] = (void *)root;
    return 1;
}

struct root_cursor { uintptr_t sp, fp; };

static int locate(const unsigned char *location, const struct root_cursor *cursor, uintptr_t low, uintptr_t high, uintptr_t *value) {
    uint64_t constant;
    if (constant_value(location, &constant)) { *value = (uintptr_t)constant; return 1; }
    uint16_t number = read16(location + 4);
#if defined(__aarch64__)
    uintptr_t reg = number == 31 ? cursor->sp : cursor->fp;
#elif defined(__x86_64__)
    uintptr_t reg = number == 7 ? cursor->sp : cursor->fp;
#else
    (void)number; (void)cursor;
    return 0;
    uintptr_t reg = 0;
#endif
    int32_t displacement = (int32_t)read32(location + 8);
    uintptr_t address = (uintptr_t)reg + (uintptr_t)(intptr_t)displacement;
    if (address < low || address > high || high - address < sizeof(void *)) return 0;
    if (location[0] == 2) { *value = address; return 1; }
    if (location[0] != 3) return 0;
    memcpy(value, (const void *)address, sizeof(*value));
    return 1;
}

static int scan_frame(const struct root_cursor *cursor, const struct root_record *record, uintptr_t low, uintptr_t high) {
    const unsigned char *locations = record->locations;
    if (record->deopt) {
        for (size_t i = 5; i < 3u + record->deopt; ++i) {
            uintptr_t slot, base;
            if (!locate(locations + 12 * i, cursor, low, high, &slot)
                    || slot < low || slot > high || high - slot < sizeof(void *)) return 0;
            memcpy(&base, (const void *)slot, sizeof(base));
            if (!append_root(base)) return 0;
        }
    }
    for (size_t i = 3u + record->deopt; i < record->count; i += 2) {
        uintptr_t base;
        if (!locate(locations + 12 * i, cursor, low, high, &base) || !append_root(base)) return 0;
    }
    return 1;
}

static const struct root_record *caller_record(uintptr_t pc, uintptr_t function) {
    for (size_t i = 0; i < record_count; ++i) {
        if (records[i].pc == pc && records[i].function == function) return records + i;
    }
    return NULL;
}

#if defined(__linux__) && defined(__x86_64__)
struct elf_walk {
    const struct root_record *pending;
    struct root_cursor cursor;
    const char *reason;
    size_t frames;
    int complete;
};

static _Unwind_Reason_Code elf_frame(struct _Unwind_Context *context, void *argument) {
    struct elf_walk *walk = argument;
    uintptr_t pc = (uintptr_t)_Unwind_GetIP(context), function = (uintptr_t)_Unwind_GetRegionStart(context);
    uintptr_t sp = (uintptr_t)_Unwind_GetCFA(context);
    if (walk->frames++ == 0) {
        walk->reason = "unexpected initial native collection frame";
        return function == (uintptr_t)&iw_gc_experiment_collect ? _URC_NO_REASON : _URC_END_OF_STACK;
    }
    walk->reason = "unwind frame limit";
    if (walk->frames > IW_MAX_MAP_ITEMS) return _URC_END_OF_STACK;
    // GCC's context CFA is this suspended frame's SP. Delay scanning until the
    // next callback supplies the actual parent SP, rather than inferring an
    // extent from a frame size or treating an unpromoted alloca as an SSA root.
    if (walk->pending != NULL) {
        walk->reason = "unwind stack extent";
        if (sp <= walk->cursor.sp || sp - walk->cursor.sp > 16 * 1024 * 1024) return _URC_END_OF_STACK;
        walk->reason = "root location/base or scratch capacity";
        if (!scan_frame(&walk->cursor, walk->pending, walk->cursor.sp, sp)) return _URC_END_OF_STACK;
        ++last_frames;
    }
    if (function == (uintptr_t)configuration.native_boundary) {
        walk->complete = 1;
        return _URC_END_OF_STACK;
    }
    walk->reason = "missing exact caller map";
    walk->pending = caller_record(pc, function);
    if (walk->pending == NULL) return _URC_END_OF_STACK;
    walk->cursor = (struct root_cursor){sp, 0};
    for (size_t i = 0; i < walk->pending->count; ++i) {
        const unsigned char *location = walk->pending->locations + 12 * i;
        if ((location[0] == 2 || location[0] == 3) && read16(location + 4) == 6) {
            walk->cursor.fp = (uintptr_t)_Unwind_GetGR(context, 6);
            break;
        }
    }
#ifdef IW_GC_ROOT_DIAGNOSTICS
    fprintf(stderr, "GC3 ELF frame pc=%p function=%p sp=%p fp=%p\n", (void *)pc, (void *)function,
            (void *)walk->cursor.sp, (void *)walk->cursor.fp);
#endif
    return _URC_NO_REASON;
}
#endif

__attribute__((noinline)) void iw_gc_experiment_collect(void) {
    const char *reason = "initialization/context";
    root_count = 0; last_frames = 0;
    if (!started || !iw_gc_snapshot().disabled) goto refused;
#if defined(__APPLE__) && defined(__aarch64__)
    unw_context_t context;
    unw_cursor_t cursor;
    int complete = 0;
    if (unw_getcontext(&context) != 0 || unw_init_local(&cursor, &context) != 0) goto refused;
    // Exactly this known native collection frame precedes the managed callers.
    // Every subsequent frame must match a statepoint until the approved boundary.
    for (size_t frames = 0; frames < IW_MAX_MAP_ITEMS; ++frames) {
        reason = "unwind step";
        if (unw_step(&cursor) <= 0) goto refused;
        unw_word_t pc, sp;
        unw_proc_info_t info;
        reason = "unwind registers/coverage";
        if (unw_get_reg(&cursor, UNW_REG_IP, &pc) != 0 || unw_get_reg(&cursor, UNW_REG_SP, &sp) != 0
                || unw_get_proc_info(&cursor, &info) != 0) goto refused;
        if (info.start_ip == (unw_word_t)(uintptr_t)configuration.native_boundary) { complete = 1; break; }
        const struct root_record *record = caller_record((uintptr_t)pc, (uintptr_t)info.start_ip);
#ifdef IW_GC_ROOT_DIAGNOSTICS
        fprintf(stderr, "GC3 frame pc=%p function=%p mapped=%p sp=%p\n", (void *)(uintptr_t)pc,
                (void *)(uintptr_t)info.start_ip, record == NULL ? NULL : (void *)record->function, (void *)(uintptr_t)sp);
#endif
        // LLVM maps use the exact return PC. Do not subtract one or silently skip.
        reason = "missing exact caller map";
        if (record == NULL) goto refused;
        unw_cursor_t parent = cursor;
        unw_word_t parent_sp;
        reason = "unwind stack extent";
        if (unw_step(&parent) <= 0 || unw_get_reg(&parent, UNW_REG_SP, &parent_sp) != 0
                || parent_sp <= sp || parent_sp - sp > 16 * 1024 * 1024) goto refused;
        reason = "root location/base or scratch capacity";
        unw_word_t fp;
        if (unw_get_reg(&cursor, 29, &fp) != 0) goto refused;
        struct root_cursor location_cursor = {(uintptr_t)sp, (uintptr_t)fp};
        if (!scan_frame(&location_cursor, record, (uintptr_t)sp, (uintptr_t)parent_sp)) goto refused;
        ++last_frames;
    }
    reason = "missing native boundary";
    if (!complete || last_frames == 0) goto refused;
#elif defined(__linux__) && defined(__x86_64__)
    struct elf_walk walk = {0};
    walk.reason = "unwind coverage/native boundary";
    (void)_Unwind_Backtrace(elf_frame, &walk);
    reason = walk.reason;
    if (!walk.complete || last_frames == 0) goto refused;
#else
    reason = "unqualified native unwinder";
    goto refused;
#endif
    if (last_frames > max_frames) max_frames = last_frames;
    for (size_t table = 0; table < 2; ++table) {
        void *const *slots = table ? configuration.native_slots : configuration.static_slots;
        size_t count = table ? configuration.native_count : configuration.static_count;
        for (size_t i = 0; i < count; ++i) {
            uintptr_t base;
            memcpy(&base, slots[i], sizeof(base));
            reason = "static/native allocation base or scratch capacity";
            if (!append_root(base)) goto refused;
        }
    }
    reason = "native owner coverage/base or scratch capacity";
    if (configuration.owners != NULL && !configuration.owners->enumerate(append_root)) goto refused;
    last_count = root_count;
    reason = "engine roots/collection";
    if (!iw_gc_roots(root_count ? scratch : NULL, root_count) || !iw_gc_collect()) goto refused;
    if (!iw_gc_roots(NULL, 0)) goto refused;
    memset(scratch, 0, root_count * sizeof(void *));
    root_count = 0;
    return;
refused:
    if (started) (void)iw_gc_roots(NULL, 0);
    if (scratch != NULL) memset(scratch, 0, configuration.scratch_capacity * sizeof(void *));
    fprintf(stderr, "GC3 root preparation refused before resuming managed code: %s\n", reason);
    exit(86);
}

size_t iw_gc_roots_last_count(void) { return last_count; }
size_t iw_gc_roots_last_frames(void) { return last_frames; }
size_t iw_gc_roots_max_frames(void) { return max_frames; }
