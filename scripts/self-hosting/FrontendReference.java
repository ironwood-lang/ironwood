// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.lexer.LexResult;
import ironwood.compiler.lexer.Lexer;
import ironwood.compiler.parser.ParseResult;
import ironwood.compiler.parser.Parser;
import ironwood.compiler.source.SourceFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Test-only Java frontend reference for M2.1, the J0 counterpart of the native
 * FrontendCapture adapter. It reads the same manifest (INPUT, LOGICAL_NAME and
 * the input's SHA-256 per tab-separated line), verifies each hash against the
 * decoded text's UTF-8 bytes, lexes and parses every unit in one phase while
 * retaining all results, and then writes ReferenceCapture's tokens,
 * lex-diagnostics, ast and parse-diagnostics files to OUTPUT/INDEX. A unit
 * whose lexer or parser throws records the exception's simple class name in
 * failure.txt and omits the outputs it did not produce.
 */
public final class FrontendReference {
    private FrontendReference() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("MANIFEST OUTPUT_DIRECTORY");
        List<String[]> entries = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            if (!line.isEmpty()) entries.add(line.split("\t", -1));
        }
        List<SourceFile> sources = new ArrayList<>();
        for (String[] entry : entries) {
            if (entry.length != 3) throw new IllegalArgumentException("bad manifest line");
            String content = Files.readString(Path.of(entry[0]), StandardCharsets.UTF_8);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
            if (!hash.equals(entry[2])) throw new IllegalStateException("manifest hash mismatch: " + entry[0]);
            sources.add(SourceFile.of(entry[1], content));
        }
        List<LexResult> lexed = new ArrayList<>();
        List<ParseResult> parsed = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        long started = System.nanoTime();
        for (SourceFile source : sources) {
            LexResult lexResult = null;
            ParseResult parseResult = null;
            String failure = null;
            // A parser exception is an observable outcome; the unit records its class.
            try {
                lexResult = new Lexer(source).lex();
                parseResult = new Parser(source, lexResult.tokens()).parse();
            } catch (RuntimeException exception) {
                failure = exception.getClass().getSimpleName();
            }
            lexed.add(lexResult);
            parsed.add(parseResult);
            failures.add(failure);
        }
        long phase = System.nanoTime() - started;
        for (int index = 0; index < sources.size(); index++) {
            Path output = Path.of(args[1], Integer.toString(index));
            Files.createDirectories(output);
            if (lexed.get(index) != null) {
                ReferenceCapture.save(output, "tokens", lexed.get(index).tokens());
                ReferenceCapture.save(output, "lex-diagnostics", lexed.get(index).diagnostics());
            }
            if (parsed.get(index) != null) {
                ReferenceCapture.save(output, "ast", parsed.get(index).unit());
                ReferenceCapture.save(output, "parse-diagnostics", parsed.get(index).diagnostics());
            }
            if (failures.get(index) != null) {
                Files.writeString(output.resolve("failure.txt"), failures.get(index) + "\n", StandardCharsets.UTF_8);
            }
        }
        System.out.println("units " + sources.size() + " phase_nanos " + phase);
    }
}
