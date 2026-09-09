// SPDX-License-Identifier: MIT OR Apache-2.0

// Linux hides POSIX clock and file-descriptor declarations in strict C11 mode.
#if defined(__linux__) && !defined(_POSIX_C_SOURCE)
#define _POSIX_C_SOURCE 200809L
#endif

#include "../include/ironwood_runtime.h"
#include "../include/ironwood_case.h"

#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <stdatomic.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <time.h>
#include <sys/utsname.h>
#include <unwind.h>
#include <unistd.h>

#if defined(__APPLE__)
#include <mach-o/getsect.h>
#include <mach-o/loader.h>
extern const struct mach_header_64 _mh_execute_header;
#elif defined(__linux__)
extern const uint8_t __start_ironwood_trace[] __attribute__((weak));
extern const uint8_t __stop_ironwood_trace[] __attribute__((weak));
#endif

enum ironwood_type_kind {
    IRONWOOD_TYPE_CLASS = 0,
    IRONWOOD_TYPE_ARRAY = 1
};

enum ironwood_array_element_kind {
    IRONWOOD_ARRAY_BOOLEAN = 1,
    IRONWOOD_ARRAY_BYTE = 2,
    IRONWOOD_ARRAY_SHORT = 3,
    IRONWOOD_ARRAY_CHAR = 4,
    IRONWOOD_ARRAY_INT = 5,
    IRONWOOD_ARRAY_LONG = 6,
    IRONWOOD_ARRAY_FLOAT = 7,
    IRONWOOD_ARRAY_DOUBLE = 8,
    IRONWOOD_ARRAY_REFERENCE = 9
};

struct ironwood_type_info {
    uint32_t type_id;
    const void *dispatch;
    const unsigned char *membership;
    const char *name;
    uint32_t kind;
    void (*destructor)(void *);
    void (*constructor_rollback)(void *);
    _Bool to_string_returns_owned_fresh;
    _Bool localized_message_returns_owned_fresh;
};

/* Compiler validation enforces this base-first Throwable layout.
 * The reserved integer slot owns native metadata; it is never a public handle. */
struct ironwood_throwable {
    const struct ironwood_type_info *type;
    void *message;
    void *cause;
    uint64_t trace_state;
};

struct ironwood_exception {
    void *object;
    _Bool emergency;
    struct _Unwind_Exception unwind;
};

struct ironwood_secondary_exception {
    void *object;
    struct ironwood_secondary_exception *next;
};

struct ironwood_trace_element {
    const struct ironwood_trace_site *site;
};

enum ironwood_trace_state {
    IRONWOOD_TRACE_NOT_CAPTURED = 0,
    IRONWOOD_TRACE_CAPTURED = 1,
    IRONWOOD_TRACE_UNAVAILABLE = 2
};

struct ironwood_exception_metadata {
    void *primary;
    int32_t secondary_count;
    struct ironwood_secondary_exception *first_secondary;
    struct ironwood_secondary_exception *last_secondary;
    enum ironwood_trace_state trace_state;
    int32_t trace_count;
    struct ironwood_trace_element *trace;
    _Bool trace_truncated;
};

struct ironwood_array {
    const struct ironwood_type_info *type;
    size_t length;
    size_t element_size;
    uint32_t element_kind;
    uint32_t reserved;
    unsigned char data[];
};

struct ironwood_string {
    const struct ironwood_type_info *type;
    int32_t utf16_length;
    int32_t utf8_length;
    uint16_t units[];
};

struct ironwood_print_stream {
    const struct ironwood_type_info *type;
    int32_t channel;
};

_Static_assert(offsetof(struct ironwood_string, type) == 0,
        "Ironwood String type descriptor must lead the object");
_Static_assert(offsetof(struct ironwood_string, utf16_length) == sizeof(void *),
        "Ironwood String UTF-16 length ABI changed");
_Static_assert(offsetof(struct ironwood_string, utf8_length)
                == sizeof(void *) + sizeof(int32_t),
        "Ironwood String UTF-8 length ABI changed");
_Static_assert(offsetof(struct ironwood_string, units)
                == sizeof(void *) + 2 * sizeof(int32_t),
        "Ironwood String UTF-16 tail ABI changed");
_Static_assert(sizeof(struct ironwood_string) == offsetof(struct ironwood_string, units),
        "Ironwood String flexible-tail header has unexpected padding");
_Static_assert(offsetof(struct ironwood_print_stream, channel) == sizeof(void *),
        "Ironwood PrintStream channel ABI changed");
_Static_assert(offsetof(struct ironwood_array, type) == 0,
        "Ironwood array type descriptor must lead the object");
_Static_assert(offsetof(struct ironwood_array, length) == sizeof(void *),
        "Ironwood array length ABI changed");
_Static_assert(offsetof(struct ironwood_array, data)
                == sizeof(void *) + 2 * sizeof(size_t) + 2 * sizeof(uint32_t),
        "Ironwood array element-tail ABI changed");
_Static_assert(sizeof(struct ironwood_array) == offsetof(struct ironwood_array, data),
        "Ironwood array flexible-tail header has unexpected padding");
_Static_assert(offsetof(struct ironwood_string_concat_part, payload) == 8,
        "Ironwood String-concatenation payload ABI changed");
_Static_assert(sizeof(struct ironwood_string_concat_part) == 16,
        "Ironwood String-concatenation part ABI changed");

/* Diagnostic only: relaxed ordering is sufficient for an exact process-wide
 * event count, including when language threads are added later. */
static _Atomic uint64_t allocation_count = UINT64_C(0);
static _Atomic uint64_t live_allocation_count = UINT64_C(0);

static const struct ironwood_trace_site *trace_sites;
static int32_t trace_site_count;
static const struct ironwood_trace_function *trace_functions;
static int32_t trace_function_count;
static const uint8_t *trace_section;
static size_t trace_section_size;

#define IRONWOOD_EMERGENCY_PC_CAPACITY 96
#define IRONWOOD_EMERGENCY_TRACE_CAPACITY 64

static struct ironwood_exception_metadata implicit_failure_metadata;
static struct ironwood_exception_metadata emergency_association_metadata;
static struct ironwood_secondary_exception emergency_secondary;
static struct ironwood_trace_element implicit_failure_trace[IRONWOOD_EMERGENCY_TRACE_CAPACITY];
static _Bool emergency_association_metadata_in_use;
static _Bool emergency_secondary_in_use;
static struct ironwood_exception_metadata *emergency_secondary_owner;
static void *active_implicit_failure;
static _Thread_local struct ironwood_exception emergency_exception;
static _Thread_local _Bool emergency_exception_in_use;
static _Bool allocation_limit_initialized;
static _Bool allocation_limit_enabled;
static uint64_t allocation_limit;
static uint64_t limited_allocation_successes;

enum ironwood_file_error {
    IRONWOOD_FILE_ERROR_NONE = 0,
    IRONWOOD_FILE_ERROR_NO_SUCH_FILE = 1,
    IRONWOOD_FILE_ERROR_PERMISSION = 2,
    IRONWOOD_FILE_ERROR_NOT_DIRECTORY = 3,
    IRONWOOD_FILE_ERROR_IS_DIRECTORY = 4,
    IRONWOOD_FILE_ERROR_INVALID_UTF8 = 5,
    IRONWOOD_FILE_ERROR_TOO_LARGE = 6,
    IRONWOOD_FILE_ERROR_ALREADY_EXISTS = 7,
    IRONWOOD_FILE_ERROR_DIRECTORY_NOT_EMPTY = 8,
    IRONWOOD_FILE_ERROR_OTHER = 9
};

static _Thread_local int32_t last_file_error;

static _Noreturn void fatal_emergency(const char *message) {
    fputs(message, stderr);
    fputc('\n', stderr);
    exit(1);
}

static void initialize_allocation_limit(void) {
    if (allocation_limit_initialized) {
        return;
    }
    allocation_limit_initialized = 1;
    const char *text = getenv("IRONWOOD_ALLOCATION_LIMIT");
    if (text == NULL || *text == '\0') {
        return;
    }
    uint64_t value = 0;
    for (const unsigned char *digit = (const unsigned char *) text;
         *digit != '\0'; digit++) {
        if (*digit < (unsigned char) '0' || *digit > (unsigned char) '9') {
            return;
        }
        uint64_t decoded = (uint64_t) (*digit - (unsigned char) '0');
        if (value > (UINT64_MAX - decoded) / UINT64_C(10)) {
            return;
        }
        value = value * UINT64_C(10) + decoded;
    }
    allocation_limit = value;
    allocation_limit_enabled = 1;
}

static _Bool allocation_limit_reached(void *allocation_failure) {
    if (allocation_failure == NULL) {
        return 0;
    }
    initialize_allocation_limit();
    return allocation_limit_enabled
            && limited_allocation_successes >= allocation_limit;
}

static void record_limited_allocation(void *allocation_failure) {
    if (allocation_failure != NULL && allocation_limit_enabled) {
        limited_allocation_successes++;
    }
}

static _Noreturn void raise_allocation_failure(void *allocation_failure);

static void *try_allocate_object(size_t size, const void *object_type,
                                 void *allocation_failure) {
    if (allocation_limit_reached(allocation_failure)) {
        return NULL;
    }
    void *allocation = calloc(1, size == 0 ? 1 : size);
    if (allocation == NULL) {
        return NULL;
    }
    if (object_type != NULL) {
        *(const void **) allocation = object_type;
    }
    atomic_fetch_add_explicit(&allocation_count, UINT64_C(1), memory_order_relaxed);
    atomic_fetch_add_explicit(&live_allocation_count, UINT64_C(1), memory_order_relaxed);
    record_limited_allocation(allocation_failure);
    return allocation;
}

static struct ironwood_array *try_allocate_array(int32_t length, size_t element_size,
                                                  uint32_t element_kind,
                                                  const void *array_type,
                                                  void *allocation_failure) {
    if (length < 0) {
        abort();
    }
    size_t count = (size_t) length;
    if (element_size != 0
            && count > (SIZE_MAX - sizeof(struct ironwood_array)) / element_size) {
        return NULL;
    }
    size_t size = sizeof(struct ironwood_array) + count * element_size;
    struct ironwood_array *array = try_allocate_object(size, array_type,
            allocation_failure);
    if (array == NULL) {
        return NULL;
    }
    array->length = count;
    array->element_size = element_size;
    array->element_kind = element_kind;
    return array;
}

static int32_t utf8_length_of_utf16(const uint16_t *units, int32_t length) {
    int64_t bytes = 0;
    for (int32_t index = 0; index < length; index++) {
        uint32_t code_point = units[index];
        if (code_point >= UINT32_C(0xD800) && code_point <= UINT32_C(0xDBFF)
                && index + 1 < length
                && units[index + 1] >= UINT16_C(0xDC00)
                && units[index + 1] <= UINT16_C(0xDFFF)) {
            code_point = UINT32_C(0x10000)
                    + ((code_point - UINT32_C(0xD800)) << 10)
                    + (uint32_t) (units[++index] - UINT16_C(0xDC00));
        } else if (code_point >= UINT32_C(0xD800) && code_point <= UINT32_C(0xDFFF)) {
            code_point = UINT32_C(0xFFFD);
        }
        bytes += code_point <= UINT32_C(0x7F) ? 1
                : code_point <= UINT32_C(0x7FF) ? 2
                : code_point <= UINT32_C(0xFFFF) ? 3 : 4;
        if (bytes > INT32_MAX) {
            abort();
        }
    }
    return (int32_t) bytes;
}

static struct ironwood_string *allocate_string(int32_t length,
                                                const struct ironwood_type_info *type,
                                                void *allocation_failure) {
    if (length < 0 || type == NULL) {
        abort();
    }
    if ((size_t) length
            > (SIZE_MAX - sizeof(struct ironwood_string)) / sizeof(uint16_t)) {
        raise_allocation_failure(allocation_failure);
    }
    size_t size = sizeof(struct ironwood_string) + (size_t) length * sizeof(uint16_t);
    struct ironwood_string *result = try_allocate_object(size, type, allocation_failure);
    if (result == NULL) {
        raise_allocation_failure(allocation_failure);
    }
    result->utf16_length = length;
    return result;
}

static struct ironwood_string *try_allocate_string(
        int32_t length, const struct ironwood_type_info *type, void *allocation_failure) {
    if (length < 0 || type == NULL
            || (size_t) length
            > (SIZE_MAX - sizeof(struct ironwood_string)) / sizeof(uint16_t)) {
        return NULL;
    }
    size_t size = sizeof(struct ironwood_string) + (size_t) length * sizeof(uint16_t);
    struct ironwood_string *result = try_allocate_object(size, type, allocation_failure);
    if (result != NULL) {
        result->utf16_length = length;
    }
    return result;
}

static _Bool is_utf8_continuation(unsigned char value) {
    return (value & UINT8_C(0xC0)) == UINT8_C(0x80);
}

static uint32_t decode_utf8_code_point(const unsigned char *bytes, size_t length,
                                       size_t *index) {
    size_t start = *index;
    unsigned char first = bytes[start];
    *index = start + 1;
    if (first <= UINT8_C(0x7F)) {
        return first;
    }
    if (first >= UINT8_C(0xC2) && first <= UINT8_C(0xDF)
            && start + 1 < length && is_utf8_continuation(bytes[start + 1])) {
        *index = start + 2;
        return ((uint32_t) (first & UINT8_C(0x1F)) << 6)
                | (uint32_t) (bytes[start + 1] & UINT8_C(0x3F));
    }
    if (start + 2 < length && is_utf8_continuation(bytes[start + 2])) {
        unsigned char second = bytes[start + 1];
        _Bool validSecond = first == UINT8_C(0xE0)
                ? second >= UINT8_C(0xA0) && second <= UINT8_C(0xBF)
                : first == UINT8_C(0xED)
                ? second >= UINT8_C(0x80) && second <= UINT8_C(0x9F)
                : (first >= UINT8_C(0xE1) && first <= UINT8_C(0xEC))
                || (first >= UINT8_C(0xEE) && first <= UINT8_C(0xEF))
                ? is_utf8_continuation(second) : 0;
        if (validSecond) {
            *index = start + 3;
            return ((uint32_t) (first & UINT8_C(0x0F)) << 12)
                    | ((uint32_t) (second & UINT8_C(0x3F)) << 6)
                    | (uint32_t) (bytes[start + 2] & UINT8_C(0x3F));
        }
    }
    if (start + 3 < length && is_utf8_continuation(bytes[start + 2])
            && is_utf8_continuation(bytes[start + 3])) {
        unsigned char second = bytes[start + 1];
        _Bool validSecond = first == UINT8_C(0xF0)
                ? second >= UINT8_C(0x90) && second <= UINT8_C(0xBF)
                : first == UINT8_C(0xF4)
                ? second >= UINT8_C(0x80) && second <= UINT8_C(0x8F)
                : first >= UINT8_C(0xF1) && first <= UINT8_C(0xF3)
                ? is_utf8_continuation(second) : 0;
        if (validSecond) {
            *index = start + 4;
            return ((uint32_t) (first & UINT8_C(0x07)) << 18)
                    | ((uint32_t) (second & UINT8_C(0x3F)) << 12)
                    | ((uint32_t) (bytes[start + 2] & UINT8_C(0x3F)) << 6)
                    | (uint32_t) (bytes[start + 3] & UINT8_C(0x3F));
        }
    }
    return UINT32_C(0xFFFD);
}

static struct ironwood_string *string_from_utf8_bytes(
        const unsigned char *bytes, size_t byte_length,
        const struct ironwood_type_info *type, void *allocation_failure) {
    if (bytes == NULL && byte_length != 0 || type == NULL) {
        abort();
    }
    if (byte_length > (size_t) INT32_MAX) {
        abort();
    }
    int32_t unit_length = 0;
    for (size_t index = 0; index < byte_length;) {
        uint32_t code_point = decode_utf8_code_point(bytes, byte_length, &index);
        unit_length += code_point <= UINT32_C(0xFFFF) ? 1 : 2;
    }
    struct ironwood_string *result = allocate_string(unit_length, type, allocation_failure);
    int32_t position = 0;
    for (size_t index = 0; index < byte_length;) {
        uint32_t code_point = decode_utf8_code_point(bytes, byte_length, &index);
        if (code_point <= UINT32_C(0xFFFF)) {
            result->units[position++] = (uint16_t) code_point;
        } else {
            code_point -= UINT32_C(0x10000);
            result->units[position++] = (uint16_t) (UINT32_C(0xD800) + (code_point >> 10));
            result->units[position++] = (uint16_t) (UINT32_C(0xDC00)
                    + (code_point & UINT32_C(0x3FF)));
        }
    }
    result->utf8_length = utf8_length_of_utf16(result->units, unit_length);
    return result;
}

static struct ironwood_string *string_from_utf8(
        const char *text, const struct ironwood_type_info *type, void *allocation_failure) {
    if (text == NULL) {
        abort();
    }
    return string_from_utf8_bytes((const unsigned char *) text, strlen(text), type,
            allocation_failure);
}

static size_t valid_utf8_sequence_length(const unsigned char *bytes, size_t length,
                                         size_t start) {
    unsigned char first = bytes[start];
    if (first <= UINT8_C(0x7F)) {
        return 1;
    }
    if (first >= UINT8_C(0xC2) && first <= UINT8_C(0xDF)
            && start + 1 < length && is_utf8_continuation(bytes[start + 1])) {
        return 2;
    }
    if (start + 2 < length && is_utf8_continuation(bytes[start + 2])) {
        unsigned char second = bytes[start + 1];
        _Bool valid_second = first == UINT8_C(0xE0)
                ? second >= UINT8_C(0xA0) && second <= UINT8_C(0xBF)
                : first == UINT8_C(0xED)
                ? second >= UINT8_C(0x80) && second <= UINT8_C(0x9F)
                : (first >= UINT8_C(0xE1) && first <= UINT8_C(0xEC))
                || (first >= UINT8_C(0xEE) && first <= UINT8_C(0xEF))
                ? is_utf8_continuation(second) : 0;
        if (valid_second) {
            return 3;
        }
    }
    if (start + 3 < length && is_utf8_continuation(bytes[start + 2])
            && is_utf8_continuation(bytes[start + 3])) {
        unsigned char second = bytes[start + 1];
        _Bool valid_second = first == UINT8_C(0xF0)
                ? second >= UINT8_C(0x90) && second <= UINT8_C(0xBF)
                : first == UINT8_C(0xF4)
                ? second >= UINT8_C(0x80) && second <= UINT8_C(0x8F)
                : first >= UINT8_C(0xF1) && first <= UINT8_C(0xF3)
                ? is_utf8_continuation(second) : 0;
        if (valid_second) {
            return 4;
        }
    }
    return 0;
}

