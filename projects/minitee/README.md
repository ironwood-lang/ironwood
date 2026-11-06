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

The tee closes its explicitly configured outputs according to its resource
contract. The tee, outputs and copy buffers remain ordinary shared managed
objects. Dropping an anchor or collecting does not close an output. Failure
cleanup retains the existing primary/secondary ordering.

Source close and managed collection have separate roles. Shared output aliases
are legal; callers must keep their explicit resource lifecycle valid while any
operation can use them. GC7 accepts these managed graphs and retains native
resource/confinement rejection controls independently.

## Verification

From the repository root, run these focused checks:

```sh
./scripts/test.sh \
  --test 'minitee copies binary pipelines across class and archive links' \
  --test 'minitee bounds allocations and preserves cleanup failures'
./scripts/check-licenses.sh
```

Focused tests preserve tee output, resource cleanup and failure order. Shared
managed outputs are acceptance cases; removed reclamation spellings and genuine
native/resource misuse retain separate rejection coverage.