/* SPDX-License-Identifier: MIT OR Apache-2.0 */
#include "../include/ironwood_tls.h"
#include <openssl/ssl.h>
#include <openssl/err.h>
#include <openssl/pem.h>
#include <openssl/x509v3.h>
#include <openssl/provider.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include "ironwood_ca_data.h"

/* Results are values, WANT_READ/WRITE, transport, verification, CA, configuration,
 * or allocation failures. No error-queue pointer or managed input is retained. */
enum { TLS_READ = -2, TLS_WRITE = -3, TLS_IO = -4, TLS_VERIFY = -5,
       TLS_CA = -6, TLS_CONFIG = -7, TLS_OOM = -8 };
struct tls_connection {
    OSSL_LIB_CTX *library;
    OSSL_PROVIDER *provider;
    SSL_CTX *context;
    SSL *ssl;
    BIO_METHOD *method;
    int descriptor;
};

static struct tls_connection *connection(int64_t handle) {
    return (struct tls_connection *)(uintptr_t)handle;
}

static void release(struct tls_connection *state) {
    if (!state) return;
    SSL_free(state->ssl);
    BIO_meth_free(state->method);
    SSL_CTX_free(state->context);
    OSSL_PROVIDER_unload(state->provider);
    OSSL_LIB_CTX_free(state->library);
    free(state);
    ERR_clear_error();
}

static int socket_read(BIO *bio, char *buffer, int length) {
    BIO_clear_retry_flags(bio);
    int fd = (int)(intptr_t)BIO_get_data(bio);
    int result = (int)recv(fd, buffer, (size_t)length, 0);
    if (result < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) BIO_set_retry_read(bio);
    return result;
}

static int socket_write(BIO *bio, const char *buffer, int length) {
    BIO_clear_retry_flags(bio);
    int fd = (int)(intptr_t)BIO_get_data(bio);
#ifdef MSG_NOSIGNAL
    int flags = MSG_NOSIGNAL;
#else
    int flags = 0; /* TCP creation already sets SO_NOSIGPIPE on macOS. */
#endif
    int result = (int)send(fd, buffer, (size_t)length, flags);
    if (result < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) BIO_set_retry_write(bio);
    return result;
}

static long socket_control(BIO *bio, int command, long argument, void *pointer) {
    (void)bio; (void)argument; (void)pointer;
    return command == BIO_CTRL_FLUSH ? 1 : 0;
}

/* Explicit certificate-only PEM input. Blank lines and # comments are allowed;
 * garbage, other PEM object kinds and incomplete final objects reject the bundle. */
static int load_anchors(SSL_CTX *context, BIO *input) {
    char line[512];
    BIO *certificate = NULL;
    int count = 0, size, valid = 1;
    while ((size = BIO_gets(input, line, sizeof(line))) > 0) {
        if (!certificate) {
            if (!strncmp(line, "-----BEGIN CERTIFICATE-----", 27)
                    && (line[27] == '\n' || line[27] == '\r' || line[27] == 0)) {
                certificate = BIO_new(BIO_s_mem());
                if (!certificate) { valid = 0; break; }
            } else {
                char *next = line;
                while (*next == ' ' || *next == '\t' || *next == '\r' || *next == '\n') next++;
                if (*next && *next != '#') { valid = 0; break; }
                continue;
            }
        }
        if (BIO_write(certificate, line, size) != size) { valid = 0; break; }
        if (!strncmp(line, "-----END CERTIFICATE-----", 25)
                && (line[25] == '\n' || line[25] == '\r' || line[25] == 0)) {
            X509 *cert = PEM_read_bio_X509(certificate, NULL, NULL, NULL);
            if (!cert || !X509_STORE_add_cert(SSL_CTX_get_cert_store(context), cert)) valid = 0;
            X509_free(cert);
            BIO_free(certificate);
            certificate = NULL;
            if (!valid) break;
            count++;
        }
    }
    if (certificate || size < 0 || !BIO_eof(input)) valid = 0;
    BIO_free(certificate);
    return valid && count > 0;
}