static _Bool valid_utf8(const unsigned char *bytes, size_t length) {
    for (size_t index = 0; index < length;) {
        size_t sequence = valid_utf8_sequence_length(bytes, length, index);
        if (sequence == 0) {
            return 0;
        }
        index += sequence;
    }
    return 1;
}

static struct ironwood_string *try_string_from_valid_utf8_bytes(
        const unsigned char *bytes, size_t byte_length,
        const struct ironwood_type_info *type, void *allocation_failure) {
    if (bytes == NULL && byte_length != 0 || type == NULL
            || byte_length > (size_t) INT32_MAX) {
        return NULL;
    }
    int32_t unit_length = 0;
    for (size_t index = 0; index < byte_length;) {
        uint32_t code_point = decode_utf8_code_point(bytes, byte_length, &index);
        unit_length += code_point <= UINT32_C(0xFFFF) ? 1 : 2;
    }
    struct ironwood_string *result = try_allocate_string(unit_length, type,
            allocation_failure);
    if (result == NULL) {
        return NULL;
    }
    int32_t position = 0;
    for (size_t index = 0; index < byte_length;) {
        uint32_t code_point = decode_utf8_code_point(bytes, byte_length, &index);
        if (code_point <= UINT32_C(0xFFFF)) {
            result->units[position++] = (uint16_t) code_point;
        } else {
            code_point -= UINT32_C(0x10000);
            result->units[position++] = (uint16_t) (UINT32_C(0xD800) + (code_point >> 10));
            result->units[position++] = (uint16_t) (UINT32_C(0xDC00)
                    + (code_point & UINT32_C(0x3FF)));
        }
    }
    result->utf8_length = utf8_length_of_utf16(result->units, unit_length);
    return result;
}

static int32_t encode_utf8_code_point(char *destination, uint32_t code_point) {
    if (code_point <= UINT32_C(0x7F)) {
        destination[0] = (char) code_point;
        return 1;
    }
    if (code_point <= UINT32_C(0x7FF)) {
        destination[0] = (char) (UINT32_C(0xC0) | (code_point >> 6));
        destination[1] = (char) (UINT32_C(0x80) | (code_point & UINT32_C(0x3F)));
        return 2;
    }
    if (code_point <= UINT32_C(0xFFFF)) {
        destination[0] = (char) (UINT32_C(0xE0) | (code_point >> 12));
        destination[1] = (char) (UINT32_C(0x80) | ((code_point >> 6) & UINT32_C(0x3F)));
        destination[2] = (char) (UINT32_C(0x80) | (code_point & UINT32_C(0x3F)));
        return 3;
    }
    destination[0] = (char) (UINT32_C(0xF0) | (code_point >> 18));
    destination[1] = (char) (UINT32_C(0x80) | ((code_point >> 12) & UINT32_C(0x3F)));
    destination[2] = (char) (UINT32_C(0x80) | ((code_point >> 6) & UINT32_C(0x3F)));
    destination[3] = (char) (UINT32_C(0x80) | (code_point & UINT32_C(0x3F)));
    return 4;
}

enum { IRONWOOD_PATH_STACK_CAPACITY = 4096, IRONWOOD_IO_CHUNK_CAPACITY = 8192 };

static char *string_to_utf8_buffer(const struct ironwood_string *value,
                                  char *buffer, size_t buffer_capacity,
                                  void *allocation_failure) {
    if (value == NULL || value->utf16_length < 0 || value->utf8_length < 0) {
        abort();
    }
    size_t capacity = (size_t) value->utf8_length + 1U;
    char *result = capacity <= buffer_capacity ? buffer : malloc(capacity);
    if (result == NULL) {
        raise_allocation_failure(allocation_failure);
    }
    int32_t position = 0;
    for (int32_t index = 0; index < value->utf16_length; index++) {
        uint32_t code_point = value->units[index];
        if (code_point >= UINT32_C(0xD800) && code_point <= UINT32_C(0xDBFF)
                && index + 1 < value->utf16_length
                && value->units[index + 1] >= UINT16_C(0xDC00)
                && value->units[index + 1] <= UINT16_C(0xDFFF)) {
            code_point = UINT32_C(0x10000)
                    + ((code_point - UINT32_C(0xD800)) << 10)
                    + (uint32_t) (value->units[++index] - UINT16_C(0xDC00));
        } else if (code_point >= UINT32_C(0xD800) && code_point <= UINT32_C(0xDFFF)) {
            code_point = UINT32_C(0xFFFD);
        }
        position += encode_utf8_code_point(result + position, code_point);
    }
    if (position != value->utf8_length) {
        if (result != buffer) { free(result); }
        abort();
    }
    result[position] = '\0';
    return result;
}

static char *string_to_utf8(const struct ironwood_string *value, void *allocation_failure) {
    return string_to_utf8_buffer(value, NULL, 0, allocation_failure);
}

static struct ironwood_exception *exception_wrapper(struct _Unwind_Exception *unwind) {
    return (struct ironwood_exception *)
            ((char *) unwind - offsetof(struct ironwood_exception, unwind));
}

static void cleanup_exception(_Unwind_Reason_Code reason,
                              struct _Unwind_Exception *unwind) {
    (void) reason;
    struct ironwood_exception *exception = exception_wrapper(unwind);
    if (exception->emergency) {
        emergency_exception_in_use = 0;
    } else {
        free(exception);
    }
}

static const char *object_type_name(const void *object) {
    const struct ironwood_type_info *const *header = object;
    if (header == NULL || *header == NULL || (*header)->name == NULL) {
        return "<unknown>";
    }
    return (*header)->name;
}

static struct ironwood_exception_metadata *find_exception_metadata(const void *primary) {
    if (primary == NULL) { return NULL; }
    uint64_t state = ((const struct ironwood_throwable *) primary)->trace_state;
    return state <= 1 ? NULL : (struct ironwood_exception_metadata *) (uintptr_t) state;
}

static void attach_exception_metadata(void *primary, struct ironwood_exception_metadata *metadata) {
    ((struct ironwood_throwable *) primary)->trace_state = (uint64_t) (uintptr_t) metadata;
}

static struct ironwood_exception_metadata *create_exception_metadata(void *primary) {
    struct ironwood_exception_metadata *metadata = calloc(1, sizeof(*metadata));
    if (metadata == NULL) { return NULL; }
    metadata->primary = primary;
    attach_exception_metadata(primary, metadata);
    return metadata;
}

static void remove_metadata(struct ironwood_exception_metadata *target) {
    if (target->primary != NULL && find_exception_metadata(target->primary) == target) {
        attach_exception_metadata(target->primary, NULL);
    }
}

static void detach_emergency_secondary(void) {
    if (!emergency_secondary_in_use) {
        return;
    }
    struct ironwood_exception_metadata *metadata = emergency_secondary_owner;
    if (metadata != NULL) {
        struct ironwood_secondary_exception **link = &metadata->first_secondary;
        struct ironwood_secondary_exception *previous = NULL;
        while (*link != NULL) {
            if (*link == &emergency_secondary) {
                *link = emergency_secondary.next;
                if (metadata->last_secondary == &emergency_secondary) {
                    metadata->last_secondary = previous;
                }
                if (metadata->secondary_count > 0) {
                    metadata->secondary_count--;
                }
                break;
            }
            previous = *link;
            link = &(*link)->next;
        }
    }
    memset(&emergency_secondary, 0, sizeof(emergency_secondary));
    emergency_secondary_in_use = 0;
    emergency_secondary_owner = NULL;
}

static void prepare_implicit_failure(void *object) {
    detach_emergency_secondary();
    remove_metadata(&implicit_failure_metadata);
    if (emergency_association_metadata_in_use) {
        remove_metadata(&emergency_association_metadata);
        memset(&emergency_association_metadata, 0,
                sizeof(emergency_association_metadata));
        emergency_association_metadata_in_use = 0;
    }
    memset(&implicit_failure_metadata, 0, sizeof(implicit_failure_metadata));
    implicit_failure_metadata.primary = object;
    attach_exception_metadata(object, &implicit_failure_metadata);
    active_implicit_failure = object;
}

#define IRONWOOD_TRACE_INLINE_CAPACITY 128

struct ironwood_trace_path_entry {
    uint64_t guid;
    uint32_t inline_site;
};

#define IRONWOOD_TRACE_SIBLING_CAPACITY 8

struct ironwood_trace_decoder {
    const uint8_t *cursor;
    const uint8_t *end;
    uintptr_t last_address;
    uintptr_t target_function;
    uintptr_t target_pc;
    uintptr_t best_address;
    struct ironwood_trace_path_entry path[IRONWOOD_TRACE_INLINE_CAPACITY];
    uint64_t best_guids[IRONWOOD_TRACE_INLINE_CAPACITY];
    uint32_t best_probes[IRONWOOD_TRACE_INLINE_CAPACITY];
    /* Top-level node GUIDs whose code lives in the target function symbol. */
    uint64_t sibling_guids[IRONWOOD_TRACE_SIBLING_CAPACITY];
    int32_t sibling_count;
    int32_t best_count;
    const void *object;
    uint8_t best_kind;
    _Bool best_all_hidden;
    _Bool found;
    _Bool error;
};

static const struct ironwood_trace_site *trace_site_for_probe(uint64_t guid,
                                                               uint32_t probe_index);
static _Bool trace_site_is_hidden(const void *object,
                                  const struct ironwood_trace_site *site);

static _Bool trace_candidate_all_hidden(struct ironwood_trace_decoder *decoder,
                                        int32_t depth, uint32_t probe_index) {
    const struct ironwood_trace_site *site = trace_site_for_probe(
            decoder->path[depth].guid, probe_index);
    if (site == NULL || !trace_site_is_hidden(decoder->object, site)) { return 0; }
    for (int32_t current = depth; current > 0; current--) {
        site = trace_site_for_probe(decoder->path[current - 1].guid,
                decoder->path[current].inline_site);
        if (site == NULL || !trace_site_is_hidden(decoder->object, site)) { return 0; }
    }
    return 1;
}

static uintptr_t trace_function_for_guid(uint64_t guid) {
    for (int32_t index = 0; index < trace_function_count; index++) {
        if (trace_functions[index].guid == guid) {
            return (uintptr_t) trace_functions[index].address;
        }
    }
    return 0;
}

static _Bool trace_function_is_registered(uintptr_t function) {
    for (int32_t index = 0; index < trace_function_count; index++) {
        if ((uintptr_t) trace_functions[index].address == function) { return 1; }
    }
    return 0;
}

static uint64_t trace_read_u64(struct ironwood_trace_decoder *decoder) {
    if ((size_t) (decoder->end - decoder->cursor) < sizeof(uint64_t)) {
        decoder->error = 1;
        return 0;
    }
    uint64_t value = 0;
    for (int shift = 0; shift < 64; shift += 8) {
        value |= (uint64_t) *decoder->cursor++ << shift;
    }
    return value;
}

static uint64_t trace_read_uleb(struct ironwood_trace_decoder *decoder) {
    uint64_t value = 0;
    for (int shift = 0; shift < 64; shift += 7) {
        if (decoder->cursor == decoder->end) {
            decoder->error = 1;
            return 0;
        }
        uint8_t byte = *decoder->cursor++;
        uint64_t part = byte & UINT8_C(0x7f);
        if (shift == 63 && part > 1) {
            decoder->error = 1;
            return 0;
        }
        value |= part << shift;
        if ((byte & UINT8_C(0x80)) == 0) { return value; }
    }
    decoder->error = 1;
    return 0;
}

static int64_t trace_read_sleb(struct ironwood_trace_decoder *decoder) {
    uint64_t value = 0;
    int shift = 0;
    uint8_t byte = 0;
    do {
        if (decoder->cursor == decoder->end || shift >= 64) {
            decoder->error = 1;
            return 0;
        }
        byte = *decoder->cursor++;
        value |= (uint64_t) (byte & UINT8_C(0x7f)) << shift;
        shift += 7;
    } while ((byte & UINT8_C(0x80)) != 0);
    if (shift < 64 && (byte & UINT8_C(0x40)) != 0) {
        value |= UINT64_MAX << shift;
    }
    return (int64_t) value;
}

static void trace_consider_probe(struct ironwood_trace_decoder *decoder,
                                 int32_t depth, uint32_t probe_index,
                                 uint8_t kind,
                                 uintptr_t address,
                                 uintptr_t root_address) {
    if (root_address != decoder->target_function || address > decoder->target_pc) {
        return;
    }
    int32_t count = depth + 1;
    _Bool all_hidden = trace_candidate_all_hidden(decoder, depth, probe_index);
    if (decoder->found && decoder->best_all_hidden != all_hidden) {
        if (all_hidden) { return; }
    } else {
        if (decoder->found && address < decoder->best_address) { return; }
        if (decoder->found && address == decoder->best_address) {
            if (decoder->best_kind != 0 && kind == 0) { return; }
            if (decoder->best_kind == 0 && kind != 0) {
                decoder->best_count = 0;
            } else if (count < decoder->best_count) {
                return;
            }
        }
    }
    decoder->best_address = address;
    decoder->best_count = count;
    decoder->best_all_hidden = all_hidden;
    decoder->best_kind = kind;
    decoder->best_guids[0] = decoder->path[depth].guid;
    decoder->best_probes[0] = probe_index;
    int32_t output = 1;
    for (int32_t current = depth; current > 0; current--) {
        decoder->best_guids[output] = decoder->path[current - 1].guid;
        decoder->best_probes[output] = decoder->path[current].inline_site;
        output++;
    }
    decoder->found = 1;
}

static void trace_note_sibling(struct ironwood_trace_decoder *decoder, uint64_t guid,
                               uintptr_t root_address) {
    if (root_address == 0 || root_address != decoder->target_function) { return; }
    for (int32_t index = 0; index < decoder->sibling_count; index++) {
        if (decoder->sibling_guids[index] == guid) { return; }
    }
    if (decoder->sibling_count < IRONWOOD_TRACE_SIBLING_CAPACITY) {
        decoder->sibling_guids[decoder->sibling_count++] = guid;
    }
}

static void trace_decode_node(struct ironwood_trace_decoder *decoder,
                              _Bool top_level, int32_t depth,
                              uintptr_t inherited_root) {
    if (decoder->error || depth >= IRONWOOD_TRACE_INLINE_CAPACITY) {
        decoder->error = 1;
        return;
    }
    uint32_t inline_site = top_level ? 0 : (uint32_t) trace_read_uleb(decoder);
    uint64_t guid = trace_read_u64(decoder);
    uint64_t probe_count = trace_read_uleb(decoder);
    uint64_t child_count = trace_read_uleb(decoder);
    if (decoder->error || probe_count > UINT32_MAX || child_count > UINT32_MAX) {
        decoder->error = 1;
        return;
    }
    decoder->path[depth].guid = guid;
    decoder->path[depth].inline_site = inline_site;
    uintptr_t root_address = inherited_root;
    if (top_level) {
        root_address = trace_function_for_guid(guid);
        if (root_address != 0) { decoder->last_address = root_address; }
        trace_note_sibling(decoder, guid, root_address);
    }
    for (uint64_t index = 0; index < probe_count && !decoder->error; index++) {
        uint64_t probe_index_value = trace_read_uleb(decoder);
        if (decoder->cursor == decoder->end || probe_index_value > UINT32_MAX) {
            decoder->error = 1;
            return;
        }
        uint8_t packed = *decoder->cursor++;
        uint8_t kind = packed & UINT8_C(0x0f);
        uint8_t attributes = (packed & UINT8_C(0x70)) >> 4;
        _Bool sentinel = (attributes & UINT8_C(2)) != 0;
        uintptr_t address;
        if ((packed & UINT8_C(0x80)) != 0) {
            address = decoder->last_address + (uintptr_t) trace_read_sleb(decoder);
        } else {
            uint64_t encoded = trace_read_u64(decoder);
            address = sentinel ? trace_function_for_guid(encoded) : (uintptr_t) encoded;
        }
        if ((attributes & UINT8_C(4)) != 0) { (void) trace_read_uleb(decoder); }
        if (decoder->error) { return; }
        decoder->last_address = address;
        if (sentinel) {
            if (top_level) {
                root_address = address;
                trace_note_sibling(decoder, guid, root_address);
            }
        } else {
            trace_consider_probe(decoder, depth, (uint32_t) probe_index_value,
                    kind, address, root_address);
        }
    }
    for (uint64_t index = 0; index < child_count && !decoder->error; index++) {
        trace_decode_node(decoder, 0, depth + 1, root_address);
    }
}

static const struct ironwood_trace_site *trace_site_for_probe(uint64_t guid,
                                                               uint32_t probe_index) {
    for (int32_t index = 0; index < trace_site_count; index++) {
        if (trace_sites[index].guid == guid
                && (uint32_t) trace_sites[index].probe_index == probe_index) {
            return &trace_sites[index];
        }
    }
    return NULL;
}

static _Bool trace_function_owns_guid(uintptr_t function, uint64_t guid) {
    for (int32_t index = 0; index < trace_function_count; index++) {
        if ((uintptr_t) trace_functions[index].address == function
                && trace_functions[index].guid == guid) {
            return 1;
        }
    }
    return 0;
}

/*
 * Resolves the sites of one native frame, innermost first. `outlined_body`
 * reports whether the frame's function symbol is an LLVM-outlined body of the
 * outermost site's function rather than that function itself.
 */
static int32_t trace_resolve_pc(const void *object, uintptr_t pc, uintptr_t function,
                                const struct ironwood_trace_site **result,
                                int32_t capacity, _Bool *outlined_body) {
    *outlined_body = 0;
    if (function == 0 || !trace_function_is_registered(function)
            || trace_section == NULL || trace_section_size == 0) { return 0; }
    struct ironwood_trace_decoder decoder;
    memset(&decoder, 0, sizeof(decoder));
    decoder.cursor = trace_section;
    decoder.end = trace_section + trace_section_size;
    decoder.target_function = function;
    decoder.target_pc = pc;
    decoder.object = object;
    while (decoder.cursor < decoder.end && !decoder.error) {
        trace_decode_node(&decoder, 1, 0, 0);
    }
    if (decoder.error || !decoder.found) { return 0; }
    int32_t count = decoder.best_count < capacity ? decoder.best_count : capacity;
    int32_t output = 0;
    for (int32_t index = 0; index < count; index++) {
        const struct ironwood_trace_site *site = trace_site_for_probe(
                decoder.best_guids[index],
                decoder.best_probes[index]);
        /*
         * LLVM outlining (partial inlining, hot/cold splitting) files callees
         * inlined into an outlined body under the outlined symbol's own GUID,
         * which has no sites. The sibling top-level nodes of that symbol carry
         * the original function's GUID, so retry the probe index against them.
         */
        for (int32_t sibling = 0; site == NULL && sibling < decoder.sibling_count; sibling++) {
            if (decoder.sibling_guids[sibling] != decoder.best_guids[index]) {
                site = trace_site_for_probe(decoder.sibling_guids[sibling],
                        decoder.best_probes[index]);
            }
        }
        if (site != NULL) { result[output++] = site; }
    }
    if (output > 0) {
        *outlined_body = !trace_function_owns_guid(function, result[output - 1]->guid);
    }
    return output;
}

