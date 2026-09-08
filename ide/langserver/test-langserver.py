#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0

"""Drives the Ironwood language server over stdio and checks what it reports.

The server is exercised through the protocol itself rather than through Eclipse,
so a diagnostics regression is caught without a workbench, a display, or a
human looking at squiggles.
"""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import queue

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ECLIPSE_HOME = Path("/Applications/Eclipse-2026-03.app/Contents/Eclipse")
DEFAULT_JAVA = Path("/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home/bin/java")

# A file with two errors the compiler can only find with real analysis: an
# undefined name, and a free the ownership proof must reject because a live
# alias can still reach the allocation. It also calls into a second file, so a
# clean result proves the whole source set was analyzed rather than this file
# alone.
BROKEN_SOURCE = """\
package demo;

public class Broken {

    public static int main(String[] args) {

        Broken owned = new Broken();
        Broken alias = owned;
        free owned;
        return missingName + Helper.value();
    }
}
"""

FIXED_SOURCE = """\
package demo;

public class Broken {

    public static int main(String[] args) {

        Broken owned = new Broken();
        free owned;
        return Helper.value();
    }
}
"""

# Never opened in the editor. Its type must still resolve for Broken, and it
# must never receive a diagnostics publication of its own.
HELPER_SOURCE = """\
package demo;

public class Helper {

    public static int value() {

        return 7;
    }
}
"""

# Exercises every member shape the outline knows how to render, including the
# destructor, which has no name of its own, and carries documentation comments
# so that hover has something to report.
OUTLINE_SOURCE = """\
package demo;

/**
 * Holds a buffer it reclaims itself.
 */
public class Outline {

    public static final int LIMIT = 4;

    private int[] buffer;

    public Outline(int size) {

        this.buffer = new int[size];
    }

    destructor {

        free this.buffer;
    }

    /** Reports how many slots the buffer has. */
    public int size(String label) {

        return this.buffer.length;
    }
}
"""


def position_of(text, needle, occurrence=1):
    """Finds the zero-based line and character of a needle in a source string."""
    index = -1
    for _ in range(occurrence):
        index = text.index(needle, index + 1)
    line = text.count("\n", 0, index)
    character = index - (text.rfind("\n", 0, index) + 1)
    return {"line": line, "character": character}


class ServerConnection:
    """Minimal JSON-RPC framing over the server's stdio."""

    def __init__(self, process):
        self.process = process
        self.next_id = 1
        self.notifications = queue.Queue()
        self.responses = {}
        self.responses_ready = threading.Condition()
        self.reader = threading.Thread(target=self._read_loop, daemon=True)
        self.reader.start()

    def _read_loop(self):
        stream = self.process.stdout
        while True:
            length = None
            while True:
                line = stream.readline()
                if not line:
                    return
                line = line.strip()
                if not line:
                    break
                if line.lower().startswith(b"content-length:"):
                    length = int(line.split(b":", 1)[1])
            if length is None:
                continue
            message = json.loads(stream.read(length))
            if "id" in message and "method" not in message:
                with self.responses_ready:
                    self.responses[message["id"]] = message
                    self.responses_ready.notify_all()
            elif "method" in message:
                self.notifications.put(message)

    def _send(self, payload):
        body = json.dumps(payload).encode("utf-8")
        header = f"Content-Length: {len(body)}\r\n\r\n".encode("ascii")
        self.process.stdin.write(header + body)
        self.process.stdin.flush()

    def request(self, method, params, timeout=60):
        request_id = self.next_id
        self.next_id += 1
        self._send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        with self.responses_ready:
            deadline_met = self.responses_ready.wait_for(
                lambda: request_id in self.responses, timeout=timeout)
            if not deadline_met:
                raise TimeoutError(f"no response to {method}")
            return self.responses.pop(request_id)

    def notify(self, method, params):
        self._send({"jsonrpc": "2.0", "method": method, "params": params})

    def await_diagnostics(self, uri, timeout=60):
        """Waits for the next diagnostics publication for one document."""
        while True:
            message = self.notifications.get(timeout=timeout)
            if message.get("method") != "textDocument/publishDiagnostics":
                continue
            if message["params"]["uri"] == uri:
                return message["params"]["diagnostics"]

    def collect_diagnostics(self, settle_seconds=3.0, timeout=60):
        """Collects every diagnostics publication until the server goes quiet.

        Used to assert which documents were published for, including the
        documents that must not be published for at all.
        """
        published = {}
        deadline_wait = timeout
        while True:
            try:
                message = self.notifications.get(timeout=deadline_wait)
            except queue.Empty:
                return published
            if message.get("method") == "textDocument/publishDiagnostics":
                params = message["params"]
                published[params["uri"]] = params["diagnostics"]
                # After the first publication, only wait out the settle window.
                deadline_wait = settle_seconds


