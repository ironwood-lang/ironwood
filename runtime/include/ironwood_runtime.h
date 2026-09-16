// SPDX-License-Identifier: MIT OR Apache-2.0

#ifndef IRONWOOD_RUNTIME_H
#define IRONWOOD_RUNTIME_H

#include <stddef.h>
#include <stdint.h>

/* Shared compiler/native array ABI. Payload operations borrow this storage. */
struct ironwood_array {
    const struct ironwood_type_info *type;
    size_t length;
    size_t element_size;
    uint32_t element_kind;
    uint32_t reserved;
    unsigned char data[];
};

struct ironwood_trace_site {
    uint64_t guid;
    const char *callable;
    const char *file;
    const void *element;
    int32_t probe_index;
    int32_t line;
    int32_t owner_type;
    int32_t flags;
};

struct ironwood_trace_function {
    uint64_t guid;
    const void *address;
};

enum ironwood_string_concat_kind {
    IRONWOOD_CONCAT_STRING = 0,
    IRONWOOD_CONCAT_BOOLEAN = 1,
    IRONWOOD_CONCAT_CHARACTER = 2,
    IRONWOOD_CONCAT_INTEGER = 3,
    IRONWOOD_CONCAT_FLOAT = 4,
    IRONWOOD_CONCAT_DOUBLE = 5
};

struct ironwood_string_concat_part {
    uint32_t kind;
    uint64_t payload;
};

void *ironwood_allocate(size_t size, const void *object_type, void *allocation_failure);
void *ironwood_allocate_array(int32_t length, size_t element_size, uint32_t element_kind,
        const void *array_type, void *allocation_failure);
uint64_t ironwood_allocation_count(void);
uint64_t ironwood_live_allocation_count(void);
int32_t ironwood_identity_hash_code(const void *object);
int32_t ironwood_object_hash_code(const void *object);
void *ironwood_object_to_string(const void *object, const void *string_type,
        void *allocation_failure);
void ironwood_release_owned_to_string_result(const void *object, void *result);
uint16_t ironwood_string_char_at(const void *string, int32_t index);
_Bool ironwood_string_equals(const void *string, const void *other, const void *string_type);
int32_t ironwood_string_hash_code(const void *string);
void *ironwood_string_copy(const void *source, const void *string_type,
        void *allocation_failure);
void *ironwood_string_from_chars(const void *characters, int32_t length,
        const void *string_type, void *allocation_failure);
void *ironwood_string_from_utf8(const void *bytes, int32_t length,
        const void *string_type, void *allocation_failure);
void *ironwood_string_repeat(const void *source, int32_t count,
        const void *string_type, void *allocation_failure);
void *ironwood_string_replace_char(const void *source, uint16_t old_char, uint16_t new_char,
        const void *string_type, void *allocation_failure);
void *ironwood_string_replace_text(const void *source, const void *target, const void *replacement,
        const void *string_type, void *allocation_failure);
void *ironwood_string_join(const void *delimiter, const void *elements,
        const void *string_type, void *allocation_failure);
void *ironwood_string_case(const void *source, _Bool upper,
        const void *string_type, void *allocation_failure);
_Bool ironwood_string_equals_ignore_case(const void *source, const void *other);
void *ironwood_string_from_char_range(const void *characters, int32_t offset, int32_t length,
        const void *string_type, void *allocation_failure);
void *ironwood_string_from_range(const void *source, int32_t begin_index, int32_t length,
        const void *string_type, void *allocation_failure);
void *ironwood_string_from_integer(int64_t value, int32_t radix,
        const void *string_type, void *allocation_failure);
void *ironwood_string_from_character(uint16_t value, const void *string_type,
        void *allocation_failure);
void *ironwood_string_concat(const struct ironwood_string_concat_part *parts,
        int32_t count, const void *string_type, void *allocation_failure);
void *ironwood_process_arguments(int32_t argc, const char *const *argv,
        const void *string_type, const void *string_array_type);
void ironwood_system_arraycopy(const void *source, int32_t source_position,
        void *destination, int32_t destination_position, int32_t length);
void *ironwood_system_getenv(const void *name, const void *string_type,
        void *allocation_failure);
void *ironwood_system_get_property(const void *name, const void *string_type,
        void *allocation_failure);
