# Streaming command-line tools

Build with `./compile.sh` and `./link.sh`, with the repository `bin/` on PATH.
The resulting native executable needs neither Java nor LLVM at runtime.

```sh
./run.sh cat input.bin > output.bin
printf 'hello 😀\n' | ./run.sh wc
./run.sh cp input.bin copy.bin
./run.sh prompt
```

`cat [file]` copies bytes exactly; omitted file reads stdin. `wc [file]` prints
bytes, UTF-16 code units, ASCII-whitespace-delimited words, and LF count in that order.
UTF-8 malformed sequences use replacement decoding. CR alone separates words
but does not count as an LF line. An unterminated final line contributes no LF.
`cp` creates/truncates its destination and rejects same-file aliases, including
hard links and symlinks. The same-file check assumes paths are not concurrently
replaced during the operation. These tools do not implement GNU option parsing,
metadata preservation, or recursive copy. Filenames are literal arguments.

`prompt` flushes its prompt before reading, accepts CR/LF/CRLF, and reports one
line's UTF-16 length. EOF is handled explicitly. Exit codes are 0 for success,
64 for usage, and 74 for I/O failure. Error messages go to stderr. `run.sh`
preserves the caller's working directory and emits no binary-corrupting banner.

Byte and character copy buffers are retained for the operation and reclaimed
in `finally`. Resource close and object free are separate, with cleanup ordered
outside-in for borrowing wrappers. Standard streams remain process-owned.