def build_command(server_jar, compiler_jar, eclipse_home, java_binary):
    lsp4j_jars = sorted(
        str(path)
        for pattern in ("org.eclipse.lsp4j_*.jar",
                        "org.eclipse.lsp4j.jsonrpc_*.jar",
                        "com.google.gson_*.jar")
        for path in (eclipse_home / "plugins").glob(pattern))
    if not lsp4j_jars:
        raise SystemExit(f"no LSP4J jars under {eclipse_home / 'plugins'}")
    classpath = ":".join([str(server_jar), str(compiler_jar)] + lsp4j_jars)
    return [str(java_binary), "-cp", classpath, "ironwood.lsp.IronwoodLanguageServerMain"]


def check(condition, description, failures):
    if condition:
        print(f"  ok: {description}")
    else:
        print(f"  FAILED: {description}")
        failures.append(description)


def main():
    server_jar = REPO_ROOT / "ide/langserver/target/ironwood-langserver.jar"
    compiler_jar = REPO_ROOT / "compiler/build/ironwoodc.jar"
    for required in (server_jar, compiler_jar):
        if not required.exists():
            raise SystemExit(f"missing {required}; run ide/langserver/build.sh first")

    java_binary = DEFAULT_JAVA if DEFAULT_JAVA.exists() else Path("java")
    command = build_command(server_jar, compiler_jar, DEFAULT_ECLIPSE_HOME, java_binary)

    failures = []
    with tempfile.TemporaryDirectory() as workspace:
        source_path = Path(workspace) / "demo" / "Broken.iron"
        source_path.parent.mkdir(parents=True)
        source_path.write_text(BROKEN_SOURCE, encoding="utf-8")
        uri = source_path.as_uri()

        helper_path = source_path.parent / "Helper.iron"
        helper_path.write_text(HELPER_SOURCE, encoding="utf-8")
        helper_uri = helper_path.as_uri()

        process = subprocess.Popen(
            command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        connection = ServerConnection(process)
        try:
            initialize = connection.request("initialize", {
                "processId": None,
                "rootUri": Path(workspace).as_uri(),
                "capabilities": {},
            })
            server_info = initialize["result"].get("serverInfo", {})
            check(server_info.get("name") == "Ironwood Language Server",
                  "initialize reports the server name", failures)
            check(initialize["result"]["capabilities"].get("textDocumentSync") == 1,
                  "initialize advertises full document synchronization", failures)

            connection.notify("initialized", {})
            connection.notify("textDocument/didOpen", {
                "textDocument": {
                    "uri": uri, "languageId": "ironwood",
                    "version": 1, "text": BROKEN_SOURCE,
                },
            })

            published = connection.collect_diagnostics()
            check(set(published) == {uri},
                  "only the open document is published for, not every analyzed file "
                  f"(got {sorted(published)})", failures)
            check(helper_uri not in published,
                  "a file that was never opened receives no diagnostics", failures)

            diagnostics = published.get(uri, [])
            messages = [entry["message"] for entry in diagnostics]
            check(not any("Helper" in message for message in messages),
                  f"the type from the unopened file resolves (got {messages})", failures)
            check(len(diagnostics) == 2,
                  f"the broken file reports two diagnostics (got {len(diagnostics)}: {messages})",
                  failures)
            check(any("missingName" in message for message in messages),
                  "the undefined name is reported", failures)
            check(any("alias" in message and "free" in message for message in messages),
                  "the rejected free explains the surviving alias", failures)

            free_diagnostic = next(
                (entry for entry in diagnostics if "alias" in entry["message"]), None)
            if free_diagnostic is None:
                check(False, "the rejected free carries a range", failures)
            else:
                start = free_diagnostic["range"]["start"]
                # 'free owned;' is line 9 of the fixture, which is line index 8.
                check(start["line"] == 8,
                      f"the rejected free is anchored on its own line (got {start['line']})",
                      failures)
                check(free_diagnostic.get("source") == "ironwood",
                      "diagnostics are labelled as coming from ironwood", failures)

            # Editing the buffer without saving must refresh diagnostics, which
            # is the whole point of analyzing the editor's copy rather than disk.
            connection.notify("textDocument/didChange", {
                "textDocument": {"uri": uri, "version": 2},
                "contentChanges": [{"text": FIXED_SOURCE}],
            })
            cleared = connection.await_diagnostics(uri)
            check(cleared == [],
                  f"fixing the buffer clears diagnostics without saving (got {cleared})",
                  failures)
            check(source_path.read_text(encoding="utf-8") == BROKEN_SOURCE,
                  "the file on disk was never written", failures)

            # The outline is built from the syntax tree, so it is requested on a
            # separate file to keep it independent of the diagnostics checks.
            outline_path = source_path.parent / "Outline.iron"
            outline_path.write_text(OUTLINE_SOURCE, encoding="utf-8")
            outline_uri = outline_path.as_uri()
            connection.notify("textDocument/didOpen", {
                "textDocument": {
                    "uri": outline_uri, "languageId": "ironwood",
                    "version": 1, "text": OUTLINE_SOURCE,
                },
            })
            symbols = connection.request("textDocument/documentSymbol", {
                "textDocument": {"uri": outline_uri},
            })["result"]

            check(len(symbols) == 1 and symbols[0]["name"] == "Outline",
                  f"the outline has one top-level type (got {[s['name'] for s in symbols]})",
                  failures)
            members = {child["name"]: child for child in symbols[0].get("children", [])}
            # SymbolKind values: 5 Class, 6 Method, 8 Field, 9 Constructor, 14 Constant.
            expected_members = {
                "LIMIT": 14,
                "buffer": 8,
                "Outline": 9,
                "destructor": 6,
                "size": 6,
            }
            for name, kind in expected_members.items():
                entry = members.get(name)
                if entry is None:
                    check(False, f"the outline lists '{name}'", failures)
                    continue
                check(entry["kind"] == kind,
                      f"'{name}' has the right symbol kind (got {entry['kind']}, want {kind})",
                      failures)

            size_entry = members.get("size")
            if size_entry is not None:
                check(size_entry.get("detail") == "(String) : int",
                      f"a method shows its signature (got {size_entry.get('detail')!r})",
                      failures)
            buffer_entry = members.get("buffer")
            if buffer_entry is not None:
                check(buffer_entry.get("detail") == "int[]",
                      f"an array field shows its type (got {buffer_entry.get('detail')!r})",
                      failures)

            # Hover on a declaration's own name reports its signature and the
            # IronDocs comment written above it.
            method_hover = connection.request("textDocument/hover", {
                "textDocument": {"uri": outline_uri},
                "position": position_of(OUTLINE_SOURCE, "size(String label)"),
            })["result"]
            hover_text = (method_hover or {}).get("contents", {}).get("value", "")
            check("public int size(String label)" in hover_text,
                  f"hover on a method shows its signature (got {hover_text!r})", failures)
            check("Reports how many slots" in hover_text,
                  "hover on a method shows its IronDocs comment", failures)
            check("Declared in `Outline`" in hover_text,
                  "hover on a member names its enclosing type", failures)

            type_hover = connection.request("textDocument/hover", {
                "textDocument": {"uri": outline_uri},
                "position": position_of(OUTLINE_SOURCE, "Outline {"),
            })["result"]
            type_text = (type_hover or {}).get("contents", {}).get("value", "")
            check("public class Outline" in type_text,
                  f"hover on a type shows its declaration (got {type_text!r})", failures)
            check("Holds a buffer it reclaims itself." in type_text,
                  "hover on a type shows its IronDocs comment", failures)

            # Go to definition crosses files: Helper is declared in a file the
            # editor never opened.
            definitions = connection.request("textDocument/definition", {
                "textDocument": {"uri": uri},
                "position": position_of(FIXED_SOURCE, "Helper.value()"),
            })["result"]
            check(len(definitions) == 1 and definitions[0]["uri"] == helper_uri,
                  f"go to definition crosses into the declaring file (got {definitions})",
                  failures)
            if definitions:
                target = definitions[0]["range"]["start"]
                helper_name = position_of(HELPER_SOURCE, "Helper {")
                check(target["line"] == helper_name["line"],
                      f"the definition lands on the type name (got line {target['line']}, "
                      f"want {helper_name['line']})", failures)

            connection.request("shutdown", {})
            connection.notify("exit", {})
            process.wait(timeout=30)
            check(process.returncode == 0,
                  f"the server exits cleanly (got {process.returncode})", failures)
        finally:
            if process.poll() is None:
                process.kill()
            stderr = process.stderr.read().decode("utf-8", "replace").strip()
            if stderr:
                print("  server stderr:")
                for line in stderr.splitlines():
                    print(f"    {line}")

    if failures:
        print(f"\nlanguage server check failed with {len(failures)} problem(s)")
        return 1
    print("\nlanguage server check passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