struct ironwood_trace_pc {
    uintptr_t address;
    uintptr_t function;
};

struct ironwood_pc_capture {
    struct ironwood_trace_pc *pcs;
    int32_t capacity;
    int32_t count;
    _Bool truncated;
};

static _Unwind_Reason_Code trace_capture_pc(struct _Unwind_Context *context, void *argument) {
    struct ironwood_pc_capture *capture = argument;
    uintptr_t pc = (uintptr_t) _Unwind_GetIP(context);
    if (pc == 0) { return _URC_NO_REASON; }
    pc--;
    if (capture->capacity != 0 && capture->count >= capture->capacity) {
        capture->truncated = 1;
        return _URC_END_OF_STACK;
    }
    if (capture->capacity != 0) {
        capture->pcs[capture->count].address = pc;
        /* Linkers may reorder cold functions and interleave runtime code. */
        capture->pcs[capture->count].function = (uintptr_t) _Unwind_GetRegionStart(context);
    }
    if (capture->count < INT32_MAX) {
        capture->count++;
        return _URC_NO_REASON;
    }
    capture->truncated = 1;
    return _URC_END_OF_STACK;
}

static _Bool trace_site_is_hidden(const void *object,
                                  const struct ironwood_trace_site *site) {
    return (site->flags & 1) != 0
            && ((const struct ironwood_throwable *) object)->type->membership[site->owner_type];
}

static void capture_exception_trace(void *object) {
    struct ironwood_exception_metadata *metadata = find_exception_metadata(object);
    if (metadata == NULL) {
        metadata = create_exception_metadata(object);
        if (metadata == NULL) {
            ((struct ironwood_throwable *) object)->trace_state = 1;
            return;
        }
    }
    if (metadata->trace_state != IRONWOOD_TRACE_NOT_CAPTURED) {
        return;
    }
    if (trace_section == NULL || trace_site_count <= 0 || trace_function_count <= 0) {
        metadata->trace_state = IRONWOOD_TRACE_UNAVAILABLE;
        return;
    }

    _Bool emergency = metadata == &implicit_failure_metadata;
    struct ironwood_trace_pc emergency_pcs[IRONWOOD_EMERGENCY_PC_CAPACITY];
    struct ironwood_pc_capture capture;
    memset(&capture, 0, sizeof(capture));
    if (emergency) {
        capture.pcs = emergency_pcs;
        capture.capacity = IRONWOOD_EMERGENCY_PC_CAPACITY;
        _Unwind_Backtrace(trace_capture_pc, &capture);
        if (capture.count > capture.capacity) { capture.count = capture.capacity; }
    } else {
        _Unwind_Backtrace(trace_capture_pc, &capture);
        if (capture.count <= 0) {
            metadata->trace_state = IRONWOOD_TRACE_UNAVAILABLE;
            return;
        }
        capture.pcs = calloc((size_t) capture.count, sizeof(*capture.pcs));
        if (capture.pcs == NULL) {
            metadata->trace_state = IRONWOOD_TRACE_UNAVAILABLE;
            return;
        }
        capture.capacity = capture.count;
        capture.count = 0;
        _Unwind_Backtrace(trace_capture_pc, &capture);
        if (capture.count > capture.capacity) { capture.count = capture.capacity; }
    }

    struct ironwood_trace_element *trace = emergency ? implicit_failure_trace : NULL;
    int32_t capacity = emergency ? IRONWOOD_EMERGENCY_TRACE_CAPACITY : 0;
    int32_t count = 0;
    _Bool hiding = 1;
    _Bool decoded = 0;
    _Bool previous_outlined = 0;
    uint64_t previous_guid = 0;
    for (int32_t pc_index = 0; pc_index < capture.count; pc_index++) {
        const struct ironwood_trace_site *resolved[IRONWOOD_TRACE_INLINE_CAPACITY];
        _Bool outlined_body = 0;
        int32_t resolved_count = trace_resolve_pc(object, capture.pcs[pc_index].address,
                capture.pcs[pc_index].function, resolved,
                IRONWOOD_TRACE_INLINE_CAPACITY, &outlined_body);
        if (resolved_count > 0) { decoded = 1; }
        /*
         * An outlined body and the frame that called it are one source
         * activation: LLVM partial inlining leaves the caller's copy of the
         * function's entry in the calling frame. Skip that duplicate site.
         */
        int32_t first = previous_outlined && resolved_count > 0
                && resolved[0]->guid == previous_guid ? 1 : 0;
        previous_outlined = resolved_count > 0 && outlined_body;
        previous_guid = resolved_count > 0 ? resolved[resolved_count - 1]->guid : 0;
        for (int32_t index = first; index < resolved_count; index++) {
            const struct ironwood_trace_site *site = resolved[index];
            if (hiding && trace_site_is_hidden(object, site)) { continue; }
            hiding = 0;
            if (count == capacity) {
                if (emergency) {
                    capture.truncated = 1;
                    break;
                }
                int32_t grown = capacity < 16 ? 16
                        : capacity <= INT32_MAX / 2 ? capacity * 2 : INT32_MAX;
                if (grown == capacity
                        || (size_t) grown > SIZE_MAX / sizeof(*trace)) {
                    free(trace);
                    free(capture.pcs);
                    metadata->trace_state = IRONWOOD_TRACE_UNAVAILABLE;
                    return;
                }
                struct ironwood_trace_element *replacement =
                        realloc(trace, (size_t) grown * sizeof(*trace));
                if (replacement == NULL) {
                    free(trace);
                    free(capture.pcs);
                    metadata->trace_state = IRONWOOD_TRACE_UNAVAILABLE;
                    return;
                }
                trace = replacement;
                capacity = grown;
            }
            trace[count++].site = site;
        }
        if (emergency && capture.truncated && count == capacity) { break; }
    }
    if (!emergency) { free(capture.pcs); }
    if (!decoded) {
        if (!emergency) { free(trace); }
        metadata->trace_state = IRONWOOD_TRACE_UNAVAILABLE;
        return;
    }
    metadata->trace = trace;
    metadata->trace_count = count;
    metadata->trace_truncated = capture.truncated;
    metadata->trace_state = IRONWOOD_TRACE_CAPTURED;
}

void ironwood_throwable_trace_release(void *object) {
    struct ironwood_exception_metadata *metadata = find_exception_metadata(object);
    attach_exception_metadata(object, NULL);
    if (metadata == NULL) { return; }
    if (emergency_secondary_owner == metadata) { detach_emergency_secondary(); }
    struct ironwood_secondary_exception *entry = metadata->first_secondary;
    while (entry != NULL) {
        struct ironwood_secondary_exception *next = entry->next;
        if (entry != &emergency_secondary) { free(entry); }
        entry = next;
    }
    if (metadata->trace != implicit_failure_trace) { free(metadata->trace); }
    if (metadata != &implicit_failure_metadata && metadata != &emergency_association_metadata) {
        free(metadata);
    }
}

void ironwood_throwable_trace_capture(void *object) {
    struct ironwood_exception_metadata *metadata = find_exception_metadata(object);
    if (metadata != NULL) {
        if (metadata->trace != implicit_failure_trace) { free(metadata->trace); }
        metadata->trace = NULL;
        metadata->trace_count = 0;
        metadata->trace_truncated = 0;
        metadata->trace_state = IRONWOOD_TRACE_NOT_CAPTURED;
    }
    capture_exception_trace(object);
}

static void append_secondary_object(struct ironwood_exception_metadata *metadata, void *object) {
    if (metadata->secondary_count == INT32_MAX) {
        abort();
    }
    struct ironwood_secondary_exception *entry;
    if (active_implicit_failure != NULL && !emergency_secondary_in_use) {
        entry = &emergency_secondary;
        memset(entry, 0, sizeof(*entry));
        emergency_secondary_in_use = 1;
        emergency_secondary_owner = metadata;
    } else {
        entry = calloc(1, sizeof(*entry));
    }
    if (entry == NULL) {
        fatal_emergency("ironwood: exception association storage exhausted");
    }
    entry->object = object;
    if (metadata->last_secondary == NULL) {
        metadata->first_secondary = entry;
    } else {
        metadata->last_secondary->next = entry;
    }
    metadata->last_secondary = entry;
    metadata->secondary_count++;
}

void *ironwood_allocate(size_t size, const void *object_type,
                        void *allocation_failure) {
    void *allocation = try_allocate_object(size, object_type, allocation_failure);
    if (allocation == NULL) {
        raise_allocation_failure(allocation_failure);
    }
    return allocation;
}

void *ironwood_allocate_array(int32_t length, size_t element_size, uint32_t element_kind,
                              const void *array_type, void *allocation_failure) {
    if (length < 0) {
        abort();
    }
    struct ironwood_array *array = try_allocate_array(length, element_size, element_kind,
            array_type, allocation_failure);
    if (array == NULL) {
        raise_allocation_failure(allocation_failure);
    }
    return array;
}

void *ironwood_process_arguments(int32_t argc, const char *const *argv,
                                 const void *string_type, const void *string_array_type) {
    if (argc < 0 || string_type == NULL || string_array_type == NULL
            || (argc > 1 && argv == NULL)) {
        abort();
    }
    int32_t argument_count = argc > 0 ? argc - 1 : 0;
    struct ironwood_array *arguments = ironwood_allocate_array(argument_count,
            sizeof(void *), IRONWOOD_ARRAY_REFERENCE, string_array_type, NULL);
    void **values = (void **) arguments->data;
    for (int32_t index = 0; index < argument_count; index++) {
        if (argv[index + 1] == NULL) {
            abort();
        }
        values[index] = string_from_utf8(argv[index + 1], string_type, NULL);
    }
    return arguments;
}

uint64_t ironwood_allocation_count(void) {
    return atomic_load_explicit(&allocation_count, memory_order_relaxed);
}

uint64_t ironwood_live_allocation_count(void) {
    return atomic_load_explicit(&live_allocation_count, memory_order_relaxed);
}

static uint32_t identity_hash(const void *object) {
    uint64_t mixed = (uint64_t) (uintptr_t) object;
    mixed ^= mixed >> 33;
    mixed *= UINT64_C(0xff51afd7ed558ccd);
    mixed ^= mixed >> 33;
    mixed *= UINT64_C(0xc4ceb9fe1a85ec53);
    mixed ^= mixed >> 33;
    return (uint32_t) (mixed ^ (mixed >> 32));
}

int32_t ironwood_object_hash_code(const void *object) {
    if (object == NULL) {
        abort();
    }
    uint32_t hash = identity_hash(object);
    if (hash <= (uint32_t) INT32_MAX) {
        return (int32_t) hash;
    }
    return -(int32_t) (UINT32_MAX - hash) - 1;
}

int32_t ironwood_identity_hash_code(const void *object) {
    uint32_t hash = identity_hash(object);
    if (hash <= (uint32_t) INT32_MAX) {
        return (int32_t) hash;
    }
    return -(int32_t) (UINT32_MAX - hash) - 1;
}

void ironwood_system_arraycopy(const void *source, int32_t source_position,
                               void *destination, int32_t destination_position,
                               int32_t length) {
    if (source == NULL || destination == NULL) {
        fputs("ironwood: NullPointerException: System.arraycopy source or destination is null\n",
                stderr);
        exit(1);
    }
    const struct ironwood_type_info *source_type = *(const struct ironwood_type_info *const *) source;
    const struct ironwood_type_info *destination_type =
            *(const struct ironwood_type_info *const *) destination;
    if (source_type == NULL || destination_type == NULL
            || source_type->kind != IRONWOOD_TYPE_ARRAY
            || destination_type->kind != IRONWOOD_TYPE_ARRAY) {
        fputs("ironwood: IllegalArgumentException: System.arraycopy operands must be arrays\n",
                stderr);
        exit(1);
    }
    const struct ironwood_array *source_array = source;
    struct ironwood_array *destination_array = destination;
    if (source_type != destination_type
            || source_array->element_kind != destination_array->element_kind
            || source_array->element_size != destination_array->element_size
            || source_array->element_size == 0) {
        fputs("ironwood: IllegalArgumentException: System.arraycopy array element types are incompatible\n",
                stderr);
        exit(1);
    }
    if (source_position < 0 || destination_position < 0 || length < 0) {
        fputs("ironwood: IndexOutOfBoundsException: System.arraycopy position or length is negative\n",
                stderr);
        exit(1);
    }
    size_t source_start = (size_t) source_position;
    size_t destination_start = (size_t) destination_position;
    size_t count = (size_t) length;
    if (source_start > source_array->length
            || count > source_array->length - source_start
            || destination_start > destination_array->length
            || count > destination_array->length - destination_start) {
        fputs("ironwood: IndexOutOfBoundsException: System.arraycopy range exceeds array bounds\n",
                stderr);
        exit(1);
    }
    if (count > SIZE_MAX / source_array->element_size) {
        abort();
    }
    size_t byte_count = count * source_array->element_size;
    memmove(destination_array->data + destination_start * destination_array->element_size,
            source_array->data + source_start * source_array->element_size,
            byte_count);
}

void *ironwood_system_getenv(const void *name, const void *string_type,
                             void *allocation_failure) {
    if (name == NULL || string_type == NULL) {
        abort();
    }
    char *native_name = string_to_utf8(name, allocation_failure);
    const char *value = getenv(native_name);
    free(native_name);
    return value == NULL ? NULL
            : string_from_utf8(value, string_type, allocation_failure);
}

_Noreturn void ironwood_system_exit(int32_t status) {
    exit(status);
}

int64_t ironwood_current_time_millis(void) {
    struct timespec value;
    if (clock_gettime(CLOCK_REALTIME, &value) != 0) {
        return INT64_C(0);
    }
    return (int64_t) value.tv_sec * INT64_C(1000)
            + (int64_t) value.tv_nsec / INT64_C(1000000);
}

int64_t ironwood_nano_time(void) {
    struct timespec value;
    if (clock_gettime(CLOCK_MONOTONIC, &value) != 0) {
        return INT64_C(0);
    }
    return (int64_t) ((uint64_t) value.tv_sec * UINT64_C(1000000000)
            + (uint64_t) value.tv_nsec);
}

/* IEEE binary64 rounding boundaries need fewer than 1,100 significant decimal
 * digits. Retaining 1,200 plus a sticky digit is therefore sufficient even for
 * adversarially long input while keeping successful parsing off both heaps. */
#define IRONWOOD_FLOATING_SIGNIFICANT_LIMIT 1200
#define IRONWOOD_FLOATING_BUFFER_CAPACITY 1240
/* A String has at most INT32_MAX code units. Twenty billion therefore retains
 * every exponent that could cancel the significand's decimal or hexadecimal
 * scale; only values already guaranteed to overflow/underflow are saturated. */
#define IRONWOOD_FLOATING_EXPONENT_LIMIT INT64_C(20000000000)

static int64_t clamp_floating_exponent(int64_t value) {
    return value < -IRONWOOD_FLOATING_EXPONENT_LIMIT
            ? -IRONWOOD_FLOATING_EXPONENT_LIMIT
            : value > IRONWOOD_FLOATING_EXPONENT_LIMIT
            ? IRONWOOD_FLOATING_EXPONENT_LIMIT : value;
}

static int64_t read_floating_exponent(const struct ironwood_string *text,
                                      int32_t index, int32_t end) {
    _Bool negative = 0;
    if (index < end && (text->units[index] == UINT16_C('+')
            || text->units[index] == UINT16_C('-'))) {
        negative = text->units[index] == UINT16_C('-');
        index++;
    }
    int64_t value = 0;
    while (index < end) {
        int64_t digit = (int64_t) (text->units[index++] - UINT16_C('0'));
        if (value < IRONWOOD_FLOATING_EXPONENT_LIMIT) {
            value = value > (IRONWOOD_FLOATING_EXPONENT_LIMIT - digit) / INT64_C(10)
                    ? IRONWOOD_FLOATING_EXPONENT_LIMIT
                    : value * INT64_C(10) + digit;
        }
    }
    return negative ? -value : value;
}

static size_t write_floating_exponent(char *output, size_t position, int64_t exponent) {
    exponent = clamp_floating_exponent(exponent);
    if (exponent < 0) {
        output[position++] = '-';
        exponent = -exponent;
    }
    char reversed[20];
    size_t digits = 0;
    do {
        reversed[digits++] = (char) ('0' + exponent % INT64_C(10));
        exponent /= INT64_C(10);
    } while (exponent != 0);
    while (digits > 0) {
        output[position++] = reversed[--digits];
    }
    return position;
}

/* The source facade has already enforced Java's grammar. Normalize directly
 * from UTF-16 into a fixed stack buffer so the successful path needs no
 * managed object and no malloc/realloc scratch. */
