<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# TCP sockets: a quick start

[SimpleTcpEcho](../projects/SimpleTcpEcho/README.md) has two programs: a server
that waits for messages, and a client that sends one message, prints what it sent
and received, and disconnects. The server keeps running until you press **Ctrl+C**.

## Build and run

With `ironwoodc` on your PATH, start from the repository root:

```sh
cd projects/SimpleTcpEcho
./compile.sh
./link.sh
./run-server.sh
```

The server reports `Listening on port 55556`. Leave this terminal open. In a
second terminal, enter the same project folder and run:

```sh
./run-client.sh
```

It connects to `localhost:55556`, sends `HiThere!`, and prints:

```text
SENT: HiThere!
GOT: =[HiThere!]=
```

The server prints the received message and its reply:

```text
GOT: HiThere!
REPLIED: =[HiThere!]=
```

Run the client again whenever you like. To choose a different port and message,
start the server with `./run-server.sh 56000`, then use:

```sh
./run-client.sh 127.0.0.1 56000 "Hello from Ironwood!"
# SENT: Hello from Ironwood!
# GOT: =[Hello from Ironwood!]=
```

Arguments are positional: the server accepts `[PORT]`; the client accepts
`[HOST [PORT [MESSAGE]]]`. Defaults are `localhost`, `55556`, and `HiThere!`.
Quote messages containing spaces. If the port is already in use, choose another
one for both programs.

## The server

[Server.iron](../projects/SimpleTcpEcho/src/main/ironwood/org/ironwood/simpletcpecho/Server.iron)
creates a `ServerSocket` and calls `accept()` in a loop. `accept()` waits until
a client connects and returns a separate `Socket` for that conversation.
The server reads the message, replies with `=[MESSAGE]=`, and closes that client
socket. It prints `GOT: MESSAGE` and `REPLIED: =[MESSAGE]=` to stdout.
The listening `ServerSocket` stays open for the next client.

Clients are handled one at a time. The server announces its listening port on
stdout. Reads wait for request EOF without a timeout, so an unfinished request
holds up subsequent clients. A client I/O error prints a stack trace on stderr,
and the server continues accepting connections.

The server allocates one byte array before accepting clients, with space for a
1 KiB request (1,024 bytes) and the four reply delimiter bytes. Its `reply`
method borrows this array and the prepared socket streams, reads directly into
the array, and writes only the occupied range. Successful replies allocate and
free nothing, including when a shorter request follows a longer one. Requests
larger than 1 KiB are rejected without a partial reply; the server reports the
error and accepts the next client. Exceptions on failure paths may allocate.

## The client

[Client.iron](../projects/SimpleTcpEcho/src/main/ironwood/org/ironwood/simpletcpecho/Client.iron)
connects with `new Socket(host, port)`. Its core exchange is:

```java
output.write(message);
socket.shutdownOutput();
byte[] response = EchoProtocol.readMessage(input);
```

Here `message` contains the command-line string's UTF-8 bytes. TCP carries a
stream of bytes, so one write does not necessarily match one read.
[EchoProtocol.iron](../projects/SimpleTcpEcho/src/main/ironwood/org/ironwood/simpletcpecho/EchoProtocol.iron)
collects chunks until it reaches the end of the stream.

`shutdownOutput()` tells the server the request is finished while keeping the
client's input open for the reply. The server then replies and closes its client
socket, ending the response. The client prints `SENT: MESSAGE` after sending,
then converts the reply bytes to a string and prints `GOT: =[MESSAGE]=`.
It closes its socket and exits. The client uses a five-second read timeout.

## Cleanup and testing

The complete sources use `finally` blocks: `close()` releases the connection,
and `free` reclaims the socket object. The server retains its byte array across
requests and frees it only when leaving the server loop; the client frees its
temporary byte arrays and strings. Streams returned by a socket are borrowed
views and are not freed separately. Ctrl+C stops the server process; the
operating system closes its remaining sockets.

Run `./test.sh` from the project folder to build and test the programs locally
(Python 3 required). If port `55556` is unavailable, its check is reported as
skipped; the remaining checks use an automatically assigned port.
The harness captures server stdout through a pseudo-terminal to preserve
interactive line buffering and watches both stdout and stderr for readiness.
The checks cover both programs' exact output, repeated clients, custom messages
and ports, UTF-8, partial reads, connection errors, and stopping the server
with Ctrl+C. They also verify the 1 KiB limit, buffer reuse after smaller or
rejected requests, and unchanged allocation and live-object counts around
successful `reply` calls with real TCP streams.
