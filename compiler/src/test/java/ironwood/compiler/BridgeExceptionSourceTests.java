// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class BridgeExceptionSourceTests {
    static final String NAME = "Java Bridge generated exception factories preserve builtin constructor snapshots";

    private BridgeExceptionSourceTests() {}

    static void constructors() throws Exception {
        var source = new StringBuilder("package snapshotfactory; public final class Engine {\nprivate Engine() {}\n");
        int field = 0;
        for (String name : BridgeExportSurface.builtinThrowableNames().stream().sorted().toList()) {
            source.append("private static ").append(name).append(" field").append(field++).append(";\n");
        }
        source.append("public static int value() { return 1; }\n}\n");
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Engine.iron", source.toString())));
        check(artifact.valid(), artifact.diagnostics().toString());
        var projection = BridgeExceptionProjection.builtins(artifact, BridgeExportSurface.builtinThrowableNames()).contract().orElseThrow();
        var surface = BridgeExportSurface.scalarPreview(artifact, List.of("snapshotfactory")).surface().orElseThrow();
        var generation = BridgeGeneration.create("exceptions.jar", artifact, surface, "test", "1".repeat(64), "2".repeat(64));
        var module = BridgeEntryModule.scalars(artifact, surface.roots());
        var declarations = BridgeJavaSources.generate(artifact, surface, generation, module, projection);
        check(declarations.generatedTypes().contains(generation.supportPackage() + ".ExceptionFactory$NullParsedText"),
                "nullable parsed-text helper omitted from identity preflight");
        var changed = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Engine.iron",
                source.toString().replace("return 1;", "return 2;"))));
        var changedProjection = BridgeExceptionProjection.builtins(changed, BridgeExportSurface.builtinThrowableNames()).contract().orElseThrow();
        try {
            BridgeJavaSources.generate(artifact, surface, generation, module, changedProjection);
            throw new AssertionError("stale exception projection produced Java sources");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("projection"), expected.toString());
        }
        var payload = new BridgeLoaderSources.Payload(generation.nativeBuild("macos-arm64", Map.of("test", "constructors-only")),
                "11.0", "3".repeat(64));
        var sources = new java.util.TreeMap<>(declarations.sources());
        sources.put(generation.supportPackage().replace('.', '/') + "/Support.java", BridgeLoaderSources.generate(generation, declarations, payload));
        sources.put("FactoryConsumer.java", CONSUMER.replace("@PACKAGE@", generation.supportPackage())
                .replace("@COUNT@", Integer.toString(declarations.generatedTypes().size()))
                .replace("@IDS@", projection.types().stream().map(type -> Integer.toString(type.typeId())).collect(Collectors.joining(", ")))
                .replace("@NAMES@", projection.types().stream().map(type -> BridgeJavaSources.quote(type.javaName())).collect(Collectors.joining(", "))));
        sources.put("GraphConsumer.java", BridgeExceptionGraphTests.consumer(generation.supportPackage(), projection));
        Path base = Path.of("workspace/java-bridge/evidence/p2/exception-factories").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path classes = directory.resolve("classes");
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "javac").toString(),
                "--release", "21", "-Xlint:all", "-Werror", "-d", classes.toString()));
        for (var entry : sources.entrySet()) {
            Path file = directory.resolve("sources").resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            command.add(file.toString());
        }
        BridgeEntryTests.run(directory, command, "javac");
        BridgeEntryTests.run(directory, List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xcheck:jni", "-cp", classes.toString(), "FactoryConsumer"), "java-current");
        BridgeEntryTests.run(directory, List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xcheck:jni", "-cp", classes.toString(), "GraphConsumer"), "graph-current");
        BridgeEntryTests.run(directory, List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xcheck:jni", "-Xmx32m", "-cp", classes.toString(), "GraphConsumer", "pressure"), "graph-oom-current");
        System.out.println("generated exception factory evidence: " + directory);
    }

    private static final String CONSUMER = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            import java.io.*;
            import java.lang.reflect.*;
            import java.nio.file.*;
            import java.time.format.DateTimeParseException;
            import java.util.Objects;
            public final class FactoryConsumer {
                private static Method factory;
                private static Throwable create(int id, String message, Throwable cause, String first, String second,
                        String third, int number) throws Exception {
                    return (Throwable) factory.invoke(null, id, message, cause, first, second, third, number);
                }
                private static void check(boolean value, String reason) {
                    if (!value) throw new AssertionError(reason);
                }
                public static void main(String[] args) throws Exception {
                    Class<?> support = Class.forName("@PACKAGE@.Support");
                    Method preflight = support.getDeclaredMethod("preflight", ClassLoader.class);
                    preflight.setAccessible(true);
                    check(((Class<?>[]) preflight.invoke(null, support.getClassLoader())).length == @COUNT@, "incomplete preflight");
                    Class<?> type = Class.forName("@PACKAGE@.ExceptionFactory");
                    factory = type.getDeclaredMethod("create", int.class, String.class, Throwable.class,
                            String.class, String.class, String.class, int.class);
                    check(Modifier.isPrivate(factory.getModifiers()) && Modifier.isStatic(factory.getModifiers()), "factory access");
                    factory.setAccessible(true);
                    int[] ids = {@IDS@};
                    String[] names = {@NAMES@};
                    String text = "text\\u0000\\ud800\\udfff";
                    IOException cause = new IOException("cause");
                    for (int index = 0; index < ids.length; index++) {
                        for (String message : new String[]{null, "", text}) {
                            Throwable value = create(ids[index], message, cause, text, "other", "reason", 2);
                            check(value.getClass().getName().equals(names[index]), "wrong builtin type " + names[index]);
                            if (value instanceof FileSystemException file) {
                                check(file.getFile().equals(text), "file lost");
                                boolean single = file instanceof DirectoryNotEmptyException || file instanceof FileSystemLoopException;
                                check(Objects.equals(file.getOtherFile(), single ? null : "other"), "other file lost");
                                check(Objects.equals(file.getReason(), single ? null : "reason"), "reason lost");
                                check(file.getMessage().equals(single ? text : text + " -> other: reason"), "file message lost");
                            } else if (value instanceof InvalidPathException path) {
                                check(path.getInput().equals(text) && path.getReason().equals("other") && path.getIndex() == 2,
                                        "path fields lost");
                                check(path.getMessage().equals("other at index 2: " + text), "path message lost");
                            } else if (value instanceof DirectoryIteratorException) {
                                // The adapter must replace this constructor-derived prefix
                                // with the extracted native message before exposing the value.
                                check(value.getMessage().equals(cause.toString()), "directory constructor control");
                            } else if (value instanceof java.nio.InvalidMarkException || value instanceof java.nio.BufferUnderflowException
                                    || value instanceof java.nio.BufferOverflowException || value instanceof ClosedDirectoryStreamException) {
                                check(value.getMessage() == null, "empty constructor message");
                            } else check(Objects.equals(value.getMessage(), message), "message lost: " + names[index]);
                            if (value instanceof DateTimeParseException parsed) {
                                check(parsed.getParsedString().equals(text) && parsed.getErrorIndex() == 2, "parsed fields lost");
                                var nullable = (DateTimeParseException) create(ids[index], message, null, null, null, null, -17);
                                check(nullable.getParsedString() == null && nullable.getErrorIndex() == -17, "nullable parsed data lost");
                            }
                            if (value instanceof InterruptedIOException interrupted) check(interrupted.bytesTransferred == 2, "transfer count lost");
                            if (value instanceof DirectoryIteratorException || value instanceof UncheckedIOException) {
                                check(value.getCause() == cause, "constructor-required cause lost");
                            } else {
                                check(value.getCause() == null, "unexpected constructor cause");
                                value.initCause(cause);
                                check(value.getCause() == cause, "later cause attachment failed");
                            }
                            value.addSuppressed(new IllegalArgumentException("secondary"));
                            check(value.getSuppressed().length == 1, "secondary attachment failed");
                            if (value instanceof FileSystemException) {
                                var nullable = (FileSystemException) create(ids[index], null, null, null, null, null, 0);
                                check(nullable.getFile() == null && nullable.getOtherFile() == null && nullable.getReason() == null
                                        && nullable.getMessage() == null, "null file data lost");
                            }
                        }
                    }
                    try {
                        create(-1, null, null, null, null, null, 0);
                        throw new AssertionError("unmapped type accepted");
                    } catch (InvocationTargetException expected) {
                        check(expected.getCause() instanceof LinkageError, "wrong unmapped failure");
                    }
                    System.out.println("factory-ok:" + ids.length + ":" + Runtime.version());
                }
            }
            """;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
