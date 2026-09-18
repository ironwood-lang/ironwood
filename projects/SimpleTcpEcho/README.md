<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# SimpleTcpEcho

A TCP server that keeps running until Ctrl+C and replies with `=[MESSAGE]=`.
The client sends one message, prints what it sent and received, and disconnects.
Read the
[TCP quick-start guide](../../docs/SIMPLE_TCP_ECHO.md) for the code walkthrough.

With `ironwoodc` on your PATH:

```sh
./compile.sh
./link.sh
./run-server.sh
```

In another terminal, from this folder:

```sh
./run-client.sh
# SENT: HiThere!
# GOT: =[HiThere!]=
./run-client.sh localhost 55556 "Hello from Ironwood!"
# SENT: Hello from Ironwood!
# GOT: =[Hello from Ironwood!]=
```

For the default message, the server prints:

```text
GOT: HiThere!
REPLIED: =[HiThere!]=
```

- Server: `./run-server.sh [PORT]`, default `55556`. Port `0` selects a free port
  and prints it. The listener binds the local wildcard address.
- Client: `./run-client.sh [HOST [PORT [MESSAGE]]]`, defaults `localhost`,
  `55556`, and `HiThere!`. Quote a message containing spaces; an empty message
  is allowed. HOST accepts an IP address or hostname.
- Test: `./test.sh` builds both programs and runs local checks using Python 3.
  The default-port check is skipped if `55556` is unavailable; the remaining
  checks use a port assigned by the operating system.

Scripts resolve their paths independently of the current directory. Both programs
print their message exchange to stdout. Status and diagnostics use stderr.
Exit codes are `0` for client success, `64` for invalid arguments, and `74` for
connection/I/O failure.
The server serves clients sequentially and continues after a client's I/O error.
