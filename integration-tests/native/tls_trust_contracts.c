/* SPDX-License-Identifier: MIT OR Apache-2.0 */
/* The real adapter is compiled with a test-only default root A. Root B is
 * exposed through the ordinary OpenSSL default environment and explicit input. */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../../runtime/src/ironwood_tls.c"

static int trusted(SSL_CTX *context, const char *path) {
    BIO *file = BIO_new_file(path, "rb");
    assert(file);
    X509 *leaf = PEM_read_bio_X509(file, NULL, NULL, NULL);
    BIO_free(file);
    assert(leaf);
    X509_STORE_CTX *verify = X509_STORE_CTX_new();
    assert(verify && X509_STORE_CTX_init(verify, SSL_CTX_get_cert_store(context), leaf, NULL));
    assert(X509_STORE_CTX_set_purpose(verify, X509_PURPOSE_SSL_SERVER));
    int result = X509_verify_cert(verify);
    X509_STORE_CTX_free(verify);
    X509_free(leaf);
    ERR_clear_error();
    return result == 1;
}
int main(int argc, char **argv) {
    assert(argc == 4);
    int64_t first = ironwood_tls_create(NULL);
    assert(first > 0);
    assert(trusted(connection(first)->context, argv[2]));
    assert(!trusted(connection(first)->context, argv[3]));
    assert(ironwood_tls_close(first, false) == 0);
    size_t length = strlen(argv[1]);
    struct ironwood_array *path = malloc(sizeof(*path) + length);
    assert(path); path->length = length; memcpy(path->data, argv[1], length);
    int64_t second = ironwood_tls_create(path);
    free(path);
    assert(second > 0);
    assert(!trusted(connection(second)->context, argv[2]));
    assert(trusted(connection(second)->context, argv[3]));
    assert(ironwood_tls_close(second, false) == 0);
    /* Prove the isolated environment really would add B through a default loader. */
    SSL_CTX *ambient = SSL_CTX_new(TLS_client_method());
    assert(ambient && SSL_CTX_set_default_verify_paths(ambient));
    assert(trusted(ambient, argv[3]));
    SSL_CTX_free(ambient);
    OPENSSL_cleanup();
    puts("TLS_TRUST default=A custom=B replacement=verified ambient=B ignored=verified");
    return 0;
}
