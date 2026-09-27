// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;

import java.util.List;
import java.util.Map;

/** Java construction after protected native extraction; no native ownership or graph traversal. */
final class BridgeExceptionSources {
    private BridgeExceptionSources() {}

    record Sources(Map<String, String> sources, List<String> types) {}

    static Sources generate(CompilationArtifact artifact, BridgeGeneration generation, BridgeExceptionProjection projection) {
        if (!artifact.valid() || !projection.matches(artifact.program().orElseThrow())) {
            throw new IllegalArgumentException("exception source projection does not match the analyzed program");
        }
        if (!projection.customTypes().isEmpty()) throw new IllegalArgumentException("custom exception Java snapshots require the P3 adapter");
        String binaryName = generation.supportPackage() + ".ExceptionFactory";
        String annotation = "@Identity(" + BridgeJavaSources.quote(generation.identity()) + ")";
        boolean parsed = projection.types().stream().anyMatch(type -> type.nativeName().equals("ironwood.time.format.DateTimeParseException"));
        var source = new StringBuilder("// SPDX-License-Identifier: MIT OR Apache-2.0\n\npackage ")
                .append(generation.supportPackage()).append(";\n\n").append(annotation)
                .append("\nfinal class ExceptionFactory {\n    private ExceptionFactory() {}\n")
                .append("    private static Throwable create(int type, String message, Throwable cause,\n")
                .append("            String first, String second, String third, int number) {\n")
                .append("        Throwable value = switch (type) {\n");
        for (var type : projection.types()) {
            source.append("            case ").append(type.typeId()).append(" -> new ").append(type.javaName()).append('(')
                    .append(arguments(type.nativeName())).append(");\n");
        }
        source.append("            default -> throw new LinkageError(\"unmapped Ironwood exception type: \" + type);\n")
                .append("        };\n")
                .append("        if (value instanceof java.io.InterruptedIOException interrupted) interrupted.bytesTransferred = number;\n")
                .append("        return value;\n    }\n");
        source.append(BridgeExceptionGraphSources.generate(projection));
        if (parsed) {
            // A legal native CharSequence may render to null. Java's constructor
            // requires the sequence, but preserves its toString result unchanged.
            source.append("    private static final CharSequence NULL_PARSED_TEXT = new NullParsedText();\n")
                    .append("    ").append(annotation).append("\n")
                    .append("    private static final class NullParsedText implements CharSequence {\n")
                    .append("        @Override public int length() { return 0; }\n")
                    .append("        @Override public char charAt(int index) { throw new IndexOutOfBoundsException(index); }\n")
                    .append("        @Override public CharSequence subSequence(int start, int end) {\n")
                    .append("            if (start != 0 || end != 0) throw new IndexOutOfBoundsException();\n")
                    .append("            return this;\n        }\n")
                    .append("        @Override public String toString() { return null; }\n    }\n");
        }
        source.append("}\n");
        return new Sources(Map.of(binaryName.replace('.', '/') + ".java", source.toString()),
                parsed ? List.of(binaryName, binaryName + "$NullParsedText") : List.of(binaryName));
    }

    private static String arguments(String type) {
        return switch (type) {
            case "ironwood.nio.InvalidMarkException", "ironwood.nio.BufferUnderflowException",
                    "ironwood.nio.BufferOverflowException", "ironwood.nio.file.ClosedDirectoryStreamException" -> "";
            case "ironwood.nio.file.FileSystemException", "ironwood.nio.file.FileAlreadyExistsException",
                    "ironwood.nio.file.NoSuchFileException", "ironwood.nio.file.AccessDeniedException" -> "first, second, third";
            case "ironwood.nio.file.DirectoryNotEmptyException", "ironwood.nio.file.FileSystemLoopException" -> "first";
            case "ironwood.nio.file.InvalidPathException" -> "first, second, number";
            case "ironwood.time.format.DateTimeParseException" -> "message, first == null ? NULL_PARSED_TEXT : first, number";
            case "ironwood.nio.file.DirectoryIteratorException" -> "(java.io.IOException) cause";
            case "ironwood.io.UncheckedIOException" -> "message, (java.io.IOException) cause";
            default -> {
                if (!BridgeExportSurface.builtinThrowableNames().contains(type)) {
                    throw new IllegalArgumentException("unmapped exception constructor: " + type);
                }
                yield "message";
            }
        };
    }
}
