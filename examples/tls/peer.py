# SPDX-License-Identifier: MIT OR Apache-2.0
"""Generate disposable local certificates and run the TLS example. No public hosts."""
import os
from pathlib import Path
import socket
import ssl
import subprocess
import threading

ROOT = Path(__file__).resolve().parent
CERTS = ROOT / "target/certificates"
CERTS.mkdir(parents=True, exist_ok=True)

def openssl(*args):
    environment = {key: value for key, value in os.environ.items() if key not in ("OPENSSL_CONF", "OPENSSL_MODULES")}
    result = subprocess.run(["openssl", *map(str, args)], capture_output=True, text=True, timeout=20, env=environment)
    if result.returncode: raise RuntimeError(result.stderr)

openssl("req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
        "-keyout", CERTS / "root.key", "-out", CERTS / "root.pem", "-days", "2", "-subj", "/CN=Ironwood Local Test Root",
        "-addext", "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=critical,keyCertSign")
openssl("req", "-new", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
        "-keyout", CERTS / "leaf.key", "-out", CERTS / "leaf.csr", "-subj", "/CN=localhost")
(CERTS / "leaf.ext").write_text("basicConstraints=critical,CA:FALSE\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost\n")
openssl("x509", "-req", "-in", CERTS / "leaf.csr", "-CA", CERTS / "root.pem", "-CAkey", CERTS / "root.key",
        "-CAcreateserial", "-out", CERTS / "leaf.pem", "-days", "2", "-extfile", CERTS / "leaf.ext")
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.minimum_version = ssl.TLSVersion.TLSv1_2
context.load_cert_chain(CERTS / "leaf.pem", CERTS / "leaf.key")
errors = []
with socket.socket() as listener:
    listener.bind(("127.0.0.1", 0)); listener.listen(); listener.settimeout(10)
    def serve():
        try:
            peer, _ = listener.accept()
            peer.settimeout(5)
            with context.wrap_socket(peer, server_side=True) as stream:
                total = 0
                while total < 258:
                    data = stream.recv(258 - total)
                    assert data
                    total += len(data); stream.sendall(data)
                assert stream.recv(1) == b""
        except Exception as error: errors.append(repr(error))
    worker = threading.Thread(target=serve); worker.start()
    result = subprocess.run([str(ROOT / "target/TlsEcho"), str(CERTS / "root.pem"),
                             str(listener.getsockname()[1]), "localhost"], timeout=10)
    worker.join(11)
    assert not worker.is_alive() and not errors, errors
    assert result.returncode == 42, result.returncode
    print("Verified local TLS echo; exit status: 42")
