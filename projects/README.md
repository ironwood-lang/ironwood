# Ironwood projects

Projects are complete command-line applications built with the same explicit
workflow as the smaller examples:

```text
project/
  compile.sh
  link.sh
  run.sh
  src/main/ironwood/org/ironwood/<project>/*.iron
  target/
```

`compile.sh` creates loose `.ironclass` files, `link.sh` creates the native
executable, and `run.sh` exercises the application. Compile/link scripts print
their commands. Streaming run scripts preserve stdout bytes for pipelines and
resolve file names from the caller's directory.

| Project | Purpose | Successful status |
| --- | --- | ---: |
| [`HelloEclipse`](HelloEclipse/README.md) | Hello World with explicit reclamation and a native `@Test` suite, used to exercise the Eclipse plugin | 0 |
| [`OrderBook`](OrderBook/README.md) | Minimal paired Ironwood and Java order-matching benchmark | 0 |
| [`streaming`](streaming/README.md) | Binary cat/cp, incremental byte/UTF-16/word/line wc, and interactive prompt | 0 |
| [`minitee`](minitee/README.md) | Copy stdin to stdout and one file, with append mode and a borrowed-output decorator | 0 |
| `minigrep` | Literal line search over a UTF-8 file, including `IGNORE_CASE` | 0 when a match is found |

`OrderBook` is the focused performance comparison. Its five Ironwood files and
five Java files implement the same fixed-capacity engine and deterministic
eight-operation benchmark. It keeps matching, price-time priority, pooled
reuse, reduction, and cancellation while omitting production listener and
reporting features. See [the OrderBook guide](OrderBook/README.md) for the
paired build, run, and benchmark commands.

`minigrep` follows the feature scope of the Rust Book Chapter 12 teaching
project while using Java-shaped Ironwood APIs:

```console
$ cd projects/minigrep
$ ./compile.sh
$ ./link.sh
$ ./run.sh body data/sample.txt
```

It returns `0` when at least one line matches, `1` when no line matches, `64`
for invalid arguments, and `74` for an I/O failure. Set `IGNORE_CASE` to any
value to enable ASCII case-insensitive matching. Literal Unicode matching is
UTF-16 exact and preserves supplementary characters.

`minitee` is the standalone U3 project. Build it with its `compile.sh` and
`link.sh`, then use it in a pipeline from the repository root:

```sh
printf 'hello from Ironwood\n' | projects/minitee/run.sh capture.txt
printf 'another line\n' | projects/minitee/run.sh -a capture.txt
```

It preserves arbitrary bytes and uses one reusable copy buffer. Its custom
`TeeOutputStream` demonstrates abstract stream calls, retained borrows, and
separate close and reclamation. See [the minitee guide](minitee/README.md) for
argument, error, and ownership behavior.
