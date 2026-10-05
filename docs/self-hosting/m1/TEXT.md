<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M1 text-block normalization

Base: 8873984d (D255). The lexer normalizes each text block's line terminators
and then calls `String.stripIndent()` before decoding cooked escapes
(Lexer.java:519-521, handoff API0166). Ironwood's `String` has no such method,
and FRONTEND_CONTRACTS forbids substituting `trim`, stripping all leading
space or adding a public text facade. D256 adds compiler-private
`ironwood.compiler.port.TextBlocks.stripIndent`, written from the documented
contract rather than translated from OpenJDK. The ledger is
[text-evidence/manifest.json](text-evidence/manifest.json).

## Contract

Lines end at `\n`, `\r` or `\r\n`, and a terminator at the very end does not
open another line. When the text does not end with a terminator, the removed
indentation is the smallest count of leading `Character.isWhitespace`
characters over nonblank lines and over the last line if it is blank. When it
does end with a terminator, nothing is outdented and the result ends with one
`\n`. Every line loses up to that indentation and all trailing whitespace; a
blank line becomes empty; lines are joined with `\n`. A no-break space is not
whitespace, matching Java 21.

The input is borrowed. The result is a fresh String the caller owns, and the
internal builder is freed before return, so each call leaves exactly one live
allocation.

## Evidence

| Check | Result |
| --- | --- |
| Differential | `compiler_text_blocks` prints index, length and FNV-1a hash for 12 handpicked and 20,000 generated inputs; the Java 21 [reference](text-evidence/TextBlocksReference.java) prints the identical 20,012 lines in four fresh JVMs (SHA-256 `ba7d3152...9e89`); the registered test regenerates the Java transcript in process |
| Artifacts | Compile and `-O3` link from classes and archive with zero `--unfreed=warn` diagnostics; exit 42 |
| Allocation | Every call leaves exactly its result live; freeing it restores the baseline |
| Ownership | Off/warn/error: freeing the input after the call and the result after use is accepted; using a freed result is rejected |
| IronDocs | One type generated without diagnostics |
