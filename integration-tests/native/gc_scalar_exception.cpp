// SPDX-License-Identifier: MIT OR Apache-2.0
// Fixed GC3 fixture boundary: an int exception has no Ironwood references and
// cannot collect or call back. The catch ends before managed cleanup collects.
// This is not the production Throwable/unwind-wrapper lifetime implementation.
#include <cxxabi.h>
#include <cstdlib>

extern "C" void iw_gc_test_raise() { throw 7; }

extern "C" void iw_gc_test_catch(void *handle) noexcept {
    const int *value = static_cast<const int *>(__cxxabiv1::__cxa_begin_catch(handle));
    if (*value != 7) std::abort();
    __cxxabiv1::__cxa_end_catch();
}
