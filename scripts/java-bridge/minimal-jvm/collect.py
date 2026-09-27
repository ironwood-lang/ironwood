# SPDX-License-Identifier: MIT OR Apache-2.0
"""Build-time inventory and copy of a pinned JVM's nongraphical external runtime closure."""

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

root = Path("/minimal")
jdk = Path("/opt/ironwood-bridge-jdk/jdk-21.0.12.1+1")
(root / "opt").mkdir(parents=True)
shutil.copytree(jdk, root / "opt/jdk", symlinks=True)
(root / "tmp").mkdir(mode=0o1777)
(root / "tmp").chmod(0o1777)
libraries = set()
scans = {}
for binary in [jdk / "bin/java", *(jdk / "lib" / name for name in
        ["libjli.so", "libjava.so", "libzip.so", "libnio.so", "libnet.so", "libjimage.so", "server/libjvm.so"])]:
    # ldd on a JDK component needs the JVM's internal directories. This variable
    # applies only to this build-time audit, never to the final image or consumer.
    environment = dict(os.environ, LD_LIBRARY_PATH=str(jdk / "lib/server") + ":" + str(jdk / "lib"))
    text = subprocess.check_output(["ldd", str(binary)], text=True, env=environment)
    scans[str(binary)] = text
    if "not found" in text:
        raise ValueError(text)
    for match in re.finditer(r"(/[^\s()]+)", text):
        path = Path(match[1])
        if not path.is_relative_to(jdk):
            libraries.add(path)
for path in libraries:
    if path.name.startswith(("libstdc++", "libgcc_s")):
        raise ValueError("JVM unexpectedly needs compiler runtime: " + str(path))
    output = root / str(path).lstrip("/")
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, output)
(root / "jvm-runtime-inventory.json").write_text(json.dumps({
    "dependency_scans": scans,
    "libraries": {str(path): hashlib.file_digest(path.open("rb"), "sha256").hexdigest() for path in sorted(libraries)}
}, indent=2) + "\n")
