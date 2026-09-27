// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeJavaSources;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class FileSystemExceptionTests {
    static final String NAME = "FileSystemException nullable constructor data and messages match Java 21";

    private FileSystemExceptionTests() {}

    static void messages() throws Exception {
        Path base = Path.of("workspace/java-bridge/evidence/p2/filesystem-messages").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        var source = new StringBuilder("""
                // SPDX-License-Identifier: MIT OR Apache-2.0
                import ironwood.nio.file.*;
                // Compare null, empty and nonempty constructor values with Java 21.
                // Exit zero means every getter and message preserved its value.
                class Main {
                    static boolean same(String actual, String expected) {
                        return actual == null ? expected == null : actual.equals(expected);
                    }
                    static boolean verify(FileSystemException value, String file, String other,
                            String reason, String message) {
                        return same(value.getFile(), file) && same(value.getOtherFile(), other)
                                && same(value.getReason(), reason) && same(value.getMessage(), message);
                    }
                    public static int main(String[] args) {
                        FileSystemException value = null;
                """);
        var identities = new ArrayList<String>();
        for (String file : new String[]{null, "", "file"}) {
            for (String other : new String[]{null, "", "other"}) {
                for (String reason : new String[]{null, "", "reason"}) {
                    var expected = new java.nio.file.FileSystemException(file, other, reason);
                    append(source, identities, "new FileSystemException(" + literal(file) + ", "
                            + literal(other) + ", " + literal(reason) + ")", expected);
                }
            }
            for (var expected : List.of(new java.nio.file.FileSystemException(file),
                    new java.nio.file.DirectoryNotEmptyException(file), new java.nio.file.FileSystemLoopException(file),
                    new java.nio.file.NoSuchFileException(file), new java.nio.file.AccessDeniedException(file),
                    new java.nio.file.FileAlreadyExistsException(file))) {
                append(source, identities, "new " + expected.getClass().getSimpleName() + "(" + literal(file) + ")", expected);
            }
        }
        source.append("        return 0;\n    }\n}\n");
        Path input = directory.resolve("Main.iron");
        Files.writeString(input, source);
        Files.write(directory.resolve("cases.txt"), identities);
        Path classes = directory.resolve("classes");
        var output = new ByteArrayOutputStream();
        var print = new PrintStream(output, true, StandardCharsets.UTF_8);
        check(Main.run(new String[]{input.toString(), "-d", classes.toString()}, print, print) == 0, output.toString());
        for (int level : List.of(0, 3)) {
            Path image = directory.resolve("messages-O" + level);
            output.reset();
            check(Main.run(new String[]{"--link", "-cp", classes.toString(), "--main-class", "Main",
                    "-o", image.toString(), "-O" + level}, print, print) == 0, output.toString());
            BridgeEntryTests.run(directory, List.of(image.toString()), "native-O" + level);
        }
        System.out.println("filesystem exception differential evidence: " + directory);
    }

    private static void append(StringBuilder source, List<String> identities, String constructor,
            java.nio.file.FileSystemException expected) {
        identities.add((identities.size() + 1) + ": " + constructor + " => " + literal(expected.getMessage()));
        source.append("        value = ").append(constructor).append(";\n")
                .append("        if (!verify(value, ").append(literal(expected.getFile())).append(", ")
                .append(literal(expected.getOtherFile())).append(", ").append(literal(expected.getReason())).append(", ")
                .append(literal(expected.getMessage())).append(")) return ").append(identities.size()).append(";\n")
                .append("        free value;\n");
    }

    private static String literal(String value) { return value == null ? "null" : BridgeJavaSources.quote(value); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
