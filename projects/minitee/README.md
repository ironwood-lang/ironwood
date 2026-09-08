# minitee

`minitee` copies stdin bytes to both stdout and one file. It is a small U3
application built around abstract streams, a custom output decorator, and
explicit resource cleanup.

Build from the repository root with its `bin/` directory on PATH:

```sh
export PATH="$PWD/bin:$PATH"
projects/minitee/compile.sh
projects/minitee/link.sh
printf 'hello from Ironwood\n' | projects/minitee/run.sh capture.txt
printf 'another line\n' | projects/minitee/run.sh -a capture.txt
```

Each invocation prints the supplied line and writes it to `capture.txt`; the
second invocation appends. The native executable is `target/minitee` inside
this project and needs neither Java nor LLVM at runtime. `run.sh` preserves
the caller's working directory and emits no banner, so it also works in binary
pipelines:

```sh
projects/minitee/run.sh capture.bin < input.bin > forwarded.bin
cmp input.bin capture.bin
cmp input.bin forwarded.bin
```

## Command behavior

```text
minitee [-a] [--] file
```

Exactly one output file is required. The default creates or truncates it;
`-a` appends and creates it if necessary. Use `--` before a filename beginning
with `-`, for example `minitee -- -a`. Copying stops at stdin EOF. NUL bytes,
invalid UTF-8, and all other byte values pass through unchanged.

Each copied chunk is flushed so downstream commands can receive data before
stdin closes. Flush does not request durable storage with `fsync`. File writes
are not transactional: truncation and any bytes already written remain after
a later error. When redirecting stdin from a file, use a different output file;
the application does not detect aliases of stdin's descriptor.

| Status | Meaning |
| --- | --- |
| 0 | Copy and cleanup succeeded |
| 64 | Invalid arguments |
| 74 | An I/O operation failed, including a recorded stdout error |

Diagnostics go to stderr. A thrown I/O failure stops copying after the current
operation has attempted both outputs. `System.out` is a `PrintStream`, which
records ordinary I/O errors instead of throwing them: in that case, copying to
the file continues through EOF and `checkError()` produces status 74 afterward.

## What the code demonstrates

- [`StreamCopy.iron`](src/main/ironwood/org/ironwood/minitee/StreamCopy.iron)
  works with `InputStream` and `OutputStream`, reuses one 8 KiB byte array for
  the entire copy, handles short reads, and frees the array in `finally`.
- [`TeeOutputStream.iron`](src/main/ironwood/org/ironwood/minitee/TeeOutputStream.iron)
  borrows two abstract outputs in private fields. Writes, flushes, and closes
  attempt both outputs even if the first fails. Ironwood preserves the first
  exception and attaches a later failure as secondary. Close is idempotent.
- [`Minitee.iron`](src/main/ironwood/org/ironwood/minitee/Minitee.iron)
  handles arguments and owns the file stream and tee. Nested `finally` blocks
  close resources and then free the tee before the borrowed file object. An
  outer file-close guard also covers failure to allocate the tee.

Closing and freeing are separate operations. The tee never frees its borrowed
outputs, and closing it does not release the compiler-tracked borrow. Standard
streams remain process-owned; closing `System.out` only flushes it. The compiler
proves that the reachable output implementations do not retain the copy buffer
before accepting its `free`.

## Verification

From the repository root, run `./scripts/test.sh` and
`./scripts/check-licenses.sh`. The minitee integration tests cover byte-exact
copying, append and empty input, option parsing, live pipe delivery, loose-class
and archive linking, and O0–O3 native execution. Generated large inputs verify
one copy-buffer allocation; injected failures verify exception ordering,
allocation-failure cleanup, and repeated use under a low descriptor limit.
A negative compile test rejects freeing an output while the tee still borrows it.