int64_t ironwood_tls_create(const struct ironwood_array *path) {
    if (!OPENSSL_init_ssl(OPENSSL_INIT_NO_LOAD_CONFIG, NULL)) return TLS_OOM;
    ERR_clear_error();
    struct tls_connection *state = calloc(1, sizeof(*state));
    if (!state) return TLS_OOM;
    state->descriptor = -1;
    state->library = OSSL_LIB_CTX_new();
    if (state->library) state->provider = OSSL_PROVIDER_load(state->library, "default");
    if (state->provider) state->context = SSL_CTX_new_ex(state->library, NULL, TLS_client_method());
    if (!state->context) { release(state); return TLS_OOM; }
    SSL_CTX_set_verify(state->context, SSL_VERIFY_PEER, NULL);
    SSL_CTX_set_session_cache_mode(state->context, SSL_SESS_CACHE_OFF);
    SSL_CTX_set_mode(state->context, SSL_MODE_ACCEPT_MOVING_WRITE_BUFFER);
    SSL_CTX_set_options(state->context, SSL_OP_NO_COMPRESSION | SSL_OP_NO_RENEGOTIATION);
    if (!SSL_CTX_set_min_proto_version(state->context, TLS1_2_VERSION)
            || !SSL_CTX_set_max_proto_version(state->context, TLS1_3_VERSION)
            || !SSL_CTX_set_max_early_data(state->context, 0)) {
        release(state); return TLS_CONFIG;
    }
    BIO *input = NULL;
    if (path) {
        if (!path->length || path->length > INT_MAX || memchr(path->data, 0, path->length)) {
            release(state); return TLS_CA;
        }
        char *name = malloc(path->length + 1);
        if (!name) { release(state); return TLS_OOM; }
        memcpy(name, path->data, path->length);
        name[path->length] = 0;
        input = BIO_new_file(name, "rb");
        free(name);
    } else {
        input = BIO_new_mem_buf(ironwood_ca_pem, sizeof(ironwood_ca_pem) - 1);
    }
    int loaded = input && load_anchors(state->context, input);
    BIO_free(input);
    if (!loaded) { release(state); return TLS_CA; }
    state->ssl = SSL_new(state->context);
    if (!state->ssl) { release(state); return TLS_OOM; }
    ERR_clear_error();
    SSL_set_connect_state(state->ssl);
    /* The void setter can fail its record-layer allocation. Check its error
     * before any later operation touches the incompletely reset connection. */
    if (ERR_peek_error() != 0) { release(state); return TLS_OOM; }
    return (int64_t)(uintptr_t)state;
}

int64_t ironwood_tls_configure(int64_t handle, const struct ironwood_array *name,
        const struct ironwood_array *address) {
    struct tls_connection *state = connection(handle);
    char identity[254];
    if (!name->length || name->length >= sizeof(identity) || memchr(name->data, 0, name->length)) return TLS_CONFIG;
    memcpy(identity, name->data, name->length);
    identity[name->length] = 0;
    X509_VERIFY_PARAM *parameters = SSL_get0_param(state->ssl);
    X509_VERIFY_PARAM_set_hostflags(parameters, X509_CHECK_FLAG_NO_PARTIAL_WILDCARDS);
    if (address) {
        if ((address->length != 4 && address->length != 16)
                || !X509_VERIFY_PARAM_set1_ip(parameters, address->data, address->length)) return TLS_CONFIG;
    } else {
        if (!SSL_set1_host(state->ssl, identity)
                || !SSL_set_tlsext_host_name(state->ssl, identity)) return TLS_CONFIG;
    }
    return 0;
}

