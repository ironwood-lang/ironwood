<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# TCP sockets: a quick start

[SimpleTcpEcho](../projects/SimpleTcpEcho/README.md) has two programs: a server
that waits for messages, and a client that sends one message, prints the reply,
and disconnects. The server keeps running until you press **Ctrl+C**.

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
Got: HiThere!
```

Run the client again whenever you like. To choose a different port and message,
start the server with `./run-server.sh 56000`, then use:

```sh
./run-client.sh 127.0.0.1 56000 "Hello from Ironwood!"
# Got: Hello from Ironwood!
```

Arguments are positional: the server accepts `[PORT]`; the client accepts
`[HOST [PORT [MESSAGE]]]`. Defaults are `localhost`, `55556`, and `HiThere!`.
Quote messages containing spaces. If the port is already in use, choose another
one for both programs.

## The server

[Server.iron](../projects/SimpleTcpEcho/src/main/ironwood/org/ironwood/simpletcpecho/Server.iron)
creates a `ServerSocket` and calls `accept()` in a loop. `accept()` waits until
a client connects and returns a separate `Socket` for that conversation.
The server reads the message, writes `Got: ` followed by the message, and closes
that client socket. The listening `ServerSocket` stays open for the next client.

Clients are handled one at a time. A five-second read timeout prevents a silent
client from holding up the server indefinitely between reads. A client I/O error
is reported on stderr, and the server continues accepting connections.

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
socket, ending the response. The client converts those bytes to a string, prints
it, closes its socket, and exits. It also uses a five-second read timeout.

## Cleanup and testing

The complete sources use `finally` blocks: `close()` releases the connection,
and `free` reclaims the socket object. They also free temporary byte arrays and
strings. Streams returned by a socket are borrowed views and are not freed
separately. Ctrl+C stops the server process; the operating system closes its
remaining sockets.

Run `./test.sh` from the project folder to build and test the programs locally
(Python 3 required). If port `55556` is unavailable, its check is reported as
skipped; the remaining checks use an automatically assigned port.
The checks cover repeated clients, custom messages and ports, UTF-8, partial
reads, connection errors, and stopping the server with Ctrl+C.
