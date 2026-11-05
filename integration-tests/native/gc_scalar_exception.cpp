// SPDX-License-Identifier: MIT OR Apache-2.0
// Fixed GC3 fixture boundary: an int exception has no Ironwood references and
// cannot collect or call back. The catch ends before managed cleanup collects.
// This is not the production Throwable/unwind-wrapper lifetime implementation.
#include <stdlib.h>

// The platform's Itanium exception ABI, also used by the native backend.
// Keep this fixed scalar fixture independent of C++ development headers.
extern "C" void *__cxa_begin_catch(void *);
extern "C" void __cxa_end_catch(void);

extern "C" void iw_gc_test_raise() { throw 7; }

extern "C" void iw_gc_test_catch(void *handle) noexcept {
    const int *value = static_cast<const int *>(__cxa_begin_catch(handle));
    if (*value != 7) abort();
    __cxa_end_catch();
}