static void normalize_validated_floating_text(const struct ironwood_string *text,
                                              char output[IRONWOOD_FLOATING_BUFFER_CAPACITY]) {
    if (text == NULL) {
        abort();
    }
    int32_t begin = 0;
    while (begin < text->utf16_length && text->units[begin] <= UINT16_C(0x20)) {
        begin++;
    }
    int32_t end = text->utf16_length;
    while (end > begin && text->units[end - 1] <= UINT16_C(0x20)) {
        end--;
    }
    uint16_t last = text->units[end - 1];
    if (last == UINT16_C('f') || last == UINT16_C('F')
            || last == UINT16_C('d') || last == UINT16_C('D')) {
        end--;
    }

    _Bool negative = 0;
    if (text->units[begin] == UINT16_C('+') || text->units[begin] == UINT16_C('-')) {
        negative = text->units[begin] == UINT16_C('-');
        begin++;
    }

    _Bool hexadecimal = begin + 1 < end && text->units[begin] == UINT16_C('0')
            && (text->units[begin + 1] == UINT16_C('x')
            || text->units[begin + 1] == UINT16_C('X'));
    int32_t mantissa_begin = hexadecimal ? begin + 2 : begin;
    int32_t exponent_marker = end;
    for (int32_t index = mantissa_begin; index < end; index++) {
        uint16_t unit = text->units[index];
        if (hexadecimal ? unit == UINT16_C('p') || unit == UINT16_C('P')
                : unit == UINT16_C('e') || unit == UINT16_C('E')) {
            exponent_marker = index;
            break;
        }
    }
    int32_t mantissa_end = exponent_marker;
    int64_t digits_before_point = 0;
    int64_t ordinal = 0;
    int64_t first_nonzero = -1;
    for (int32_t index = mantissa_begin; index < mantissa_end; index++) {
        uint16_t unit = text->units[index];
        if (unit == UINT16_C('.')) {
            digits_before_point = ordinal;
        } else {
            if (unit > UINT16_C(0x7f)) {
                abort();
            }
            if (first_nonzero < 0 && unit != UINT16_C('0')) {
                first_nonzero = ordinal;
            }
            ordinal++;
        }
    }
    if (digits_before_point == 0) {
        _Bool has_point = 0;
        for (int32_t index = mantissa_begin; index < mantissa_end; index++) {
            if (text->units[index] == UINT16_C('.')) {
                has_point = 1;
                break;
            }
        }
        if (!has_point) {
            digits_before_point = ordinal;
        }
    }

    size_t position = 0;
    if (negative) {
        output[position++] = '-';
    }
    if (first_nonzero < 0) {
        output[position++] = '0';
        output[position] = '\0';
        return;
    }

    char significant[IRONWOOD_FLOATING_SIGNIFICANT_LIMIT + 1];
    size_t significant_count = 0;
    _Bool sticky = 0;
    ordinal = 0;
    for (int32_t index = mantissa_begin; index < mantissa_end; index++) {
        uint16_t unit = text->units[index];
        if (unit == UINT16_C('.')) {
            continue;
        }
        if (ordinal >= first_nonzero) {
            if (significant_count < IRONWOOD_FLOATING_SIGNIFICANT_LIMIT) {
                significant[significant_count++] = (char) unit;
            } else if (unit != UINT16_C('0')) {
                sticky = 1;
            }
        }
        ordinal++;
    }
    if (sticky) {
        significant[significant_count++] = '1';
    }

    if (hexadecimal) {
        output[position++] = '0';
        output[position++] = 'x';
    }
    output[position++] = significant[0];
    output[position++] = '.';
    if (significant_count == 1) {
        output[position++] = '0';
    } else {
        for (size_t index = 1; index < significant_count; index++) {
            output[position++] = significant[index];
        }
    }
    output[position++] = hexadecimal ? 'p' : 'e';
    int64_t explicit_exponent = exponent_marker == end ? 0
            : read_floating_exponent(text, exponent_marker + 1, end);
    int64_t scale = digits_before_point - first_nonzero - INT64_C(1);
    int64_t exponent = hexadecimal
            ? clamp_floating_exponent(scale * INT64_C(4))
                    + explicit_exponent
            : clamp_floating_exponent(scale) + explicit_exponent;
    position = write_floating_exponent(output, position, exponent);
    if (position >= IRONWOOD_FLOATING_BUFFER_CAPACITY) {
        abort();
    }
    output[position] = '\0';
}

float ironwood_parse_float(const void *text) {
    char native_text[IRONWOOD_FLOATING_BUFFER_CAPACITY];
    normalize_validated_floating_text(text, native_text);
    char *end = NULL;
    float result = strtof(native_text, &end);
    if (end == NULL || *end != '\0') {
        abort();
    }
    return result;
}

double ironwood_parse_double(const void *text) {
    char native_text[IRONWOOD_FLOATING_BUFFER_CAPACITY];
    normalize_validated_floating_text(text, native_text);
    char *end = NULL;
    double result = strtod(native_text, &end);
    if (end == NULL || *end != '\0') {
        abort();
    }
    return result;
}

static void set_file_error_from_errno(int error) {
    last_file_error = error == ENOENT ? IRONWOOD_FILE_ERROR_NO_SUCH_FILE
            : error == EACCES || error == EPERM ? IRONWOOD_FILE_ERROR_PERMISSION
            : error == ENOTDIR ? IRONWOOD_FILE_ERROR_NOT_DIRECTORY
            : error == EISDIR ? IRONWOOD_FILE_ERROR_IS_DIRECTORY
            : error == EFBIG ? IRONWOOD_FILE_ERROR_TOO_LARGE
            : error == EEXIST ? IRONWOOD_FILE_ERROR_ALREADY_EXISTS
            : error == ENOTEMPTY ? IRONWOOD_FILE_ERROR_DIRECTORY_NOT_EMPTY
            : IRONWOOD_FILE_ERROR_OTHER;
}

/* Host APIs require a contiguous NUL-terminated spelling. Ordinary paths use
 * bounded stack storage; only unusually long spellings need a heap fallback. */
static int open_native_file(const struct ironwood_string *path, int flags,
                            void *allocation_failure) {
    char stack[IRONWOOD_PATH_STACK_CAPACITY];
    char *native_path = string_to_utf8_buffer(path, stack, sizeof(stack), allocation_failure);
    int descriptor;
    do {
        descriptor = open(native_path, flags, 0666);
    } while (descriptor < 0 && errno == EINTR);
    int error = errno;
    if (native_path != stack) { free(native_path); }
    if (descriptor < 0) { set_file_error_from_errno(error); }
    return descriptor;
}

/* Streaming descriptors are retained only by source-level file objects. Opening
 * is the final fallible step of their constructors; free never reaches close. */
int32_t ironwood_stream_open(const void *path, int32_t mode, void *allocation_failure) {
    int flags;
    if (mode == 0) {
        flags = O_RDONLY;
    } else if (mode <= 2) {
        flags = O_WRONLY | O_CREAT | (mode == 2 ? O_APPEND : O_TRUNC);
    } else {
        flags = O_RDWR | O_CREAT;
        if (mode == 4) {
            flags |= O_SYNC;
        } else if (mode == 5) {
#ifdef O_DSYNC
            flags |= O_DSYNC;
#else
            flags |= O_SYNC;
#endif
        }
    }
    int descriptor = open_native_file(path, flags | O_CLOEXEC, allocation_failure);
    if (descriptor < 0) { return -1; }
    struct stat status = {0};
    if (fstat(descriptor, &status) != 0 || S_ISDIR(status.st_mode)) {
        int error = errno;
        if (S_ISDIR(status.st_mode)) { error = EISDIR; }
        close(descriptor);
        set_file_error_from_errno(error);
        return -1;
    }
    return descriptor;
}

int32_t ironwood_stream_read_bytes(int32_t descriptor, void *buffer, int32_t offset, int32_t length) {
    struct ironwood_array *array = buffer;
    if (array == NULL || offset < 0 || length < 0 || (size_t) offset > array->length
            || (size_t) length > array->length - (size_t) offset) { abort(); }
    if (length == 0) { return 0; }
    ssize_t result;
    do { result = read(descriptor, array->data + offset, (size_t) length); }
    while (result < 0 && errno == EINTR);
    if (result < 0) { set_file_error_from_errno(errno); return -2; }
    return result == 0 ? -1 : (int32_t) result;
}

int32_t ironwood_stream_read_byte(int32_t descriptor) {
    unsigned char value;
    ssize_t result;
    do { result = read(descriptor, &value, 1); } while (result < 0 && errno == EINTR);
    if (result < 0) { set_file_error_from_errno(errno); return -2; }
    return result == 0 ? -1 : (int32_t) value;
}

static int32_t stream_write(int descriptor, const unsigned char *bytes, size_t length) {
    size_t offset = 0;
    while (offset < length) {
        ssize_t result = write(descriptor, bytes + offset, length - offset);
        if (result < 0 && errno == EINTR) { continue; }
        if (result <= 0) { set_file_error_from_errno(result < 0 ? errno : EIO); return -1; }
        offset += (size_t) result;
    }
    return 0;
}

int32_t ironwood_stream_write_bytes(int32_t descriptor, const void *buffer, int32_t offset, int32_t length) {
    const struct ironwood_array *array = buffer;
    if (array == NULL || offset < 0 || length < 0 || (size_t) offset > array->length
            || (size_t) length > array->length - (size_t) offset) { abort(); }
    return stream_write(descriptor, array->data + offset, (size_t) length);
}

int32_t ironwood_stream_write_byte(int32_t descriptor, int32_t value) {
    unsigned char byte = (unsigned char) value;
    return stream_write(descriptor, &byte, 1);
}

int32_t ironwood_stream_available(int32_t descriptor) {
    int count = 0;
    if (ioctl(descriptor, FIONREAD, &count) == 0) { return count < 0 ? 0 : count; }
    struct stat status;
    if (fstat(descriptor, &status) < 0) { set_file_error_from_errno(errno); return -1; }
    if (!S_ISREG(status.st_mode)) { return 0; }
    off_t position = lseek(descriptor, 0, SEEK_CUR);
    if (position < 0) { set_file_error_from_errno(errno); return -1; }
    off_t remaining = status.st_size > position ? status.st_size - position : 0;
    return remaining > INT32_MAX ? INT32_MAX : (int32_t) remaining;
}

int64_t ironwood_stream_position(int32_t descriptor) {
    off_t position = lseek(descriptor, 0, SEEK_CUR);
    if (position < 0) { set_file_error_from_errno(errno); return -1; }
    if ((uintmax_t) position > INT64_MAX) { set_file_error_from_errno(EOVERFLOW); return -1; }
    return (int64_t) position;
}

int64_t ironwood_stream_seek(int32_t descriptor, int64_t position) {
    if (position < 0 || (int64_t) (off_t) position != position) {
        set_file_error_from_errno(EINVAL);
        return -1;
    }
    off_t result = lseek(descriptor, (off_t) position, SEEK_SET);
    if (result < 0) { set_file_error_from_errno(errno); return -1; }
    return (int64_t) result;
}

int64_t ironwood_stream_length(int32_t descriptor) {
    struct stat status;
    if (fstat(descriptor, &status) < 0) { set_file_error_from_errno(errno); return -1; }
    if (status.st_size < 0 || (uintmax_t) status.st_size > INT64_MAX) {
        set_file_error_from_errno(EOVERFLOW);
        return -1;
    }
    return (int64_t) status.st_size;
}

int32_t ironwood_stream_set_length(int32_t descriptor, int64_t length) {
    if (length < 0 || (int64_t) (off_t) length != length) {
        set_file_error_from_errno(EINVAL);
        return -1;
    }
    if (ftruncate(descriptor, (off_t) length) < 0) {
        set_file_error_from_errno(errno);
        return -1;
    }
    int flags = fcntl(descriptor, F_GETFL);
    int sync_flags = O_SYNC;
#ifdef O_DSYNC
    sync_flags |= O_DSYNC;
#endif
    if (flags < 0 || ((flags & sync_flags) != 0 && fsync(descriptor) < 0)) {
        set_file_error_from_errno(errno);
        return -1;
    }
    return 0;
}

int32_t ironwood_stream_close(int32_t descriptor) {
    /* Do not retry close: on supported hosts EINTR can consume the descriptor. */
    if (close(descriptor) < 0) { set_file_error_from_errno(errno); return -1; }
    return 0;
}

static int stat_native_file(const struct ironwood_string *path, struct stat *status,
                            void *allocation_failure) {
    char stack[IRONWOOD_PATH_STACK_CAPACITY];
    char *native_path = string_to_utf8_buffer(path, stack, sizeof(stack), allocation_failure);
    int result = stat(native_path, status);
    int error = errno;
    if (native_path != stack) { free(native_path); }
    if (result != 0) { set_file_error_from_errno(error); }
    return result;
}

int32_t ironwood_file_same(const void *first, const void *second, void *allocation_failure) {
    struct stat left, right;
    if (stat_native_file(first, &left, allocation_failure) != 0
            || stat_native_file(second, &right, allocation_failure) != 0) { return -1; }
    return left.st_dev == right.st_dev && left.st_ino == right.st_ino;
}

/* The result is not published until the descriptor is closed. Resizing that
 * same allocation does not create an additional language object or scratch
 * buffer. A failed resize leaves the original allocation available for cleanup. */
static void *reserve_file_result(void *result, size_t header, size_t element_size,
                                 size_t required, size_t *capacity) {
    if (required <= *capacity) { return result; }
    size_t next = *capacity < IRONWOOD_IO_CHUNK_CAPACITY
            ? IRONWOOD_IO_CHUNK_CAPACITY : *capacity;
    while (next < required) {
        next = next > (size_t) INT32_MAX / 2U ? (size_t) INT32_MAX : next * 2U;
    }
    if (next > (SIZE_MAX - header) / element_size) { return NULL; }
    void *grown = realloc(result, header + next * element_size);
    if (grown != NULL) { *capacity = next; }
    return grown;
}

static void *read_file_result(const struct ironwood_string *path, const void *type,
                              _Bool text, void *allocation_failure) {
    if (path == NULL || type == NULL) { abort(); }
    last_file_error = IRONWOOD_FILE_ERROR_NONE;
    int descriptor = open_native_file(path, O_RDONLY, allocation_failure);
    if (descriptor < 0) { return NULL; }
    struct stat status;
    size_t capacity = 0;
    if (fstat(descriptor, &status) == 0 && S_ISREG(status.st_mode) && status.st_size > 0) {
        if ((uint64_t) status.st_size > (uint64_t) INT32_MAX) {
            close(descriptor);
            last_file_error = IRONWOOD_FILE_ERROR_TOO_LARGE;
            return NULL;
        }
        capacity = (size_t) status.st_size;
    }
    size_t header = text ? sizeof(struct ironwood_string) : sizeof(struct ironwood_array);
    size_t element_size = text ? sizeof(uint16_t) : sizeof(uint8_t);
    void *result = text
            ? (void *) try_allocate_string((int32_t) capacity, type, allocation_failure)
            : (void *) try_allocate_array((int32_t) capacity, 1, IRONWOOD_ARRAY_BYTE,
                    type, allocation_failure);
    _Bool out_of_memory = result == NULL;
    if (out_of_memory) { goto failed; }
    unsigned char chunk[IRONWOOD_IO_CHUNK_CAPACITY];
    size_t byte_length = 0;
    size_t length = 0;
    uint32_t code_point = 0;
    uint32_t minimum = 0;
    int remaining = 0;
    for (;;) {
        ssize_t count = read(descriptor, chunk, sizeof(chunk));
        if (count < 0) {
            if (errno == EINTR) { continue; }
            set_file_error_from_errno(errno);
            goto failed;
        }
        if (count == 0) { break; }
        if (byte_length > (size_t) INT32_MAX - (size_t) count) {
            last_file_error = IRONWOOD_FILE_ERROR_TOO_LARGE;
            goto failed;
        }
        byte_length += (size_t) count;
        if (!text) {
            void *grown = reserve_file_result(result, header, element_size,
                    byte_length, &capacity);
            if (grown == NULL) { out_of_memory = 1; goto failed; }
            result = grown;
            memcpy(((struct ironwood_array *) result)->data + length, chunk, (size_t) count);
            length = byte_length;
            continue;
        }
        for (ssize_t index = 0; index < count; index++) {
            unsigned char byte = chunk[index];
            if (remaining == 0) {
                if (byte <= 0x7F) { code_point = byte; minimum = 0; }
                else if (byte >= 0xC2 && byte <= 0xDF) {
                    code_point = byte & 0x1F; minimum = 0x80; remaining = 1; continue;
                } else if (byte >= 0xE0 && byte <= 0xEF) {
                    code_point = byte & 0x0F; minimum = 0x800; remaining = 2; continue;
                } else if (byte >= 0xF0 && byte <= 0xF4) {
                    code_point = byte & 0x07; minimum = 0x10000; remaining = 3; continue;
                } else { goto malformed; }
            } else {
                if (!is_utf8_continuation(byte)) { goto malformed; }
                code_point = (code_point << 6) | (byte & 0x3F);
                if (--remaining != 0) { continue; }
            }
            if (code_point < minimum || code_point > 0x10FFFF
                    || (code_point >= 0xD800 && code_point <= 0xDFFF)) { goto malformed; }
            size_t required = length + (code_point > 0xFFFF ? 2U : 1U);
            void *grown = reserve_file_result(result, header, element_size, required, &capacity);
            if (grown == NULL) { out_of_memory = 1; goto failed; }
            result = grown;
            uint16_t *units = ((struct ironwood_string *) result)->units;
            if (code_point <= 0xFFFF) { units[length++] = (uint16_t) code_point; }
            else {
                code_point -= 0x10000;
                units[length++] = (uint16_t) (0xD800 + (code_point >> 10));
                units[length++] = (uint16_t) (0xDC00 + (code_point & 0x3FF));
            }
        }
    }
    if (remaining != 0) { goto malformed; }
    if (close(descriptor) != 0) {
        set_file_error_from_errno(errno);
        ironwood_deallocate(result);
        return NULL;
    }
    if (text) {
        ((struct ironwood_string *) result)->utf16_length = (int32_t) length;
        ((struct ironwood_string *) result)->utf8_length = (int32_t) byte_length;
    } else { ((struct ironwood_array *) result)->length = length; }
    // Shrinking is optional: failure must not discard an already valid result.
    if (length < capacity) {
        void *compact = realloc(result, header + length * element_size);
        if (compact != NULL) { result = compact; }
    }
    return result;

malformed:
    last_file_error = IRONWOOD_FILE_ERROR_INVALID_UTF8;
failed:
    if (result != NULL) { ironwood_deallocate(result); }
    close(descriptor);
    if (out_of_memory) { raise_allocation_failure(allocation_failure); }
    return NULL;
}

void *ironwood_file_read_all_bytes(const void *path, const void *byte_array_type,
                                   void *allocation_failure) {
    return read_file_result(path, byte_array_type, 0, allocation_failure);
}

void *ironwood_file_read_string(const void *path, const void *string_type,
                                void *allocation_failure) {
    return read_file_result(path, string_type, 1, allocation_failure);
}

static _Bool write_native_bytes(int descriptor, const void *bytes, size_t length) {
    const unsigned char *data = bytes;
    while (length > 0) {
        ssize_t count = write(descriptor, data, length);
        if (count < 0 && errno == EINTR) { continue; }
        if (count <= 0) {
            if (count == 0) { errno = EIO; }
            return 0;
        }
        data += count;
        length -= (size_t) count;
    }
    return 1;
}

static int32_t finish_file_write(int descriptor, _Bool successful) {
    int error = successful ? 0 : errno;
    if (close(descriptor) != 0 && successful) {
        error = errno;
        successful = 0;
    }
    if (!successful) {
        set_file_error_from_errno(error);
        return last_file_error;
    }
    last_file_error = IRONWOOD_FILE_ERROR_NONE;
    return IRONWOOD_FILE_ERROR_NONE;
}