_Noreturn void ironwood_system_exit(int32_t status);
int64_t ironwood_current_time_millis(void);
int64_t ironwood_nano_time(void);
float ironwood_parse_float(const void *text);
double ironwood_parse_double(const void *text);
int32_t ironwood_stream_open(const void *path, int32_t mode, void *allocation_failure);
int32_t ironwood_stream_read_byte(int32_t descriptor);
int32_t ironwood_stream_read_bytes(int32_t descriptor, void *buffer, int32_t offset, int32_t length);
int32_t ironwood_stream_write_byte(int32_t descriptor, int32_t value);
int32_t ironwood_stream_write_bytes(int32_t descriptor, const void *buffer, int32_t offset, int32_t length);
int32_t ironwood_stream_available(int32_t descriptor);
int64_t ironwood_stream_position(int32_t descriptor);
int64_t ironwood_stream_seek(int32_t descriptor, int64_t position);
int64_t ironwood_stream_length(int32_t descriptor);
int32_t ironwood_stream_set_length(int32_t descriptor, int64_t length);
int32_t ironwood_stream_close(int32_t descriptor);
int32_t ironwood_stream_print_byte(const void *stream, int32_t value);
int32_t ironwood_stream_print_bytes(const void *stream, const void *buffer, int32_t offset, int32_t length);

int32_t ironwood_file_same(const void *first, const void *second, void *allocation_failure);

void *ironwood_file_read_all_bytes(const void *path, const void *byte_array_type,
        void *allocation_failure);
void *ironwood_file_read_string(const void *path, const void *string_type,
        void *allocation_failure);
int32_t ironwood_file_write_bytes(const void *path, const void *bytes,
        void *allocation_failure);
int32_t ironwood_file_write_string(const void *path, const void *content,
        void *allocation_failure);
int32_t ironwood_file_delete(const void *path, void *allocation_failure);
int32_t ironwood_file_create_directories(const void *path, void *allocation_failure);
int32_t ironwood_file_copy(const void *source, const void *target, void *allocation_failure);
int32_t ironwood_file_move(const void *source, const void *target, void *allocation_failure);
int64_t ironwood_directory_open(const void *path, void *allocation_failure);
int32_t ironwood_directory_has_next(int64_t handle);
void *ironwood_directory_next(int64_t handle, const void *string_type,
        void *allocation_failure);
int32_t ironwood_directory_close(int64_t handle);
void *ironwood_file_read_attributes(const void *path, _Bool follow_links,
        const void *long_array_type, void *allocation_failure);
int32_t ironwood_file_kind(const void *path, void *allocation_failure);
int32_t ironwood_file_kind_nofollow(const void *path, void *allocation_failure);
int64_t ironwood_file_size(const void *path, void *allocation_failure);
int32_t ironwood_file_last_error(void);
void *ironwood_path_current_directory(const void *string_type, void *allocation_failure);
void *ironwood_path_absolute(const void *path, const void *string_type, void *allocation_failure);
void *ironwood_path_resolve_sibling(const void *base, const void *other,
        const void *string_type, void *allocation_failure);
int32_t ironwood_file_write_chars(const void *path, const void *content, void *allocation_failure);
void *ironwood_path_normalize_syntax(const void *path, const void *string_type,
        void *allocation_failure);
void *ironwood_path_normalize(const void *path, const void *string_type,
        void *allocation_failure);
void *ironwood_path_file_name(const void *path, const void *string_type,
        void *allocation_failure);
void *ironwood_path_parent(const void *path, const void *string_type,
        void *allocation_failure);
void *ironwood_path_resolve(const void *base, const void *other,
        const void *string_type, void *allocation_failure);
void ironwood_deallocate(void *object);
_Noreturn void ironwood_destructor_failed(void);
void ironwood_stdout_println(const void *string);
void ironwood_print_stream_write(const void *stream, int32_t kind, uint64_t payload,
        _Bool newline);
void ironwood_print_stream_flush(const void *stream);
_Bool ironwood_print_stream_check_error(const void *stream);
_Noreturn void ironwood_throw(void *object);
void *ironwood_exception_take(void *exception);
void ironwood_exception_caught(void *object);
void ironwood_exception_add_secondary(void *primary, void *secondary);
int32_t ironwood_exception_secondary_count(const void *primary);
void *ironwood_exception_secondary_at(const void *primary, int32_t index);
void ironwood_trace_register(const struct ironwood_trace_site *sites, int32_t site_count,
        const struct ironwood_trace_function *functions, int32_t function_count);
void ironwood_throwable_trace_capture(void *object);
void ironwood_throwable_trace_release(void *object);
int32_t ironwood_throwable_trace_common(const void *object, const void *parent);
void ironwood_throwable_trace_print(const void *object, const void *stream, int32_t indentation, int32_t common);
void ironwood_throwable_trace_emergency(const void *object, const void *stream);
void *ironwood_throwable_trace_array(const void *object,
        const void *array_type, void *allocation_failure);
_Noreturn void ironwood_uncaught_exception(void *object, size_t message_offset);

/* TCP attempts borrow their arguments and pack progress with captured errno. */
int64_t ironwood_tcp_create(int32_t family);
int64_t ironwood_tcp_bind(int32_t descriptor, int32_t socket_family, int32_t address_family,
        int32_t a, int32_t b, int32_t c, int32_t d, int32_t port, int32_t scope);
