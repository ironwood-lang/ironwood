// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.backend.LlvmEmitter;
import ironwood.compiler.lexer.Lexer;
import ironwood.compiler.parser.Parser;
import ironwood.compiler.source.SourceFile;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Test-only process adapter: semantic records become explicit structural wire data. */
public final class ReferenceCapture {
    private ReferenceCapture() {}

    private static String quote(String text) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c >= 32 && c <= 126) result.append(c);
            else result.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
        }
        return result.append('"').toString();
    }

    private static String wire(Object value) throws Exception {
        if (value == null) return "null";
        if (value instanceof String s) return quote(s);
        if (value instanceof Boolean b) return b ? "true" : "false";
        if (value instanceof Character c) return "{\"char_utf16\":" + (int)c + "}";
        if (value instanceof Number n) {
            String payload = n instanceof Double d ? Long.toHexString(Double.doubleToRawLongBits(d)) :
                    n instanceof Float f ? Integer.toHexString(Float.floatToRawIntBits(f)) : n.toString();
            return "{\"number_kind\":" + quote(n.getClass().getSimpleName()) + ",\"payload\":" + quote(payload) + "}";
        }
        if (value instanceof Enum<?> e) return "{\"enum\":" + quote(e.getDeclaringClass().getSimpleName()) + ",\"name\":" + quote(e.name()) + "}";
        if (value instanceof SourceFile source) return "{\"source\":" + quote(source.path().getFileName().toString()) +
                ",\"sha256_utf8\":" + quote(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(source.content().getBytes(StandardCharsets.UTF_8)))) + "}";
        if (value instanceof Optional<?> o) return "{\"optional\":" + (o.isPresent() ? wire(o.orElseThrow()) : "null") + "}";
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            for (var entry : map.entrySet()) entries.add("[" + wire(entry.getKey()) + "," + wire(entry.getValue()) + "]");
            return "{\"map_entries\":[" + String.join(",", entries) + "]}";
        }
        if (value instanceof Iterable<?> items) {
            List<String> elements = new ArrayList<>();
            for (Object item : items) elements.add(wire(item));
            return "[" + String.join(",", elements) + "]";
        }
        if (value.getClass().isArray()) {
            List<String> elements = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) elements.add(wire(Array.get(value, i)));
            return "[" + String.join(",", elements) + "]";
        }
        if (value.getClass().isRecord()) {
            List<String> fields = new ArrayList<>();
            fields.add("\"node\":" + quote(value.getClass().getSimpleName()));
            for (var component : value.getClass().getRecordComponents()) {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                fields.add(quote(component.getName()) + ":" + wire(accessor.invoke(value)));
            }
            return "{" + String.join(",", fields) + "}";
        }
        throw new IllegalArgumentException("unhandled wire type " + value.getClass().getName());
    }

    static void save(Path output, String name, Object value) throws Exception {
        Files.writeString(output.resolve(name + ".json"), wire(value) + "\n", StandardCharsets.UTF_8);
    }

    static void verifyLibrary(Path output) throws Exception {
        String home = System.getenv("IRONWOOD_STDLIB_HOME");
        if (home == null) throw new IllegalStateException("qualification requires an explicit frozen library home");
        Path root = Path.of(home).toAbsolutePath().normalize();
        Set<String> expected = new HashSet<>();
        try (var files = Files.walk(root.resolve("stdlib/src/main/ironwood"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".iron")).toList())
                expected.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
        }
        StandardLibrary library = StandardLibrary.discover();
        Map<String, String> consumed = new TreeMap<>();
        String archivePrefix = root.resolve("lib/ironwood-stdlib.ironjar") + "!/";
        for (String type : library.ownedTypes()) {
            SourceFile source = library.locate(type).orElseThrow();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.content().getBytes(StandardCharsets.UTF_8)));
            boolean frozenDocumentation = type.endsWith(".package-info") &&
                    source.path().toAbsolutePath().normalize().startsWith(root.resolve("stdlib/src/main/ironwood"));
            if ((!source.path().toString().startsWith(archivePrefix) && !frozenDocumentation) || !expected.contains(hash))
                throw new IllegalStateException("library type outside frozen archive: " + type);
            consumed.put(type, hash);
        }
        // Sorted installation metadata is separate from semantic output comparisons.
        save(output, "library-inputs", consumed);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("input.iron output-directory explain-on|explain-off");
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        verifyLibrary(output);
        SourceFile source = SourceFile.of(input.getFileName().toString(), Files.readString(input, StandardCharsets.UTF_8));
        var lexed = new Lexer(source).lex();
        var parsed = new Parser(source, lexed.tokens()).parse();
        save(output, "tokens", lexed.tokens());
        save(output, "lex-diagnostics", lexed.diagnostics());
        save(output, "ast", parsed.unit());
        save(output, "parse-diagnostics", parsed.diagnostics());
        CompilerPipeline pipeline = new CompilerPipeline(UnfreedMode.WARN, args[2].equals("explain-on"), null);
        var analyzed = pipeline.analyze(List.of(source));
        save(output, "diagnostics", analyzed.diagnostics());
        save(output, "typed-ir", analyzed.program());
        var compiled = pipeline.compile(List.of(source));
        save(output, "compile-diagnostics", compiled.diagnostics());
        save(output, "final-typed-ir", compiled.program());
        save(output, "status", List.of(analyzed.valid(), compiled.successful()));
        if (compiled.successful()) Files.writeString(output.resolve("output.ll"),
                new LlvmEmitter().emit(NativeLinkPipeline.finish(compiled.program().orElseThrow())), StandardCharsets.UTF_8);
    }
}