int32_t ironwood_file_write_bytes(const void *path, const void *bytes,
                                  void *allocation_failure) {
    const struct ironwood_array *array = bytes;
    if (path == NULL || array == NULL || array->element_kind != IRONWOOD_ARRAY_BYTE
            || array->element_size != sizeof(uint8_t)) { abort(); }
    int descriptor = open_native_file(path, O_WRONLY | O_CREAT | O_TRUNC, allocation_failure);
    if (descriptor < 0) { return last_file_error; }
    return finish_file_write(descriptor, write_native_bytes(descriptor, array->data, array->length));
}

static _Bool well_formed_utf16(const uint16_t *units, int32_t length) {
    for (int32_t index = 0; index < length; index++) {
        uint16_t unit = units[index];
        if (unit >= 0xD800 && unit <= 0xDBFF) {
            if (index + 1 >= length || units[index + 1] < 0xDC00
                    || units[index + 1] > 0xDFFF) { return 0; }
            index++;
        } else if (unit >= 0xDC00 && unit <= 0xDFFF) { return 0; }
    }
    return 1;
}

static int32_t write_utf16_file(const struct ironwood_string *path,
                                const uint16_t *units, int32_t length,
                                void *allocation_failure) {
    /* Validate before O_TRUNC so malformed text cannot alter the destination. */
    if (!well_formed_utf16(units, length)) {
        last_file_error = IRONWOOD_FILE_ERROR_INVALID_UTF8;
        return last_file_error;
    }
    int descriptor = open_native_file(path, O_WRONLY | O_CREAT | O_TRUNC, allocation_failure);
    if (descriptor < 0) { return last_file_error; }
    char buffer[IRONWOOD_IO_CHUNK_CAPACITY];
    size_t used = 0;
    for (int32_t index = 0; index < length; index++) {
        if (sizeof(buffer) - used < 4) {
            if (!write_native_bytes(descriptor, buffer, used)) {
                return finish_file_write(descriptor, 0);
            }
            used = 0;
        }
        uint32_t code_point = units[index];
        if (code_point >= 0xD800 && code_point <= 0xDBFF) {
            code_point = 0x10000 + ((code_point - 0xD800) << 10)
                    + (uint32_t) (units[++index] - 0xDC00);
        }
        used += (size_t) encode_utf8_code_point(buffer + used, code_point);
    }
    return finish_file_write(descriptor, write_native_bytes(descriptor, buffer, used));
}

int32_t ironwood_file_write_string(const void *path, const void *content,
                                   void *allocation_failure) {
    const struct ironwood_string *text = content;
    if (path == NULL || text == NULL) { abort(); }
    return write_utf16_file(path, text->units, text->utf16_length, allocation_failure);
}

int32_t ironwood_file_write_chars(const void *path, const void *content,
                                  void *allocation_failure) {
    const struct ironwood_array *array = content;
    if (path == NULL || array == NULL || array->element_kind != IRONWOOD_ARRAY_CHAR
            || array->element_size != sizeof(uint16_t) || array->length > INT32_MAX) { abort(); }
    return write_utf16_file(path, (const uint16_t *) array->data, (int32_t) array->length,
            allocation_failure);
}

int32_t ironwood_file_delete(const void *path, void *allocation_failure) {
    const struct ironwood_string *text = path;
    if (text == NULL) { abort(); }
    char stack[IRONWOOD_PATH_STACK_CAPACITY];
    char *native_path = string_to_utf8_buffer(text, stack, sizeof(stack), allocation_failure);
    struct stat status;
    int result = lstat(native_path, &status);
    if (result == 0) {
        result = S_ISDIR(status.st_mode) ? rmdir(native_path) : unlink(native_path);
    }
    int error = errno;
    if (native_path != stack) { free(native_path); }
    if (result != 0) {
        set_file_error_from_errno(error);
        return last_file_error;
    }
    last_file_error = IRONWOOD_FILE_ERROR_NONE;
    return last_file_error;
}

int32_t ironwood_file_create_directories(const void *path, void *allocation_failure) {
    const struct ironwood_string *text = path;
    if (text == NULL) { abort(); }
    char stack[IRONWOOD_PATH_STACK_CAPACITY];
    char *native_path = string_to_utf8_buffer(text, stack, sizeof(stack), allocation_failure);
    size_t length = strlen(native_path);
    int error = 0;
    for (size_t index = native_path[0] == '/' ? 1U : 0U; index <= length; index++) {
        if (native_path[index] != '/' && native_path[index] != '\0') { continue; }
        char saved = native_path[index];
        native_path[index] = '\0';
        if (native_path[0] != '\0' && mkdir(native_path, 0777) != 0) {
            error = errno;
            if (error == EEXIST) {
                struct stat status;
                if (stat(native_path, &status) == 0 && S_ISDIR(status.st_mode)) { error = 0; }
            }
        }
        native_path[index] = saved;
        if (error != 0) { break; }
    }
    if (native_path != stack) { free(native_path); }
    if (error != 0) {
        set_file_error_from_errno(error);
        return last_file_error;
    }
    last_file_error = IRONWOOD_FILE_ERROR_NONE;
    return last_file_error;
}

static char *two_native_paths(const struct ironwood_string *first,
                              const struct ironwood_string *second,
                              char *stack, size_t stack_capacity,
                              char **first_path, char **second_path,
                              void *allocation_failure) {
    size_t first_capacity = (size_t) first->utf8_length + 1U;
    size_t second_capacity = (size_t) second->utf8_length + 1U;
    if (first_capacity > SIZE_MAX - second_capacity) {
        raise_allocation_failure(allocation_failure);
    }
    size_t capacity = first_capacity + second_capacity;
    char *storage = capacity <= stack_capacity ? stack : malloc(capacity);
    if (storage == NULL) { raise_allocation_failure(allocation_failure); }
    *first_path = string_to_utf8_buffer(first, storage, first_capacity, allocation_failure);
    *second_path = string_to_utf8_buffer(second, storage + first_capacity,
            second_capacity, allocation_failure);
    return storage;
}

int32_t ironwood_file_copy(const void *source, const void *target,
                           void *allocation_failure) {
    const struct ironwood_string *source_text = source;
    const struct ironwood_string *target_text = target;
    if (source_text == NULL || target_text == NULL) { abort(); }
    char stack[IRONWOOD_PATH_STACK_CAPACITY * 2];
    char *source_path, *target_path;
    char *storage = two_native_paths(source_text, target_text, stack, sizeof(stack),
            &source_path, &target_path, allocation_failure);
    struct stat source_status, target_status;
    if (stat(source_path, &source_status) != 0) {
        int error = errno;
        if (storage != stack) { free(storage); }
        set_file_error_from_errno(error);
        return last_file_error;
    }
    int target_result = stat(target_path, &target_status);
    int target_error = errno;
    if (target_result == 0) {
        if (source_status.st_dev == target_status.st_dev
                && source_status.st_ino == target_status.st_ino) {
            if (storage != stack) { free(storage); }
            last_file_error = IRONWOOD_FILE_ERROR_NONE;
            return last_file_error;
        }
        if (storage != stack) { free(storage); }
        set_file_error_from_errno(EEXIST);
        return last_file_error;
    }
    if (target_error != ENOENT) {
        if (storage != stack) { free(storage); }
        set_file_error_from_errno(target_error);
        return last_file_error;
    }
    if (S_ISDIR(source_status.st_mode)) {
        int result = mkdir(target_path, 0777);
        int error = errno;
        if (storage != stack) { free(storage); }
        if (result != 0) {
            set_file_error_from_errno(error);
            return last_file_error;
        }
        last_file_error = IRONWOOD_FILE_ERROR_NONE;
        return last_file_error;
    }
    int source_descriptor = open(source_path, O_RDONLY | O_CLOEXEC);
    int target_descriptor = source_descriptor < 0 ? -1
            : open(target_path, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0666);
    int error = source_descriptor < 0 || target_descriptor < 0 ? errno : 0;
    unsigned char buffer[IRONWOOD_IO_CHUNK_CAPACITY];
    while (error == 0) {
        ssize_t count = read(source_descriptor, buffer, sizeof(buffer));
        if (count < 0 && errno == EINTR) { continue; }
        if (count < 0) { error = errno; break; }
        if (count == 0) { break; }
        if (!write_native_bytes(target_descriptor, buffer, (size_t) count)) {
            error = errno;
            break;
        }
    }
    if (source_descriptor >= 0 && close(source_descriptor) != 0 && error == 0) { error = errno; }
    if (target_descriptor >= 0 && close(target_descriptor) != 0 && error == 0) { error = errno; }
    if (storage != stack) { free(storage); }
    if (error != 0) {
        set_file_error_from_errno(error);
        return last_file_error;
    }
    last_file_error = IRONWOOD_FILE_ERROR_NONE;
    return last_file_error;
}

int32_t ironwood_file_move(const void *source, const void *target,
                           void *allocation_failure) {
    const struct ironwood_string *source_text = source;
    const struct ironwood_string *target_text = target;
    if (source_text == NULL || target_text == NULL) { abort(); }
    char stack[IRONWOOD_PATH_STACK_CAPACITY * 2];
    char *source_path, *target_path;
    char *storage = two_native_paths(source_text, target_text, stack, sizeof(stack),
            &source_path, &target_path, allocation_failure);
    struct stat source_status, target_status;
    int result = lstat(source_path, &source_status);
    int error = result != 0 ? errno : 0;
    if (result == 0 && lstat(target_path, &target_status) == 0) {
        if (source_status.st_dev == target_status.st_dev
                && source_status.st_ino == target_status.st_ino) {
            if (storage != stack) { free(storage); }
            last_file_error = IRONWOOD_FILE_ERROR_NONE;
            return last_file_error;
        }
        error = EEXIST;
    } else if (result == 0) {

        result = rename(source_path, target_path);
        if (result != 0) { error = errno; }
    }
    if (storage != stack) { free(storage); }
    if (error != 0) {
        set_file_error_from_errno(error);
        return last_file_error;
    }
    last_file_error = IRONWOOD_FILE_ERROR_NONE;
    return last_file_error;
}

struct ironwood_directory_handle {
    DIR *directory;
    struct dirent *next_entry;
    _Bool finished;
};

int64_t ironwood_directory_open(const void *path, void *allocation_failure) {
    const struct ironwood_string *text = path;
    if (text == NULL) { abort(); }
    char stack[IRONWOOD_PATH_STACK_CAPACITY];
    char *native_path = string_to_utf8_buffer(text, stack, sizeof(stack), allocation_failure);
    DIR *directory = opendir(native_path);
    int error = errno;
    if (native_path != stack) { free(native_path); }
    if (directory == NULL) {
        set_file_error_from_errno(error);
        return INT64_C(0);
    }
    struct ironwood_directory_handle *handle = calloc(1, sizeof(*handle));
    if (handle == NULL) {
        closedir(directory);
        raise_allocation_failure(allocation_failure);
    }
    handle->directory = directory;
    last_file_error = IRONWOOD_FILE_ERROR_NONE;
    return (int64_t) (intptr_t) handle;
}

int32_t ironwood_directory_has_next(int64_t handle_value) {
    if (handle_value == 0) { abort(); }
    struct ironwood_directory_handle *handle =
            (struct ironwood_directory_handle *) (intptr_t) handle_value;
    if (handle->next_entry != NULL) { return 1; }
    if (handle->finished) { return 0; }
    for (;;) {
        errno = 0;
        struct dirent *entry = readdir(handle->directory);
        if (entry == NULL) {
            if (errno != 0) {
                set_file_error_from_errno(errno);
                return -1;
            }
            handle->finished = 1;
            last_file_error = IRONWOOD_FILE_ERROR_NONE;
            return 0;
        }
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
            continue;
        }
        size_t length = strlen(entry->d_name);
        if (!valid_utf8((const unsigned char *) entry->d_name, length)) {
            last_file_error = IRONWOOD_FILE_ERROR_INVALID_UTF8;
            return -1;
        }
        handle->next_entry = entry;
        last_file_error = IRONWOOD_FILE_ERROR_NONE;
        return 1;
    }
}

void *ironwood_directory_next(int64_t handle_value, const void *string_type,
                              void *allocation_failure) {
    if (handle_value == 0 || string_type == NULL) { abort(); }
    struct ironwood_directory_handle *handle =
            (struct ironwood_directory_handle *) (intptr_t) handle_value;
    if (ironwood_directory_has_next(handle_value) <= 0) { return NULL; }
    const char *name = handle->next_entry->d_name;
    size_t length = strlen(name);
    struct ironwood_string *result = try_string_from_valid_utf8_bytes(
            (const unsigned char *) name, length, string_type, allocation_failure);
    if (result == NULL) { raise_allocation_failure(allocation_failure); }
    handle->next_entry = NULL;
    return result;
}

int32_t ironwood_directory_close(int64_t handle_value) {
    if (handle_value == 0) { abort(); }
    struct ironwood_directory_handle *handle =
            (struct ironwood_directory_handle *) (intptr_t) handle_value;
    int result = closedir(handle->directory);
    int error = errno;
    free(handle);
    if (result != 0) {
        set_file_error_from_errno(error);
        return last_file_error;
    }
    last_file_error = IRONWOOD_FILE_ERROR_NONE;
    return last_file_error;
}

static int64_t file_time_millis(time_t seconds, long nanos) {
    if (seconds > INT64_MAX / INT64_C(1000)) { return INT64_MAX; }
    if (seconds < INT64_MIN / INT64_C(1000)) { return INT64_MIN; }
    int64_t millis = (int64_t) seconds * INT64_C(1000);
    int64_t fraction = (int64_t) (nanos / 1000000L);
    if (fraction > 0 && millis > INT64_MAX - fraction) { return INT64_MAX; }
    if (fraction < 0 && millis < INT64_MIN - fraction) { return INT64_MIN; }
    return millis + fraction;
}

void *ironwood_file_read_attributes(const void *path, _Bool follow_links,
                                    const void *long_array_type,
                                    void *allocation_failure) {
    const struct ironwood_string *text = path;
    if (text == NULL || long_array_type == NULL) { abort(); }
    char stack[IRONWOOD_PATH_STACK_CAPACITY];
    char *native_path = string_to_utf8_buffer(text, stack, sizeof(stack), allocation_failure);
    struct stat status;
    int result = follow_links ? stat(native_path, &status) : lstat(native_path, &status);
    int error = errno;
    if (native_path != stack) { free(native_path); }
    if (result != 0) {
        set_file_error_from_errno(error);
        return NULL;
    }
    struct ironwood_array *values = try_allocate_array(5, sizeof(int64_t),
            IRONWOOD_ARRAY_LONG, long_array_type, allocation_failure);
    if (values == NULL) { raise_allocation_failure(allocation_failure); }
    int64_t *data = (int64_t *) values->data;
    data[0] = status.st_size < 0 ? INT64_C(0) : (int64_t) status.st_size;
#if defined(__APPLE__)
    data[1] = file_time_millis(status.st_mtimespec.tv_sec, status.st_mtimespec.tv_nsec);
    data[2] = file_time_millis(status.st_atimespec.tv_sec, status.st_atimespec.tv_nsec);
    data[3] = file_time_millis(status.st_birthtimespec.tv_sec, status.st_birthtimespec.tv_nsec);
#else
    data[1] = file_time_millis(status.st_mtim.tv_sec, status.st_mtim.tv_nsec);
    data[2] = file_time_millis(status.st_atim.tv_sec, status.st_atim.tv_nsec);
    data[3] = INT64_C(0);
#endif
    data[4] = S_ISREG(status.st_mode) ? INT64_C(1)
            : S_ISDIR(status.st_mode) ? INT64_C(2)
            : S_ISLNK(status.st_mode) ? INT64_C(3) : INT64_C(4);
    last_file_error = IRONWOOD_FILE_ERROR_NONE;
    return values;
}

int32_t ironwood_file_kind(const void *path, void *allocation_failure) {
    if (path == NULL) { abort(); }
    struct stat status;
    if (stat_native_file(path, &status, allocation_failure) != 0) { return 0; }
    last_file_error = IRONWOOD_FILE_ERROR_NONE;
    return S_ISREG(status.st_mode) ? 1 : S_ISDIR(status.st_mode) ? 2 : 3;
}

int32_t ironwood_file_kind_nofollow(const void *path, void *allocation_failure) {
    if (path == NULL) { abort(); }
    struct stat status;
    char stack[IRONWOOD_PATH_STACK_CAPACITY];
    char *native_path = string_to_utf8_buffer(path, stack, sizeof(stack), allocation_failure);
    int result = lstat(native_path, &status);
    int error = errno;
    if (native_path != stack) { free(native_path); }
    if (result != 0) {
        set_file_error_from_errno(error);
        return 0;
    }
    last_file_error = IRONWOOD_FILE_ERROR_NONE;
    return S_ISREG(status.st_mode) ? 1 : S_ISDIR(status.st_mode) ? 2
            : S_ISLNK(status.st_mode) ? 3 : 4;
}

int64_t ironwood_file_size(const void *path, void *allocation_failure) {
    if (path == NULL) { abort(); }
    struct stat status;
    if (stat_native_file(path, &status, allocation_failure) != 0) { return INT64_C(-1); }
    if (status.st_size < 0) {
        set_file_error_from_errno(EIO);
        return INT64_C(-1);
    }
    last_file_error = IRONWOOD_FILE_ERROR_NONE;
    return (int64_t) status.st_size;
}

int32_t ironwood_file_last_error(void) {
    return last_file_error;
}

static struct ironwood_string *path_string_from_units(
        const uint16_t *units, int32_t length, const void *string_type,
        void *allocation_failure) {
    struct ironwood_string *result = allocate_string(length, string_type,
            allocation_failure);
    if (length > 0) {
        memcpy(result->units, units, (size_t) length * sizeof(uint16_t));
    }
    result->utf8_length = utf8_length_of_utf16(result->units, length);
    return result;
}

static char *current_directory_buffer(char *stack, size_t stack_capacity,
                                       void *allocation_failure) {
    char *directory = stack;
    size_t capacity = stack_capacity;
    while (getcwd(directory, capacity) == NULL) {
        if (errno != ERANGE || capacity > (size_t) INT32_MAX / 2U) {
            if (directory != stack) { free(directory); }
            return NULL;
        }
        capacity *= 2U;
        char *grown = directory == stack ? malloc(capacity) : realloc(directory, capacity);
        if (grown == NULL) {
            if (directory != stack) { free(directory); }
            raise_allocation_failure(allocation_failure);
        }
        directory = grown;
    }
    return directory;
}

