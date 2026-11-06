// SPDX-License-Identifier: MIT OR Apache-2.0
/* Appended only to the generated qualification adapter. No hot-path hooks.
 * Measurements cover this JVM process and its private Ironwood image. */
#include <mach/mach.h>
#include <libproc.h>
#include <malloc/malloc.h>
#include <sys/resource.h>
#include <unistd.h>
#include "ironwood_gc_engine.h"

JNIEXPORT jlongArray JNICALL Java_Gc8Consumer_observe(JNIEnv *env, jclass type) {
    (void)type;
    struct iw_gc_snapshot state = iw_gc_snapshot();
    struct task_vm_info vm = {0};
    mach_msg_type_number_t count = TASK_VM_INFO_COUNT;
    if (task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&vm, &count) != KERN_SUCCESS) abort();
    struct rusage usage;
    if (getrusage(RUSAGE_SELF, &usage) != 0) abort();
    int fd_bytes = proc_pidinfo(getpid(), PROC_PIDLISTFDS, 0, NULL, 0);
    if (fd_bytes <= 0) abort();
    void *fds = malloc((size_t)fd_bytes);
    if (fds == NULL) abort();
    int actual = proc_pidinfo(getpid(), PROC_PIDLISTFDS, 0, fds, fd_bytes);
    free(fds);
    if (actual < 0 || actual == fd_bytes) abort(); /* Refuse a truncated list. */
    malloc_statistics_t native;
    malloc_zone_statistics(NULL, &native);
    jlong anchors = 0, facades = 0;
    for (struct iw_gc_anchor *a = iw_gc_anchors; a != NULL; a = a->next) anchors++;
    for (struct iw_gc_facade *f = iw_gc_facades; f != NULL; f = f->next) facades++;
    const jlong values[] = {(jlong)state.collections, (jlong)state.total, (jlong)state.live,
        (jlong)state.sidecars, (jlong)state.administration, (jlong)state.heap_bytes,
        (jlong)state.free_bytes, (jlong)state.reclaimed_since_collection, state.disabled,
        (jlong)vm.resident_size, (jlong)usage.ru_maxrss, (jlong)vm.phys_footprint,
        (jlong)native.size_in_use, (jlong)native.size_allocated,
        actual / (jlong)sizeof(struct proc_fdinfo), gc8_globals, anchors, facades};
    jlongArray result = (*env)->NewLongArray(env, sizeof(values) / sizeof(values[0]));
    if (result != NULL) (*env)->SetLongArrayRegion(env, result, 0, sizeof(values) / sizeof(values[0]), values);
    return result;
}