int64_t ironwood_tcp_connect(int32_t descriptor, int32_t socket_family, int32_t address_family,
        int32_t a, int32_t b, int32_t c, int32_t d, int32_t port, int32_t scope);
int64_t ironwood_tcp_listen(int32_t descriptor, int32_t backlog);
int64_t ironwood_tcp_accept(int32_t listener);
int64_t ironwood_tcp_complete_connect(int32_t descriptor);
int64_t ironwood_tcp_read_byte(int32_t descriptor);
int64_t ironwood_tcp_try_read_byte(int32_t descriptor);
int64_t ironwood_tcp_read_bytes(int32_t descriptor, void *buffer, int32_t offset, int32_t length);
int64_t ironwood_tcp_try_read_bytes(int32_t descriptor, void *buffer, int32_t offset, int32_t length);
int64_t ironwood_tcp_write_byte(int32_t descriptor, int32_t value);
int64_t ironwood_tcp_write_bytes(int32_t descriptor, const void *buffer, int32_t offset, int32_t length);
int64_t ironwood_tcp_urgent(int32_t descriptor, int32_t value);
int64_t ironwood_tcp_get_traffic_class(int32_t descriptor, int32_t family);
int64_t ironwood_tcp_set_traffic_class(int32_t descriptor, int32_t family, int32_t value);
int64_t ironwood_tcp_reuse_port_supported(void);
int64_t ironwood_tcp_available(int32_t descriptor);
int64_t ironwood_tcp_shutdown(int32_t descriptor, int32_t direction);
int64_t ironwood_tcp_close(int32_t descriptor);
int64_t ironwood_tcp_wait(int32_t descriptor, _Bool write_ready, int64_t remaining_nanos);
int64_t ironwood_tcp_blocking(int32_t descriptor, _Bool blocking);
int64_t ironwood_tcp_restore_flags(int32_t descriptor, int32_t flags);
int64_t ironwood_tcp_get_boolean(int32_t descriptor, int32_t code);
int64_t ironwood_tcp_set_boolean(int32_t descriptor, int32_t code, _Bool enabled);
int64_t ironwood_tcp_get_integer(int32_t descriptor, int32_t code);
int64_t ironwood_tcp_set_integer(int32_t descriptor, int32_t code, int32_t value);
int64_t ironwood_tcp_endpoint(int32_t descriptor, _Bool peer, int32_t *family,
        int32_t *a, int32_t *b, int32_t *c, int32_t *d, int32_t *port, int32_t *scope);
int32_t ironwood_tcp_error_kind(int32_t error);

/* Resolver query handles refer only to OS storage, never managed objects. */
int64_t ironwood_tcp_resolve_start(const void *host, int64_t *handle, int32_t *count);
int64_t ironwood_tcp_resolve_address(int64_t handle, int32_t index, int32_t *family,
        int32_t *a, int32_t *b, int32_t *c, int32_t *d, int32_t *scope,
        int64_t *cursor, int32_t *preferred);
int64_t ironwood_tcp_resolve_release(int64_t handle);
int64_t ironwood_tcp_reverse_name(int32_t family, int32_t a, int32_t b,
        int32_t c, int32_t d, int32_t scope, void *output);
int64_t ironwood_tcp_local_name(void *output);
int64_t ironwood_tcp_scope_id(const void *name, int32_t first);
int64_t ironwood_tcp_preferred_family(void);

/* Host snapshots use native-only handles and explicit primitive output fields. */
int64_t ironwood_tcp_interfaces_start(int64_t *handle, int32_t *count);
int64_t ironwood_tcp_interface_next(int64_t handle, int32_t position, void *name,
        int32_t *cursor, int32_t *index, int32_t *parent, int32_t *count, int32_t *address_cursor);
int64_t ironwood_tcp_interface_address(int64_t handle, int32_t position,
        int32_t *family, int32_t *a, int32_t *b, int32_t *c, int32_t *d, int32_t *scope,
        int32_t *prefix, int32_t *broadcast, int32_t *has_broadcast, int32_t *cursor);
int64_t ironwood_tcp_interfaces_release(int64_t handle);
int64_t ironwood_tcp_interface_flags(const void *name);
int64_t ironwood_tcp_interface_mtu(const void *name);
int64_t ironwood_tcp_interface_hardware(const void *name, void *output);
int64_t ironwood_tcp_reachable(int32_t family, int32_t a, int32_t b, int32_t c, int32_t d, int32_t scope,
        int32_t source_family, int32_t sa, int32_t sb, int32_t sc, int32_t sd, int32_t source_scope,
        int32_t interface_index, int32_t ttl, int32_t timeout);

#endif