void *ironwood_path_current_directory(const void *string_type, void *allocation_failure) {
    char stack[IRONWOOD_PATH_STACK_CAPACITY];
    char *directory = current_directory_buffer(stack, sizeof(stack), allocation_failure);
    if (directory == NULL) { return NULL; }
    size_t length = strlen(directory);
    if (!valid_utf8((const unsigned char *) directory, length)) {
        if (directory != stack) { free(directory); }
        return NULL;
    }
    struct ironwood_string *result = try_string_from_valid_utf8_bytes(
            (const unsigned char *) directory, length, string_type, allocation_failure);
    if (directory != stack) { free(directory); }
    if (result == NULL) { raise_allocation_failure(allocation_failure); }
    return result;
}

static _Bool string_equals_ascii(const struct ironwood_string *value,
                                 const char *ascii) {
    size_t length = strlen(ascii);
    if (length > (size_t) INT32_MAX || value->utf16_length != (int32_t) length) {
        return 0;
    }
    for (size_t index = 0; index < length; index++) {
        if (value->units[index] != (uint16_t) (unsigned char) ascii[index]) {
            return 0;
        }
    }
    return 1;
}

void *ironwood_system_get_property(const void *name, const void *string_type,
                                   void *allocation_failure) {
    if (name == NULL || string_type == NULL) { abort(); }
    const struct ironwood_string *key = name;
    const char *value = NULL;
    if (string_equals_ascii(key, "file.separator")) {
        value = "/";
    } else if (string_equals_ascii(key, "path.separator")) {
        value = ":";
    } else if (string_equals_ascii(key, "line.separator")) {
        value = "\n";
    } else if (string_equals_ascii(key, "file.encoding")
            || string_equals_ascii(key, "native.encoding")
            || string_equals_ascii(key, "stdout.encoding")
            || string_equals_ascii(key, "stderr.encoding")) {
        value = "UTF-8";
    } else if (string_equals_ascii(key, "os.name")) {
#if defined(__APPLE__)
        value = "Mac OS X";
#elif defined(__linux__)
        value = "Linux";
#else
        value = "Unknown";
#endif
    } else if (string_equals_ascii(key, "os.arch")) {
#if defined(__aarch64__) || defined(__arm64__)
        value = "aarch64";
#elif defined(__x86_64__) || defined(_M_X64)
        value = "amd64";
#elif defined(__i386__) || defined(_M_IX86)
        value = "x86";
#else
        value = "unknown";
#endif
    } else if (string_equals_ascii(key, "os.version")) {
        struct utsname host;
        if (uname(&host) == 0) { value = host.release; }
        return value == NULL ? NULL
                : string_from_utf8(value, string_type, allocation_failure);
    } else if (string_equals_ascii(key, "user.name")) {
        value = getenv("USER");
    } else if (string_equals_ascii(key, "user.home")) {
        value = getenv("HOME");
    } else if (string_equals_ascii(key, "user.dir")) {
        return ironwood_path_current_directory(string_type, allocation_failure);
    } else if (string_equals_ascii(key, "java.io.tmpdir")) {
        value = getenv("TMPDIR");
        if (value == NULL || value[0] == '\0') { value = "/tmp"; }
    } else if (string_equals_ascii(key, "user.language")) {
        value = "en";
    } else if (string_equals_ascii(key, "user.country")) {
        value = "US";
    }
    return value == NULL ? NULL
            : string_from_utf8(value, string_type, allocation_failure);
}

/* Scan a virtual join twice: first size it, then fill only the final String.
 * Both passes suppress repeated/trailing separators without staging either input. */
static int64_t path_syntax_pass(const uint16_t *left, int32_t left_length,
                                const uint16_t *right, int32_t right_length,
                                uint16_t *output) {
    _Bool join = left_length > 0 && right_length > 0;
    int64_t total = (int64_t) left_length + right_length + join;
    int64_t length = 0;
    _Bool separator = 0;
    for (int64_t index = 0; index < total; index++) {
        uint16_t unit = index < left_length ? left[index]
                : join && index == left_length ? UINT16_C('/')
                : right[index - left_length - join];
        if (unit != UINT16_C('/') || !separator) {
            if (output != NULL) { output[length] = unit; }
            length++;
        }
        separator = unit == UINT16_C('/');
    }
    return length > 1 && separator ? length - 1 : length;
}

static struct ironwood_string *normalized_join(const uint16_t *left, int32_t left_length,
                                               const uint16_t *right, int32_t right_length,
                                               const void *type, void *failure) {
    int64_t length = path_syntax_pass(left, left_length, right, right_length, NULL);
    if (length > INT32_MAX) { raise_allocation_failure(failure); }
    struct ironwood_string *result = allocate_string((int32_t) length, type, failure);
    /* The sizing pass excludes a final separator. Never write that discarded unit. */
    int32_t trimmed_right = right_length;
    int32_t trimmed_left = left_length;
    while (trimmed_right > 0 && right[trimmed_right - 1] == UINT16_C('/')) { trimmed_right--; }
    if (trimmed_right == 0) {
        while (trimmed_left > 0 && left[trimmed_left - 1] == UINT16_C('/')) { trimmed_left--; }
    }
    if (length == 1 && trimmed_left == 0 && trimmed_right == 0) {
        result->units[0] = UINT16_C('/');
    } else {
        path_syntax_pass(left, trimmed_left, right, trimmed_right, result->units);
    }
    result->utf8_length = utf8_length_of_utf16(result->units, (int32_t) length);
    return result;
}

void *ironwood_path_normalize_syntax(const void *path, const void *string_type,
                                     void *allocation_failure) {
    const struct ironwood_string *input = path;
    if (input == NULL || string_type == NULL) { abort(); }
    return normalized_join(input->units, input->utf16_length, NULL, 0,
            string_type, allocation_failure);
}

/* Walking right-to-left lets a scalar count of pending ".." components replace
 * an input-sized stack. Two linear passes count and then copy surviving names. */
static int32_t normalize_path_pass(const struct ironwood_string *input,
                                   uint16_t *output, int32_t output_length) {
    _Bool absolute = input->utf16_length > 0 && input->units[0] == UINT16_C('/');
    int32_t pending = 0;
    int32_t length = 0;
    int32_t segments = 0;
    int32_t position = output_length;
    int32_t end = input->utf16_length;
    while (end > 0) {
        while (end > 0 && input->units[end - 1] == UINT16_C('/')) { end--; }
        int32_t start = end;
        while (start > 0 && input->units[start - 1] != UINT16_C('/')) { start--; }
        int32_t count = end - start;
        end = start;
        if (count == 0 || (count == 1 && input->units[start] == UINT16_C('.'))) { continue; }
        if (count == 2 && input->units[start] == UINT16_C('.')
                && input->units[start + 1] == UINT16_C('.')) { pending++; continue; }
        if (pending > 0) { pending--; continue; }
        if (segments++ > 0) {
            length++;
            if (output != NULL) { output[--position] = UINT16_C('/'); }
        }
        length += count;
        if (output != NULL) {
            position -= count;
            memcpy(output + position, input->units + start, (size_t) count * sizeof(uint16_t));
        }
    }
    if (!absolute) {
        while (pending-- > 0) {
            if (segments++ > 0) {
                length++;
                if (output != NULL) { output[--position] = UINT16_C('/'); }
            }
            length += 2;
            if (output != NULL) {
                output[--position] = UINT16_C('.');
                output[--position] = UINT16_C('.');
            }
        }
    } else {
        length++;
        if (output != NULL) { output[--position] = UINT16_C('/'); }
    }
    return length;
}

void *ironwood_path_normalize(const void *path, const void *string_type,
                              void *allocation_failure) {
    const struct ironwood_string *input = path;
    if (input == NULL || string_type == NULL) { abort(); }
    int32_t length = normalize_path_pass(input, NULL, 0);
    struct ironwood_string *result = allocate_string(length, string_type, allocation_failure);
    normalize_path_pass(input, result->units, length);
    result->utf8_length = utf8_length_of_utf16(result->units, length);
    return result;
}

static int32_t parent_path_length(const struct ironwood_string *input) {
    if (input->utf16_length == 0
            || (input->utf16_length == 1 && input->units[0] == UINT16_C('/'))) { return -1; }
    int32_t separator = input->utf16_length - 1;
    while (separator >= 0 && input->units[separator] != UINT16_C('/')) { separator--; }
    return separator == 0 ? 1 : separator;
}

void *ironwood_path_file_name(const void *path, const void *string_type,
                              void *allocation_failure) {
    const struct ironwood_string *input = path;
    if (input == NULL || string_type == NULL) { abort(); }
    if (input->utf16_length == 1 && input->units[0] == UINT16_C('/')) { return NULL; }
    int32_t separator = input->utf16_length - 1;
    while (separator >= 0 && input->units[separator] != UINT16_C('/')) { separator--; }
    return path_string_from_units(input->units + separator + 1,
            input->utf16_length - separator - 1, string_type, allocation_failure);
}

void *ironwood_path_parent(const void *path, const void *string_type, void *allocation_failure) {
    const struct ironwood_string *input = path;
    if (input == NULL || string_type == NULL) { abort(); }
    int32_t length = parent_path_length(input);
    return length < 0 ? NULL : path_string_from_units(input->units, length, string_type,
            allocation_failure);
}

static void *resolve_path(const struct ironwood_string *left, int32_t left_length,
                           const struct ironwood_string *right,
                           const void *type, void *failure) {
    if (right->utf16_length > 0 && right->units[0] == UINT16_C('/')) { left_length = 0; }
    return normalized_join(left->units, left_length, right->units, right->utf16_length,
            type, failure);
}

void *ironwood_path_resolve(const void *base, const void *other,
                            const void *string_type, void *allocation_failure) {
    const struct ironwood_string *left = base;
    const struct ironwood_string *right = other;
    if (left == NULL || right == NULL || string_type == NULL) { abort(); }
    return resolve_path(left, left->utf16_length, right, string_type, allocation_failure);
}

void *ironwood_path_resolve_sibling(const void *base, const void *other,
                                    const void *string_type, void *allocation_failure) {
    const struct ironwood_string *left = base;
    const struct ironwood_string *right = other;
    if (left == NULL || right == NULL || string_type == NULL) { abort(); }
    int32_t parent_length = parent_path_length(left);
    return resolve_path(left, parent_length < 0 ? 0 : parent_length, right,
            string_type, allocation_failure);
}

void *ironwood_path_absolute(const void *path, const void *string_type,
                             void *allocation_failure) {
    const struct ironwood_string *input = path;
    if (input == NULL || string_type == NULL) { abort(); }
    if (input->utf16_length > 0 && input->units[0] == UINT16_C('/')) {
        return ironwood_path_normalize_syntax(path, string_type, allocation_failure);
    }
    char stack[IRONWOOD_PATH_STACK_CAPACITY];
    char *directory = current_directory_buffer(stack, sizeof(stack), allocation_failure);
    if (directory == NULL) { return NULL; }
    size_t byte_length = strlen(directory);
    if (byte_length > INT32_MAX || !valid_utf8((const unsigned char *) directory, byte_length)) {
        if (directory != stack) { free(directory); }
        return NULL;
    }
    int64_t length = 0;
    for (size_t index = 0; index < byte_length;) {
        uint32_t point = decode_utf8_code_point((const unsigned char *) directory, byte_length, &index);
        length += point <= 0xFFFF ? 1 : 2;
    }
    _Bool separator = input->utf16_length > 0
            && (byte_length == 0 || directory[byte_length - 1] != '/');
    length += (int64_t) input->utf16_length + separator;
    struct ironwood_string *result = length > INT32_MAX ? NULL
            : try_allocate_string((int32_t) length, string_type, allocation_failure);
    if (result == NULL) {
        if (directory != stack) { free(directory); }
        raise_allocation_failure(allocation_failure);
    }
    int32_t position = 0;
    for (size_t index = 0; index < byte_length;) {
        uint32_t point = decode_utf8_code_point((const unsigned char *) directory, byte_length, &index);
        if (point <= 0xFFFF) { result->units[position++] = (uint16_t) point; }
        else {
            point -= 0x10000;
            result->units[position++] = (uint16_t) (0xD800 + (point >> 10));
            result->units[position++] = (uint16_t) (0xDC00 + (point & 0x3FF));
        }
    }
    if (directory != stack) { free(directory); }
    if (separator) { result->units[position++] = UINT16_C('/'); }
    memcpy(result->units + position, input->units, (size_t) input->utf16_length * sizeof(uint16_t));
    result->utf8_length = utf8_length_of_utf16(result->units, (int32_t) length);
    return result;
}

void *ironwood_object_to_string(const void *object, const void *string_type,
                                void *allocation_failure) {
    if (object == NULL || string_type == NULL) {
        abort();
    }
    const char *type_name = object_type_name(object);
    uint32_t hash = identity_hash(object);
    size_t name_length = strlen(type_name);
    int hash_digits = 1;
    for (uint32_t remaining = hash; remaining >= UINT32_C(16); remaining >>= 4) {
        hash_digits++;
    }
    if (name_length > (size_t) INT32_MAX - 1U - (size_t) hash_digits) {
        raise_allocation_failure(allocation_failure);
    }
    int32_t text_length = (int32_t) name_length + 1 + hash_digits;
    struct ironwood_string *result = allocate_string(text_length, string_type,
            allocation_failure);
    int32_t position = 0;
    for (size_t index = 0; index < name_length; index++) {
        result->units[position++] = (uint16_t) (unsigned char) type_name[index];
    }
    result->units[position++] = UINT16_C('@');
    static const char hexadecimal[] = "0123456789abcdef";
    for (int digit = hash_digits - 1; digit >= 0; digit--) {
        result->units[position++] = (uint16_t) hexadecimal[(hash >> (digit * 4)) & UINT32_C(0xF)];
    }
    result->utf8_length = text_length;
    return result;
}

void *ironwood_throwable_description(const void *object, const void *localized_message,
                                     const void *string_type, void *allocation_failure) {
    const struct ironwood_string *message = localized_message;
    const unsigned char *name = (const unsigned char *) object_type_name(object);
    size_t name_bytes = strlen((const char *) name);
    if (name_bytes > (size_t) INT32_MAX) {
        raise_allocation_failure(allocation_failure);
    }
    int64_t length = message == NULL ? 0 : (int64_t) message->utf16_length + 2;
    for (size_t index = 0; index < name_bytes;) {
        uint32_t point = decode_utf8_code_point(name, name_bytes, &index);
        length += point <= UINT32_C(0xFFFF) ? 1 : 2;
    }
    if (length > INT32_MAX) {
        raise_allocation_failure(allocation_failure);
    }
    struct ironwood_string *result = allocate_string((int32_t) length, string_type,
            allocation_failure);
    int32_t position = 0;
    for (size_t index = 0; index < name_bytes;) {
        uint32_t point = decode_utf8_code_point(name, name_bytes, &index);
        if (point <= UINT32_C(0xFFFF)) {
            result->units[position++] = (uint16_t) point;
        } else {
            point -= UINT32_C(0x10000);
            result->units[position++] = (uint16_t) (UINT32_C(0xD800) + (point >> 10));
            result->units[position++] = (uint16_t) (UINT32_C(0xDC00) + (point & UINT32_C(0x3FF)));
        }
    }
    if (message != NULL) {
        result->units[position++] = UINT16_C(':');
        result->units[position++] = UINT16_C(' ');
        memcpy(result->units + position, message->units,
                (size_t) message->utf16_length * sizeof(uint16_t));
    }
    result->utf8_length = utf8_length_of_utf16(result->units, (int32_t) length);
    return result;
}

uint16_t ironwood_string_char_at(const void *string, int32_t index) {
    const struct ironwood_string *value = string;
    return value->units[index];
}

_Bool ironwood_string_equals(const void *string, const void *other, const void *string_type) {
    if (string == other) {
        return 1;
    }
    if (string == NULL || other == NULL || string_type == NULL
            || *(const void *const *) other != string_type) {
        return 0;
    }
    const struct ironwood_string *left = string;
    const struct ironwood_string *right = other;
    return left->utf16_length == right->utf16_length
            && (left->utf16_length == 0
            || memcmp(left->units, right->units,
                    (size_t) left->utf16_length * sizeof(uint16_t)) == 0);
}

int32_t ironwood_string_hash_code(const void *string) {
    const struct ironwood_string *value = string;
    uint32_t hash = 0;
    for (int32_t index = 0; index < value->utf16_length; index++) {
        hash = hash * UINT32_C(31) + value->units[index];
    }
    if (hash <= (uint32_t) INT32_MAX) {
        return (int32_t) hash;
    }
    return -(int32_t) (UINT32_MAX - hash) - 1;
}

void *ironwood_string_copy(const void *source, const void *string_type,
                           void *allocation_failure) {
    const struct ironwood_string *value = source;
    if (value == NULL || string_type == NULL || value->utf16_length < 0) {
        abort();
    }
    struct ironwood_string *result = allocate_string(value->utf16_length, string_type,
            allocation_failure);
    if (value->utf16_length > 0) {
        memcpy(result->units, value->units,
                (size_t) value->utf16_length * sizeof(uint16_t));
    }
    result->utf8_length = value->utf8_length;
    return result;
}

/* Consume one scalar or malformed prefix. Leave an interrupting byte for the
 * next step, but consume a complete UTF-8 surrogate encoding as one error.
 * The argv/environment decoder intentionally keeps its older per-byte rule. */
static uint32_t decode_utf8_replacing(const unsigned char *bytes, size_t length,
                                      size_t *index) {
    unsigned char first = bytes[(*index)++];
    if (first < UINT8_C(0x80)) {
        return first;
    }
    int needed = first >= UINT8_C(0xC2) && first <= UINT8_C(0xDF) ? 1
            : first >= UINT8_C(0xE0) && first <= UINT8_C(0xEF) ? 2
            : first >= UINT8_C(0xF0) && first <= UINT8_C(0xF4) ? 3 : 0;
    if (needed == 0) {
        return UINT32_C(0xFFFD);
    }
    uint32_t point = first & (needed == 1 ? UINT8_C(0x1F)
            : needed == 2 ? UINT8_C(0x0F) : UINT8_C(0x07));
    for (int i = 0; i < needed; i++) {
        if (*index == length) {
            return UINT32_C(0xFFFD);
        }
        unsigned char next = bytes[*index];
        if (!is_utf8_continuation(next)
                || (i == 0 && ((first == UINT8_C(0xE0) && next < UINT8_C(0xA0))
                || (first == UINT8_C(0xF0) && next < UINT8_C(0x90))
                || (first == UINT8_C(0xF4) && next > UINT8_C(0x8F))))) {
            return UINT32_C(0xFFFD);
        }
        (*index)++;
        point = (point << 6) | (next & UINT8_C(0x3F));
    }
    return point >= UINT32_C(0xD800) && point <= UINT32_C(0xDFFF)
            ? UINT32_C(0xFFFD) : point;
}

