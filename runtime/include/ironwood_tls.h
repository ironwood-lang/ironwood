/* SPDX-License-Identifier: MIT OR Apache-2.0 */
#ifndef IRONWOOD_TLS_H
#define IRONWOOD_TLS_H
#include "ironwood_runtime.h"
#include <stdbool.h>
/* Private native handles never escape the managed connection owner. */
int64_t ironwood_tls_create(const struct ironwood_array *path);
int64_t ironwood_tls_configure(int64_t handle, const struct ironwood_array *name, const struct ironwood_array *address);
int64_t ironwood_tls_attach(int64_t handle, int32_t descriptor);
int64_t ironwood_tls_handshake(int64_t handle);
int64_t ironwood_tls_read_byte(int64_t handle);
int64_t ironwood_tls_read_bytes(int64_t handle, struct ironwood_array *buffer, int32_t offset, int32_t length);
int64_t ironwood_tls_write_byte(int64_t handle, int32_t value);
int64_t ironwood_tls_write_bytes(int64_t handle, const struct ironwood_array *buffer, int32_t offset, int32_t length);
int64_t ironwood_tls_available(int64_t handle);
int64_t ironwood_tls_wait(int64_t handle, bool writing, int64_t remaining);
int64_t ironwood_tls_close(int64_t handle, bool graceful);
#endif
