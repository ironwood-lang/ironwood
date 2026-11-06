// SPDX-License-Identifier: MIT OR Apache-2.0
/* Appended only to the generated qualification adapter. No hot-path hooks.
 * Measurements cover this JVM process and its private Ironwood image. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#if defined(__APPLE__)
#include <mach/mach.h>
#include <libproc.h>
#include <malloc/malloc.h>
#else
#include <dirent.h>
#include <malloc.h>
#endif
#include <sys/resource.h>
#include <unistd.h>
#include "ironwood_gc_engine.h"

JNIEXPORT jlongArray JNICALL Java_Gc8Consumer_observe(JNIEnv *env, jclass type) {
    (void)type;
    struct iw_gc_snapshot state = iw_gc_snapshot();
    jlong resident, peak, platform_resident, native_used, native_capacity, descriptors;
    struct rusage usage;
    if (getrusage(RUSAGE_SELF, &usage) != 0) abort();
#if defined(__APPLE__)
    struct task_vm_info vm = {0};
    mach_msg_type_number_t count = TASK_VM_INFO_COUNT;
    if (task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&vm, &count) != KERN_SUCCESS) abort();
    int fd_bytes = proc_pidinfo(getpid(), PROC_PIDLISTFDS, 0, NULL, 0);
    if (fd_bytes <= 0) abort();
    void *fds = malloc((size_t)fd_bytes);
    if (fds == NULL) abort();
    int actual = proc_pidinfo(getpid(), PROC_PIDLISTFDS, 0, fds, fd_bytes);
    free(fds);
    if (actual < 0 || actual == fd_bytes) abort(); /* Refuse a truncated list. */
    malloc_statistics_t native;
    malloc_zone_statistics(NULL, &native);
    resident = (jlong)vm.resident_size;
    peak = (jlong)usage.ru_maxrss;
    platform_resident = (jlong)vm.phys_footprint;
    native_used = (jlong)native.size_in_use;
    native_capacity = (jlong)native.size_allocated;
    descriptors = actual / (jlong)sizeof(struct proc_fdinfo);
#else
    /* statm RSS and getrusage high water are Linux process observations.
     * PSS is proportional resident memory, not a cgroup charged-memory census.
     * mallinfo uses the pinned glibc 2.17 ABI; refuse signed-counter overflow. */
    unsigned long pages, virtual_pages;
    FILE *memory = fopen("/proc/self/statm", "r");
    if (memory == NULL || fscanf(memory, "%lu %lu", &virtual_pages, &pages) != 2) abort();
    if (fclose(memory) != 0) abort();
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) abort();
    resident = (jlong)pages * page_size;
    peak = (jlong)usage.ru_maxrss * 1024;
    platform_resident = -1;
    memory = fopen("/proc/self/smaps_rollup", "r");
    if (memory != NULL) {
        char line[512];
        unsigned long long kib;
        while (fgets(line, sizeof(line), memory) != NULL) {
            if (strncmp(line, "Pss:", 4) == 0 && sscanf(line + 4, "%llu", &kib) == 1) {
                platform_resident = (jlong)kib * 1024;
                break;
            }
        }
        if (fclose(memory) != 0) abort();
    }
    DIR *directory = opendir("/proc/self/fd");
    if (directory == NULL) abort();
    descriptors = -1; /* Exclude this observer's open directory descriptor. */
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        if (entry->d_name[0] != '.') descriptors++;
    }
    if (closedir(directory) != 0 || descriptors < 0) abort();
    struct mallinfo native = mallinfo();
    if (native.uordblks < 0 || native.hblkhd < 0 || native.arena < 0) abort();
    native_used = (jlong)native.uordblks + native.hblkhd;
    native_capacity = (jlong)native.arena + native.hblkhd;
#endif
    jlong anchors = 0, facades = 0;
    for (struct iw_gc_anchor *a = iw_gc_anchors; a != NULL; a = a->next) anchors++;
    for (struct iw_gc_facade *f = iw_gc_facades; f != NULL; f = f->next) facades++;
    const jlong values[] = {(jlong)state.collections, (jlong)state.total, (jlong)state.live,
        (jlong)state.sidecars, (jlong)state.administration, (jlong)state.heap_bytes,
        (jlong)state.free_bytes, (jlong)state.reclaimed_since_collection, state.disabled,
        resident, peak, platform_resident, native_used, native_capacity,
        descriptors, gc8_globals, anchors, facades};
    jlongArray result = (*env)->NewLongArray(env, sizeof(values) / sizeof(values[0]));
    if (result != NULL) (*env)->SetLongArrayRegion(env, result, 0, sizeof(values) / sizeof(values[0]), values);
    return result;
}