void *ironwood_string_from_utf8(const void *source, int32_t length,
                                const void *string_type, void *allocation_failure) {
    /* The private ByteArrayOutputStream helper supplies its valid written prefix.
     * Two passes copy that prefix without an intermediate byte or char array. */
    const struct ironwood_array *array = source;
    const unsigned char *bytes = array->data;
    size_t byte_length = (size_t) length;
    int32_t units = 0;
    int64_t encoded_length = 0;
    for (size_t index = 0; index < byte_length;) {
        uint32_t point = decode_utf8_replacing(bytes, byte_length, &index);
        units += point <= UINT32_C(0xFFFF) ? 1 : 2;
        encoded_length += point <= UINT32_C(0x7F) ? 1
                : point <= UINT32_C(0x7FF) ? 2 : point <= UINT32_C(0xFFFF) ? 3 : 4;
    }
    if (encoded_length > INT32_MAX) {
        raise_allocation_failure(allocation_failure);
    }
    struct ironwood_string *result = allocate_string(units, string_type, allocation_failure);
    int32_t position = 0;
    for (size_t index = 0; index < byte_length;) {
        uint32_t point = decode_utf8_replacing(bytes, byte_length, &index);
        if (point <= UINT32_C(0xFFFF)) {
            result->units[position++] = (uint16_t) point;
        } else {
            point -= UINT32_C(0x10000);
            result->units[position++] = (uint16_t) (UINT32_C(0xD800) + (point >> 10));
            result->units[position++] = (uint16_t) (UINT32_C(0xDC00) + (point & UINT32_C(0x3FF)));
        }
    }
    result->utf8_length = (int32_t) encoded_length;
    return result;
}

void *ironwood_string_from_chars(const void *characters, int32_t length,
                                 const void *string_type, void *allocation_failure) {
    const struct ironwood_array *array = characters;
    if (array == NULL || length < 0 || (size_t) length > array->length
            || array->element_kind != IRONWOOD_ARRAY_CHAR
            || array->element_size != sizeof(uint16_t)) {
        abort();
    }
    struct ironwood_string *result = allocate_string(length, string_type,
            allocation_failure);
    if (length > 0) {
        memcpy(result->units, array->data, (size_t) length * sizeof(uint16_t));
    }
    result->utf8_length = utf8_length_of_utf16(result->units, length);
    return result;
}

void *ironwood_string_from_char_range(const void *characters, int32_t offset, int32_t length,
                                      const void *string_type, void *allocation_failure) {
    const struct ironwood_array *array = characters;
    if (array == NULL || offset < 0 || length < 0
            || (size_t) offset > array->length
            || (size_t) length > array->length - (size_t) offset
            || array->element_kind != IRONWOOD_ARRAY_CHAR
            || array->element_size != sizeof(uint16_t)) {
        abort();
    }
    struct ironwood_string *result = allocate_string(length, string_type,
            allocation_failure);
    if (length > 0) {
        memcpy(result->units,
                (const uint16_t *) array->data + offset,
                (size_t) length * sizeof(uint16_t));
    }
    result->utf8_length = utf8_length_of_utf16(result->units, length);
    return result;
}

void *ironwood_string_from_range(const void *source, int32_t begin_index, int32_t length,
                                 const void *string_type, void *allocation_failure) {
    const struct ironwood_string *string = source;
    if (string == NULL || begin_index < 0 || length < 0
            || begin_index > string->utf16_length
            || length > string->utf16_length - begin_index) {
        abort();
    }
    struct ironwood_string *result = allocate_string(length, string_type,
            allocation_failure);
    if (length > 0) {
        memcpy(result->units, string->units + begin_index,
                (size_t) length * sizeof(uint16_t));
    }
    result->utf8_length = utf8_length_of_utf16(result->units, length);
    return result;
}

/* Direct-result String operations. Parameters are validated by their source facades. */
struct string_sink {
    uint16_t *units;
    int64_t length;
    int64_t bytes;
    uint16_t previous;
};

static void string_sink_unit(struct string_sink *sink, uint16_t unit) {
    if (sink->units != NULL) sink->units[sink->length] = unit;
    sink->length++;
    sink->bytes += unit < 128 ? 1 : unit < 2048 ? 2 : 3;
    if (unit >= 0xDC00 && unit <= 0xDFFF
            && sink->previous >= 0xD800 && sink->previous <= 0xDBFF) sink->bytes -= 2;
    sink->previous = unit;
}

static void string_sink_text(struct string_sink *sink, const struct ironwood_string *text) {
    if (text->utf16_length == 0) return;
    if (sink->units != NULL) {
        memcpy(sink->units + sink->length, text->units, (size_t) text->utf16_length * sizeof(uint16_t));
    }
    sink->length += text->utf16_length;
    sink->bytes += text->utf8_length;
    if (text->units[0] >= 0xDC00 && text->units[0] <= 0xDFFF
            && sink->previous >= 0xD800 && sink->previous <= 0xDBFF) sink->bytes -= 2;
    sink->previous = text->units[text->utf16_length - 1];
}

static struct ironwood_string *allocate_measured_string(struct string_sink *sink,
        const void *string_type, void *allocation_failure) {
    if (sink->length > INT32_MAX || sink->bytes > INT32_MAX) {
        raise_allocation_failure(allocation_failure);
    }
    struct ironwood_string *result = allocate_string((int32_t) sink->length,
            string_type, allocation_failure);
    result->utf8_length = (int32_t) sink->bytes;
    return result;
}

void *ironwood_string_repeat(const void *source, int32_t count,
        const void *string_type, void *allocation_failure) {
    const struct ironwood_string *text = source;
    struct string_sink measured = {0};
    measured.length = (int64_t) text->utf16_length * count;
    measured.bytes = (int64_t) text->utf8_length * count;
    if (count > 1 && text->utf16_length > 0
            && text->units[0] >= 0xDC00 && text->units[0] <= 0xDFFF
            && text->units[text->utf16_length - 1] >= 0xD800
            && text->units[text->utf16_length - 1] <= 0xDBFF) {
        measured.bytes -= 2 * (int64_t) (count - 1);
    }
    struct ironwood_string *result = allocate_measured_string(&measured, string_type, allocation_failure);
    if (measured.length != 0) {
        size_t copied = (size_t) text->utf16_length;
        memcpy(result->units, text->units, copied * sizeof(uint16_t));
        while (copied < (size_t) measured.length) {
            size_t next = (size_t) measured.length - copied;
            if (next > copied) next = copied;
            memcpy(result->units + copied, result->units, next * sizeof(uint16_t));
            copied += next;
        }
    }
    return result;
}

void *ironwood_string_replace_char(const void *source, uint16_t old_char, uint16_t new_char,
        const void *string_type, void *allocation_failure) {
    const struct ironwood_string *text = source;
    struct string_sink measured = {0};
    for (int32_t i = 0; i < text->utf16_length; i++) {
        string_sink_unit(&measured, text->units[i] == old_char ? new_char : text->units[i]);
    }
    struct ironwood_string *result = allocate_measured_string(&measured, string_type, allocation_failure);
    for (int32_t i = 0; i < text->utf16_length; i++) {
        result->units[i] = text->units[i] == old_char ? new_char : text->units[i];
    }
    return result;
}

static void replace_text_into(struct string_sink *sink, const struct ironwood_string *text,
        const struct ironwood_string *target, const struct ironwood_string *replacement) {
    int32_t i = 0;
    while (i <= text->utf16_length) {
        _Bool match = target->utf16_length <= text->utf16_length - i
                && (target->utf16_length == 0 || memcmp(text->units + i, target->units,
                    (size_t) target->utf16_length * sizeof(uint16_t)) == 0);
        if (match) {
            string_sink_text(sink, replacement);
            if (target->utf16_length != 0) {
                i += target->utf16_length;
                continue;
            }
        }
        if (i == text->utf16_length) break;
        string_sink_unit(sink, text->units[i++]);
    }
}

void *ironwood_string_replace_text(const void *source, const void *target, const void *replacement,
        const void *string_type, void *allocation_failure) {
    struct string_sink measured = {0};
    replace_text_into(&measured, source, target, replacement);
    struct ironwood_string *result = allocate_measured_string(&measured, string_type, allocation_failure);
    struct string_sink output = {.units = result->units};
    replace_text_into(&output, source, target, replacement);
    return result;
}

static void join_into(struct string_sink *sink, const struct ironwood_string *delimiter,
        const struct ironwood_array *elements) {
    const struct ironwood_string *const *texts = (const void *) elements->data;
    const uint16_t null_text[] = {'n', 'u', 'l', 'l'};
    for (size_t i = 0; i < elements->length; i++) {
        if (i != 0) string_sink_text(sink, delimiter);
        const struct ironwood_string *text = texts[i];
        if (text == NULL) {
            for (int32_t j = 0; j < 4; j++) string_sink_unit(sink, null_text[j]);
        } else {
            string_sink_text(sink, text);
        }
    }
}

void *ironwood_string_join(const void *delimiter, const void *elements,
        const void *string_type, void *allocation_failure) {
    struct string_sink measured = {0};
    join_into(&measured, delimiter, elements);
    struct ironwood_string *result = allocate_measured_string(&measured, string_type, allocation_failure);
    struct string_sink output = {.units = result->units};
    join_into(&output, delimiter, elements);
    return result;
}

void *ironwood_string_case(const void *source, _Bool upper,
        const void *string_type, void *allocation_failure) {
    const struct ironwood_string *text = source;
    struct string_sink measured = {0};
    ironwood_case_convert(text->units, text->utf16_length, upper, NULL,
            &measured.length, &measured.bytes);
    struct ironwood_string *result = allocate_measured_string(&measured, string_type, allocation_failure);
    ironwood_case_convert(text->units, text->utf16_length, upper, result->units,
            &measured.length, &measured.bytes);
    return result;
}

_Bool ironwood_string_equals_ignore_case(const void *source, const void *other) {
    const struct ironwood_string *first = source, *second = other;
    return ironwood_case_equal(first->units, first->utf16_length, second->units, second->utf16_length);
}

static int32_t signed_integer_length(int64_t value, int32_t radix) {
    int32_t length = value < 0 ? 2 : 1;
    /* Keeping the magnitude negative also represents INT64_MIN without overflow. */
    int64_t probe = value < 0 ? value : -value;
    while (probe <= -radix) {
        length++;
        probe /= radix;
    }
    return length;
}

static int32_t write_signed_integer(uint16_t *destination, int64_t value, int32_t radix) {
    static const char digits[] = "0123456789abcdefghijklmnopqrstuvwxyz";
    int32_t length = signed_integer_length(value, radix);
    int64_t current = value < 0 ? value : -value;
    int32_t write = length - 1;
    while (current <= -radix) {
        int64_t quotient = current / radix;
        destination[write--] = (uint16_t) digits[quotient * radix - current];
        current = quotient;
    }
    destination[write] = (uint16_t) digits[-current];
    if (value < 0) {
        destination[0] = UINT16_C('-');
    }
    return length;
}

void *ironwood_string_from_integer(int64_t value, int32_t radix,
                                    const void *string_type, void *allocation_failure) {
    if (radix < 2 || radix > 36) {
        radix = 10;
    }
    int32_t length = signed_integer_length(value, radix);
    struct ironwood_string *result = allocate_string(length, string_type,
            allocation_failure);
    write_signed_integer(result->units, value, radix);
    result->utf8_length = length;
    return result;
}

void *ironwood_string_from_character(uint16_t value, const void *string_type,
                                      void *allocation_failure) {
    struct ironwood_string *result = allocate_string(1, string_type, allocation_failure);
    result->units[0] = value;
    result->utf8_length = utf8_length_of_utf16(result->units, 1);
    return result;
}

static uint32_t float_bits(float value) {
    uint32_t bits;
    memcpy(&bits, &value, sizeof(bits));
    return bits;
}

static uint64_t double_bits(double value) {
    uint64_t bits;
    memcpy(&bits, &value, sizeof(bits));
    return bits;
}

/* Produces the shortest libc decimal that round-trips to the original IEEE
 * value. The second stage below gives that digit sequence Java's plain/E
 * presentation thresholds and spelling. No locale API is exposed by Ironwood,
 * so native programs retain the C locale installed at process start. */
static void shortest_round_trip(double value, _Bool single, char output[64]) {
    int maximum = single ? 9 : 17;
    /* Java's canonical form retains at least two significand digits when that
     * is needed to choose among equally short printed forms (for example the
     * minimum subnormal values become 1.4E-45 and 4.9E-324, not 1.0E-45 and
     * 5.0E-324). */
    for (int precision = 2; precision <= maximum; precision++) {
        int written = snprintf(output, 64, "%.*g", precision, value);
        if (written < 1 || written >= 64) {
            abort();
        }
        char *end = NULL;
        if (single) {
            float parsed = strtof(output, &end);
            if (end != NULL && *end == '\0'
                    && float_bits(parsed) == float_bits((float) value)) {
                return;
            }
        } else {
            double parsed = strtod(output, &end);
            if (end != NULL && *end == '\0'
                    && double_bits(parsed) == double_bits(value)) {
                return;
            }
        }
    }
    abort();
}

static int32_t java_floating_text(uint64_t payload, _Bool single, char output[64]) {
    double value;
    if (single) {
        uint32_t bits = (uint32_t) payload;
        float decoded;
        memcpy(&decoded, &bits, sizeof(decoded));
        value = decoded;
    } else {
        memcpy(&value, &payload, sizeof(value));
    }
    if (isnan(value)) {
        memcpy(output, "NaN", 4);
        return 3;
    }
    if (isinf(value)) {
        const char *text = signbit(value) ? "-Infinity" : "Infinity";
        int32_t length = signbit(value) ? 9 : 8;
        memcpy(output, text, (size_t) length + 1);
        return length;
    }
    if (value == 0.0) {
        const char *text = signbit(value) ? "-0.0" : "0.0";
        int32_t length = signbit(value) ? 4 : 3;
        memcpy(output, text, (size_t) length + 1);
        return length;
    }

    char raw[64];
    shortest_round_trip(value, single, raw);
    int32_t raw_length = (int32_t) strlen(raw);
    int32_t raw_position = raw[0] == '-' ? 1 : 0;
    _Bool negative = raw_position == 1;
    int32_t exponent_marker = raw_length;
    for (int32_t index = raw_position; index < raw_length; index++) {
        if (raw[index] == 'e' || raw[index] == 'E') {
            exponent_marker = index;
            break;
        }
    }
    int explicit_exponent = exponent_marker == raw_length
            ? 0 : (int) strtol(raw + exponent_marker + 1, NULL, 10);
    char digits[32];
    int32_t digit_count = 0;
    int32_t digits_before_point = 0;
    _Bool saw_point = 0;
    for (int32_t index = raw_position; index < exponent_marker; index++) {
        if (raw[index] == '.' || raw[index] == ',') {
            saw_point = 1;
        } else {
            digits[digit_count++] = raw[index];
            if (!saw_point) {
                digits_before_point++;
            }
        }
    }
    int32_t first = 0;
    while (first < digit_count && digits[first] == '0') {
        first++;
    }
    if (first == digit_count) {
        abort();
    }
    int32_t last = digit_count - 1;
    while (last > first && digits[last] == '0') {
        last--;
    }
    int32_t significant_count = last - first + 1;
    int32_t decimal_exponent = explicit_exponent + digits_before_point - first - 1;
    int32_t position = 0;
    if (negative) {
        output[position++] = '-';
    }
    if (decimal_exponent >= -3 && decimal_exponent < 7) {
        if (decimal_exponent >= 0) {
            int32_t integral_digits = decimal_exponent + 1;
            for (int32_t index = 0; index < integral_digits; index++) {
                output[position++] = index < significant_count ? digits[first + index] : '0';
            }
            output[position++] = '.';
            if (significant_count <= integral_digits) {
                output[position++] = '0';
            } else {
                for (int32_t index = integral_digits; index < significant_count; index++) {
                    output[position++] = digits[first + index];
                }
            }
        } else {
            output[position++] = '0';
            output[position++] = '.';
            for (int32_t index = -1; index > decimal_exponent; index--) {
                output[position++] = '0';
            }
            for (int32_t index = 0; index < significant_count; index++) {
                output[position++] = digits[first + index];
            }
        }
    } else {
        output[position++] = digits[first];
        output[position++] = '.';
        if (significant_count == 1) {
            output[position++] = '0';
        } else {
            for (int32_t index = 1; index < significant_count; index++) {
                output[position++] = digits[first + index];
            }
        }
        output[position++] = 'E';
        if (decimal_exponent < 0) {
            output[position++] = '-';
        }
        int32_t exponent = decimal_exponent < 0 ? -decimal_exponent : decimal_exponent;
        char reversed[12];
        int32_t exponent_digits = 0;
        do {
            reversed[exponent_digits++] = (char) ('0' + exponent % 10);
            exponent /= 10;
        } while (exponent != 0);
        while (exponent_digits > 0) {
            output[position++] = reversed[--exponent_digits];
        }
    }
    if (position >= 64) {
        abort();
    }
    output[position] = '\0';
    return position;
}

static int32_t concat_part_length(const struct ironwood_string_concat_part *part,
                                  const struct ironwood_type_info *string_type) {
    switch (part->kind) {
        case IRONWOOD_CONCAT_STRING: {
            const struct ironwood_string *text =
                    (const struct ironwood_string *) (uintptr_t) part->payload;
            if (text == NULL) {
                return 4;
            }
            if (text->type != string_type || text->utf16_length < 0) {
                abort();
            }
            return text->utf16_length;
        }
        case IRONWOOD_CONCAT_BOOLEAN:
            return part->payload == 0 ? 5 : 4;
        case IRONWOOD_CONCAT_CHARACTER:
            return 1;
        case IRONWOOD_CONCAT_INTEGER:
            return signed_integer_length((int64_t) part->payload, 10);
        case IRONWOOD_CONCAT_FLOAT:
        case IRONWOOD_CONCAT_DOUBLE: {
            char text[64];
            return java_floating_text(part->payload,
                    part->kind == IRONWOOD_CONCAT_FLOAT, text);
        }
        default:
            abort();
    }
}