int64_t ironwood_tls_attach(int64_t handle, int32_t descriptor) {
    struct tls_connection *state = connection(handle);
    int flags = fcntl(descriptor, F_GETFL, 0);
    if (flags < 0 || fcntl(descriptor, F_SETFL, flags | O_NONBLOCK) < 0) return TLS_IO;
    state->descriptor = descriptor;
    state->method = BIO_meth_new(BIO_TYPE_SOURCE_SINK, "Ironwood TCP");
    if (!state->method || !BIO_meth_set_read(state->method, socket_read)
            || !BIO_meth_set_write(state->method, socket_write)
            || !BIO_meth_set_ctrl(state->method, socket_control)) return TLS_OOM;
    BIO *transport = BIO_new(state->method);
    if (!transport) return TLS_OOM;
    BIO_set_data(transport, (void *)(intptr_t)descriptor);
    BIO_set_init(transport, 1);
    /* SSL owns the BIO. It never owns or closes the borrowed TCP descriptor. */
    SSL_set_bio(state->ssl, transport, transport);
    return 0;
}

static int64_t failure(struct tls_connection *state, int result) {
    int error = SSL_get_error(state->ssl, result);
    if (error == SSL_ERROR_WANT_READ) return TLS_READ;
    if (error == SSL_ERROR_WANT_WRITE) return TLS_WRITE;
    if (error == SSL_ERROR_ZERO_RETURN) return 0;
    if (SSL_get_verify_result(state->ssl) != X509_V_OK) return TLS_VERIFY;
    return TLS_IO; /* Includes unexpected EOF without close_notify. */
}

int64_t ironwood_tls_handshake(int64_t handle) {
    struct tls_connection *state = connection(handle);
    ERR_clear_error();
    int result = SSL_connect(state->ssl);
    if (result == 1) return 0;
    int64_t status = failure(state, result);
    return status == 0 ? TLS_IO : status;
}

int64_t ironwood_tls_read_bytes(int64_t handle, struct ironwood_array *buffer, int32_t offset, int32_t length) {
    if (!length) return 0;
    struct tls_connection *state = connection(handle);
    ERR_clear_error();
    size_t count = 0;
    int result = SSL_read_ex(state->ssl, buffer->data + offset, (size_t)length, &count);
    return result == 1 ? (int64_t)count : failure(state, result);
}

int64_t ironwood_tls_read_byte(int64_t handle) {
    struct tls_connection *state = connection(handle);
    unsigned char value;
    size_t count = 0;
    ERR_clear_error();
    int result = SSL_read_ex(state->ssl, &value, 1, &count);
    if (result == 1) return value;
    int64_t error = failure(state, result);
    return error == 0 ? -1 : error;
}

int64_t ironwood_tls_write_bytes(int64_t handle, const struct ironwood_array *buffer, int32_t offset, int32_t length) {
    if (!length) return 0;
    struct tls_connection *state = connection(handle);
    ERR_clear_error();
    size_t count = 0;
    int result = SSL_write_ex(state->ssl, buffer->data + offset, (size_t)length, &count);
    int64_t status = result == 1 ? (int64_t)count : failure(state, result);
    return status == 0 ? TLS_IO : status;
}

int64_t ironwood_tls_write_byte(int64_t handle, int32_t value) {
    struct tls_connection *state = connection(handle);
    unsigned char byte = (unsigned char)value;
    size_t count = 0;
    ERR_clear_error();
    int result = SSL_write_ex(state->ssl, &byte, 1, &count);
    int64_t status = result == 1 ? 1 : failure(state, result);
    return status == 0 ? TLS_IO : status;
}

int64_t ironwood_tls_available(int64_t handle) {
    return SSL_pending(connection(handle)->ssl);
}

int64_t ironwood_tls_wait(int64_t handle, bool writing, int64_t remaining) {
    int64_t result = ironwood_tcp_wait(connection(handle)->descriptor, writing, remaining);
    int error = (int)((uint64_t)result >> 32);
    if (error == EINTR) return writing ? TLS_WRITE : TLS_READ;
    return error ? TLS_IO : (int32_t)result;
}

int64_t ironwood_tls_close(int64_t handle, bool graceful) {
    struct tls_connection *state = connection(handle);
    int64_t result = 0;
    if (graceful && state->descriptor >= 0 && SSL_is_init_finished(state->ssl)) {
        ERR_clear_error();
        int closed = SSL_shutdown(state->ssl);
        if (closed < 0) {
            result = failure(state, closed);
            if (result == TLS_READ || result == TLS_WRITE) result = 0;
        }
    }
    release(state);
    return result;
}