static int32_t write_concat_part(uint16_t *destination,
                                 const struct ironwood_string_concat_part *part) {
    switch (part->kind) {
        case IRONWOOD_CONCAT_STRING: {
            const struct ironwood_string *text =
                    (const struct ironwood_string *) (uintptr_t) part->payload;
            if (text == NULL) {
                static const uint16_t null_text[] = {'n', 'u', 'l', 'l'};
                memcpy(destination, null_text, sizeof(null_text));
                return 4;
            }
            if (text->utf16_length > 0) {
                memcpy(destination, text->units,
                        (size_t) text->utf16_length * sizeof(uint16_t));
            }
            return text->utf16_length;
        }
        case IRONWOOD_CONCAT_BOOLEAN: {
            static const uint16_t false_text[] = {'f', 'a', 'l', 's', 'e'};
            static const uint16_t true_text[] = {'t', 'r', 'u', 'e'};
            if (part->payload == 0) {
                memcpy(destination, false_text, sizeof(false_text));
                return 5;
            }
            memcpy(destination, true_text, sizeof(true_text));
            return 4;
        }
        case IRONWOOD_CONCAT_CHARACTER:
            destination[0] = (uint16_t) part->payload;
            return 1;
        case IRONWOOD_CONCAT_INTEGER:
            return write_signed_integer(destination, (int64_t) part->payload, 10);
        case IRONWOOD_CONCAT_FLOAT:
        case IRONWOOD_CONCAT_DOUBLE: {
            char text[64];
            int32_t length = java_floating_text(part->payload,
                    part->kind == IRONWOOD_CONCAT_FLOAT, text);
            for (int32_t index = 0; index < length; index++) {
                destination[index] = (uint16_t) (unsigned char) text[index];
            }
            return length;
        }
        default:
            abort();
    }
}

void *ironwood_string_concat(const struct ironwood_string_concat_part *parts,
                             int32_t count, const void *string_type,
                             void *allocation_failure) {
    if (parts == NULL || count < 2 || string_type == NULL) {
        abort();
    }
    int64_t total = 0;
    for (int32_t index = 0; index < count; index++) {
        total += concat_part_length(&parts[index], string_type);
        if (total > INT32_MAX) {
            raise_allocation_failure(allocation_failure);
        }
    }
    struct ironwood_string *result = allocate_string((int32_t) total, string_type,
            allocation_failure);
    int32_t position = 0;
    for (int32_t index = 0; index < count; index++) {
        position += write_concat_part(result->units + position, &parts[index]);
    }
    if (position != (int32_t) total) {
        abort();
    }
    result->utf8_length = utf8_length_of_utf16(result->units, result->utf16_length);
    return result;
}

void ironwood_deallocate(void *object) {
    if (object == NULL) {
        return;
    }
    atomic_fetch_sub_explicit(&live_allocation_count, UINT64_C(1), memory_order_relaxed);
    free(object);
}

void ironwood_release_owned_to_string_result(const void *object, void *result) {
    if (object == NULL) {
        return;
    }
    const struct ironwood_type_info *type =
            *(const struct ironwood_type_info *const *) object;
    if (type == NULL) {
        abort();
    }
    if (type->to_string_returns_owned_fresh) {
        ironwood_deallocate(result);
    }
}

void ironwood_release_owned_throwable_message(const void *object, void *message) {
    const struct ironwood_type_info *type =
            *(const struct ironwood_type_info *const *) object;
    if (type->localized_message_returns_owned_fresh) {
        ironwood_deallocate(message);
    }
}

_Noreturn void ironwood_destructor_failed(void) {
    fatal_emergency("fatal: an exception escaped an Ironwood destructor");
}

static void write_string(FILE *stream, const struct ironwood_string *value) {
    for (int32_t index = 0; index < value->utf16_length; index++) {
        uint32_t code_point = value->units[index];
        if (code_point >= UINT32_C(0xD800) && code_point <= UINT32_C(0xDBFF)
                && index + 1 < value->utf16_length
                && value->units[index + 1] >= UINT16_C(0xDC00)
                && value->units[index + 1] <= UINT16_C(0xDFFF)) {
            code_point = UINT32_C(0x10000)
                    + ((code_point - UINT32_C(0xD800)) << 10)
                    + (uint32_t) (value->units[++index] - UINT16_C(0xDC00));
        } else if (code_point >= UINT32_C(0xD800) && code_point <= UINT32_C(0xDFFF)) {
            code_point = UINT32_C(0xFFFD);
        }
        if (code_point <= UINT32_C(0x7F)) {
            fputc((int) code_point, stream);
        } else if (code_point <= UINT32_C(0x7FF)) {
            fputc((int) (UINT32_C(0xC0) | (code_point >> 6)), stream);
            fputc((int) (UINT32_C(0x80) | (code_point & UINT32_C(0x3F))), stream);
        } else if (code_point <= UINT32_C(0xFFFF)) {
            fputc((int) (UINT32_C(0xE0) | (code_point >> 12)), stream);
            fputc((int) (UINT32_C(0x80) | ((code_point >> 6) & UINT32_C(0x3F))), stream);
            fputc((int) (UINT32_C(0x80) | (code_point & UINT32_C(0x3F))), stream);
        } else {
            fputc((int) (UINT32_C(0xF0) | (code_point >> 18)), stream);
            fputc((int) (UINT32_C(0x80) | ((code_point >> 12) & UINT32_C(0x3F))), stream);
            fputc((int) (UINT32_C(0x80) | ((code_point >> 6) & UINT32_C(0x3F))), stream);
            fputc((int) (UINT32_C(0x80) | (code_point & UINT32_C(0x3F))), stream);
        }
    }
}

void ironwood_stdout_println(const void *string) {
    const struct ironwood_string *value = string;
    if (value == NULL) {
        fputs("null\n", stdout);
        return;
    }
    write_string(stdout, value);
    fputc('\n', stdout);
}

static FILE *print_stream_target(const void *stream) {
    const struct ironwood_print_stream *value = stream;
    if (value == NULL) {
        abort();
    }
    if (value->channel == 1) {
        return stdout;
    }
    if (value->channel == 2) {
        return stderr;
    }
    abort();
}

int32_t ironwood_stream_print_byte(const void *stream, int32_t value) {
    return fputc((unsigned char) value, print_stream_target(stream)) == EOF ? -1 : 0;
}

int32_t ironwood_stream_print_bytes(const void *stream, const void *buffer, int32_t offset, int32_t length) {
    const struct ironwood_array *array = buffer;
    if (array == NULL || offset < 0 || length < 0 || (size_t) offset > array->length
            || (size_t) length > array->length - (size_t) offset) { abort(); }
    return fwrite(array->data + offset, 1, (size_t) length, print_stream_target(stream))
            == (size_t) length ? 0 : -1;
}

static void write_code_point(FILE *stream, uint32_t code_point) {
    char bytes[4];
    int32_t length = encode_utf8_code_point(bytes, code_point);
    if (fwrite(bytes, 1, (size_t) length, stream) != (size_t) length) {
        return;
    }
}

void ironwood_print_stream_write(const void *stream, int32_t kind, uint64_t payload,
                                 _Bool newline) {
    FILE *target = print_stream_target(stream);
    switch (kind) {
        case -1:
            break;
        case IRONWOOD_CONCAT_STRING: {
            const struct ironwood_string *text =
                    (const struct ironwood_string *) (uintptr_t) payload;
            if (text == NULL) {
                fputs("null", target);
            } else {
                write_string(target, text);
            }
            break;
        }
        case IRONWOOD_CONCAT_BOOLEAN:
            fputs(payload == 0 ? "false" : "true", target);
            break;
        case IRONWOOD_CONCAT_CHARACTER: {
            uint32_t code_point = (uint16_t) payload;
            if (code_point >= UINT32_C(0xD800) && code_point <= UINT32_C(0xDFFF)) {
                code_point = UINT32_C(0xFFFD);
            }
            write_code_point(target, code_point);
            break;
        }
        case IRONWOOD_CONCAT_INTEGER: {
            char text[32];
            int written = snprintf(text, sizeof(text), "%lld",
                    (long long) (int64_t) payload);
            if (written < 1 || written >= (int) sizeof(text)) {
                abort();
            }
            fputs(text, target);
            break;
        }
        case IRONWOOD_CONCAT_FLOAT:
        case IRONWOOD_CONCAT_DOUBLE: {
            char text[64];
            java_floating_text(payload, kind == IRONWOOD_CONCAT_FLOAT, text);
            fputs(text, target);
            break;
        }
        default:
            abort();
    }
    if (newline) {
        fputc('\n', target);
    }
}

void ironwood_print_stream_flush(const void *stream) {
    fflush(print_stream_target(stream));
}

_Bool ironwood_print_stream_check_error(const void *stream) {
    FILE *target = print_stream_target(stream);
    fflush(target);
    return ferror(target) != 0;
}

void ironwood_trace_register(const struct ironwood_trace_site *sites, int32_t site_count,
                             const struct ironwood_trace_function *functions,
                             int32_t function_count) {
    if (sites == NULL || site_count < 0 || functions == NULL
            || function_count < 0) {
        abort();
    }
    trace_sites = sites;
    trace_site_count = site_count;
    trace_functions = functions;
    trace_function_count = function_count;
#if defined(__APPLE__)
    unsigned long section_size = 0;
    trace_section = getsectiondata(&_mh_execute_header, "__PSEUDO_PROBE", "__probes",
            &section_size);
    trace_section_size = (size_t) section_size;
#elif defined(__linux__)
    if (__start_ironwood_trace != NULL && __stop_ironwood_trace != NULL
            && __stop_ironwood_trace >= __start_ironwood_trace) {
        trace_section = __start_ironwood_trace;
        trace_section_size = (size_t) (__stop_ironwood_trace - __start_ironwood_trace);
    }
#else
    trace_section = NULL;
    trace_section_size = 0;
#endif
}

static _Noreturn void raise_language_exception(void *object) {
    if (object == NULL) {
        abort();
    }
    if (object == active_implicit_failure) capture_exception_trace(object);
    struct ironwood_exception *exception;
    if (active_implicit_failure != NULL) {
        if (emergency_exception_in_use) {
            fatal_emergency("ironwood: emergency exception delivery is already active");
        }
        exception = &emergency_exception;
        memset(exception, 0, sizeof(*exception));
        exception->emergency = 1;
        emergency_exception_in_use = 1;
    } else {
        exception = calloc(1, sizeof(*exception));
        if (exception == NULL) {
            fatal_emergency("ironwood: native exception wrapper allocation failed");
        }
    }
    exception->object = object;
    exception->unwind.exception_class = UINT64_C(0x49524f4e574f4f44);
    exception->unwind.exception_cleanup = cleanup_exception;
    _Unwind_Reason_Code reason = _Unwind_RaiseException(&exception->unwind);
    _Unwind_DeleteException(&exception->unwind);
    fprintf(stderr, "ironwood: native exception unwind failed (%d)\n", (int) reason);
    exit(1);
}

static _Noreturn void raise_allocation_failure(void *allocation_failure) {
    if (allocation_failure == NULL) {
        fatal_emergency("ironwood: allocation failed outside catchable source execution");
    }
    if (active_implicit_failure != NULL) {
        fatal_emergency("ironwood: allocation failed while implicit OutOfMemoryError is active");
    }
    prepare_implicit_failure(allocation_failure);
    raise_language_exception(allocation_failure);
}

_Noreturn void ironwood_throw(void *object) {
    raise_language_exception(object);
}

void *ironwood_exception_take(void *exception) {
    struct _Unwind_Exception *unwind = exception;
    struct ironwood_exception *wrapper = exception_wrapper(unwind);
    void *object = wrapper->object;
    _Unwind_DeleteException(unwind);
    return object;
}

void ironwood_exception_caught(void *object) {
    if (object == NULL || active_implicit_failure == NULL) {
        return;
    }
    if (object == active_implicit_failure) {
        active_implicit_failure = NULL;
        return;
    }
    struct ironwood_exception_metadata *metadata = find_exception_metadata(object);
    if (metadata == NULL) {
        return;
    }
    for (struct ironwood_secondary_exception *entry = metadata->first_secondary;
         entry != NULL; entry = entry->next) {
        if (entry->object == active_implicit_failure) {
            active_implicit_failure = NULL;
            return;
        }
    }
}

void ironwood_exception_add_secondary(void *primary, void *secondary) {
    if (primary == NULL || secondary == NULL) {
        abort();
    }
    if (primary == secondary) {
        return;
    }
    struct ironwood_exception_metadata *secondary_metadata =
            find_exception_metadata(secondary);
    struct ironwood_exception_metadata *metadata = find_exception_metadata(primary);
    if (metadata == NULL) {
        _Bool trace_unavailable = ((const struct ironwood_throwable *) primary)->trace_state == 1;
        metadata = create_exception_metadata(primary);
        if (metadata == NULL) {
            if (active_implicit_failure == NULL
                    || emergency_association_metadata_in_use) {
                fatal_emergency("ironwood: exception metadata storage exhausted");
            }
            memset(&emergency_association_metadata, 0,
                    sizeof(emergency_association_metadata));
            emergency_association_metadata.primary = primary;
            attach_exception_metadata(primary, &emergency_association_metadata);
            emergency_association_metadata_in_use = 1;
            metadata = &emergency_association_metadata;
        }
        if (trace_unavailable) { metadata->trace_state = IRONWOOD_TRACE_UNAVAILABLE; }
    }
    append_secondary_object(metadata, secondary);

    /* A secondary may already carry failures from cleanup nested inside the
     * current finally. Copy that completed sequence so callers always inspect
     * one flat occurrence-ordered list on the original primary. */
    if (secondary_metadata != NULL) {
        struct ironwood_secondary_exception *nested =
                secondary_metadata->first_secondary;
        while (nested != NULL) {
            if (nested->object != primary) {
                append_secondary_object(metadata, nested->object);
            }
            nested = nested->next;
        }
    }
}

int32_t ironwood_exception_secondary_count(const void *primary) {
    if (primary == NULL) {
        abort();
    }
    struct ironwood_exception_metadata *metadata = find_exception_metadata(primary);
    return metadata == NULL ? 0 : metadata->secondary_count;
}

void *ironwood_exception_secondary_at(const void *primary, int32_t index) {
    if (primary == NULL) {
        abort();
    }
    struct ironwood_exception_metadata *metadata = find_exception_metadata(primary);
    int32_t count = metadata == NULL ? 0 : metadata->secondary_count;
    if (index < 0 || index >= count) {
        fprintf(stderr, "ironwood: secondary exception index %d out of bounds for count %d\n",
                (int) index, (int) count);
        exit(1);
    }
    struct ironwood_secondary_exception *entry = metadata->first_secondary;
    for (int32_t current = 0; current < index; current++) {
        entry = entry->next;
    }
    return entry->object;
}

int32_t ironwood_throwable_trace_common(const void *object, const void *parent) {
    struct ironwood_exception_metadata *left = find_exception_metadata(object);
    struct ironwood_exception_metadata *right = find_exception_metadata(parent);
    if (left == NULL || right == NULL) { return 0; }
    int32_t common = 0;
    while (common < left->trace_count && common < right->trace_count) {
        struct ironwood_trace_element *a = &left->trace[left->trace_count - common - 1];
        struct ironwood_trace_element *b = &right->trace[right->trace_count - common - 1];
        if (a->site != b->site) { break; }
        common++;
    }
    return common;
}

void ironwood_throwable_trace_print(const void *object, const void *stream, int32_t indentation, int32_t common) {
    FILE *target = stream == NULL ? stderr : print_stream_target(stream);
    struct ironwood_exception_metadata *metadata = find_exception_metadata(object);
    if ((metadata == NULL && ((const struct ironwood_throwable *) object)->trace_state == 1)
            || (metadata != NULL && metadata->trace_state == IRONWOOD_TRACE_UNAVAILABLE)) {
        for (int32_t index = 0; index < indentation; index++) { fputc('\t', target); }
        fputs("\tat <trace unavailable>\n", target);
        return;
    }
    if (metadata == NULL) { return; }
    for (int32_t index = 0; index < metadata->trace_count - common; index++) {
        for (int32_t tab = 0; tab < indentation; tab++) { fputc('\t', target); }
        struct ironwood_trace_element *element = &metadata->trace[index];
        fprintf(target, "\tat %s(%s:%d)\n", element->site->callable,
                element->site->file, (int) element->site->line);
    }
    if (common > 0) {
        for (int32_t tab = 0; tab < indentation; tab++) { fputc('\t', target); }
        fprintf(target, "\t... %d more\n", (int) common);
    }
    if (metadata->trace_truncated) {
        for (int32_t tab = 0; tab < indentation; tab++) { fputc('\t', target); }
        fputs("\tat <trace truncated>\n", target);
    }
}

void ironwood_throwable_trace_emergency(const void *object, const void *stream) {
    FILE *target = print_stream_target(stream);
    fprintf(target, "%s", object_type_name(object));
    const struct ironwood_string *message = ((const struct ironwood_throwable *) object)->message;
    if (message != NULL) { fputs(": ", target); write_string(target, message); }
    fputc('\n', target);
    ironwood_throwable_trace_print(object, stream, 0, 0);
}

void *ironwood_throwable_trace_array(const void *object,
        const void *array_type, void *allocation_failure) {
    if (object == NULL || array_type == NULL) {
        abort();
    }
    struct ironwood_exception_metadata *metadata = find_exception_metadata(object);
    int32_t count = metadata == NULL
            || metadata->trace_state != IRONWOOD_TRACE_CAPTURED
            ? 0 : metadata->trace_count;
    struct ironwood_array *result = ironwood_allocate_array(count, sizeof(void *),
            IRONWOOD_ARRAY_REFERENCE, array_type, allocation_failure);
    void **elements = (void **) result->data;
    for (int32_t index = 0; index < count; index++) {
        const struct ironwood_trace_element *captured = &metadata->trace[index];
        const void *element = captured->site->element;
        if (element == NULL) { abort(); }
        elements[index] = (void *) element;
    }
    return result;
}

static void print_exception_report(const char *prefix, void *object, size_t message_offset) {
    fprintf(stderr, "%s%s", prefix, object_type_name(object));
    if (message_offset != 0) {
        const struct ironwood_string *message =
                *(const struct ironwood_string *const *) ((const char *) object + message_offset);
        if (message != NULL) {
            fputs(": ", stderr);
            write_string(stderr, message);
        }
    }
    fputc('\n', stderr);
    ironwood_throwable_trace_print(object, NULL, 0, 0);
}

_Noreturn void ironwood_uncaught_exception(void *object, size_t message_offset) {
    print_exception_report("uncaught Ironwood exception: ", object, message_offset);
    struct ironwood_exception_metadata *metadata = find_exception_metadata(object);
    if (metadata != NULL) {
        for (struct ironwood_secondary_exception *entry = metadata->first_secondary;
             entry != NULL; entry = entry->next) {
            print_exception_report("secondary Ironwood exception: ", entry->object,
                    message_offset);
        }
    }
    exit(1);
}
