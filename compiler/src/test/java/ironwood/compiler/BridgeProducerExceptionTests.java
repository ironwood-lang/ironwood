// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeGeneration;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class BridgeProducerExceptionTests {
    static final String NAME = "Java Bridge producer contains exception graphs and native or Java exhaustion";
    private BridgeProducerExceptionTests() {}

    static void exceptions() throws Exception {
        BridgeGeneratedJarTests.target();
        Path base = Path.of("workspace/java-bridge/evidence/p2/producer-exceptions").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path source = directory.resolve("Errors.iron"), classes = directory.resolve("iron-classes"), archive = directory.resolve("errors.ironjar");
        Files.writeString(source, BridgeExceptionNativeTests.SOURCE);
        producer(directory, "iron-compile", List.of("--unfreed=off", "-d", classes.toString(), source.toString()));
        IronJar.create(archive, List.of(classes));
        Path consumer = directory.resolve("ProducerExceptions.java"), consumerClasses = directory.resolve("consumer-classes");
        Files.writeString(consumer, CONSUMER);
        Path javaHome = Path.of(System.getProperty("java.home"));
        for (String level : List.of("O0", "O3")) {
            Path folder = directory.resolve(level); Files.createDirectories(folder);
            Path jar = folder.resolve("errors.jar");
            var arguments = new ArrayList<>(List.of("--java-bridge", "--export", "snapshotnative", "--unfreed=off", "-" + level, "-o", jar.toString()));
            if (level.equals("O0")) arguments.add(source.toString());
            else arguments.addAll(List.of("-cp", archive.toString()));
            producer(folder, "producer", arguments);
            Files.writeString(folder.resolve("jar.sha256"), BridgeGeneration.bytesDigest(Files.readAllBytes(jar)) + "\n");
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                    "-cp", jar.toString(), "-d", consumerClasses.toString(), consumer.toString()), "consumer-javac");
            for (String scenario : List.of("normal", "0", "1", "2", "secondary-1", "secondary-2", "secondary-0", "java-oom")) {
                var command = BridgeEntryTests.grantNativeAccess(List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-Xmx24m", "-XX:-UseGCOverheadLimit",
                        "-cp", jar + java.io.File.pathSeparator + consumerClasses, "ProducerExceptions", scenario));
                var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(folder.resolve(scenario + ".log").toFile());
                builder.environment().remove("IRONWOOD_ALLOCATION_LIMIT");
                if (!scenario.equals("normal") && !scenario.equals("java-oom")) builder.environment().put("IRONWOOD_ALLOCATION_LIMIT", scenario.replace("secondary-", ""));
                Files.writeString(folder.resolve(scenario + ".command.txt"), String.join("\n", command)
                        + "\nIRONWOOD_ALLOCATION_LIMIT=" + builder.environment().getOrDefault("IRONWOOD_ALLOCATION_LIMIT", "unset") + "\n");
                var process = builder.start();
                if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("producer exception child timed out"); }
                Files.writeString(folder.resolve(scenario + ".exit.txt"), process.exitValue() + "\n");
                String output = Files.readString(folder.resolve(scenario + ".log"));
                boolean fatal = scenario.equals("secondary-0");
                String expected = fatal ? "ironwood: allocation failed while implicit OutOfMemoryError is active\n" : "producer-exceptions-ok:" + scenario + "\n";
                check(process.exitValue() == (fatal ? 1 : 0) && output.equals(expected), level + "/" + scenario + ": " + output);
            }
        }
        System.out.println("Java Bridge producer exception evidence: " + directory);
    }

    private static void producer(Path directory, String name, List<String> arguments) throws Exception {
        var output = new ByteArrayOutputStream(); int status;
        try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8)) { status = Main.run(arguments.toArray(String[]::new), stream, stream); }
        Files.writeString(directory.resolve(name + ".log"), output.toString(StandardCharsets.UTF_8));
        Files.writeString(directory.resolve(name + ".command.txt"), String.join("\n", arguments) + "\n");
        Files.writeString(directory.resolve(name + ".exit.txt"), status + "\n");
        check(status == 0, name + ": " + output);
    }

    private static final String CONSUMER = """
            import snapshotnative.Errors;
            import java.time.format.DateTimeParseException;
            public final class ProducerExceptions {
                private static byte[][] pressure;
                private static void dateTime() {
                    try { Errors.fail(); throw new AssertionError("missing DateTimeParseException"); }
                    catch (DateTimeParseException value) {
                        if (!value.getMessage().equals("detail") || !value.getParsedString().equals("te\\u0000\\uD800xt")
                                || value.getErrorIndex() != 2 || value.getCause() != null) throw new AssertionError("copied getter data");
                        int nativeSite = -1, javaSite = -1;
                        StackTraceElement[] frames = value.getStackTrace();
                        for (int i = 0; i < frames.length; i++) {
                            var frame = frames[i];
                            if (frame.getClassName().equals("snapshotnative.Errors") && frame.getMethodName().equals("fail")
                                    && frame.getFileName().equals("Errors.iron") && frame.getLineNumber() == 5) nativeSite = i;
                            if (frame.getClassName().equals("ProducerExceptions") && javaSite < 0) javaSite = i;
                        }
                        if (nativeSite < 0 || javaSite <= nativeSite) throw new AssertionError("native/Java trace order");
                    }
                }
                private static void graphs() throws java.io.IOException {
                    dateTime();
                    try { Errors.directory(); throw new AssertionError("missing DirectoryIteratorException"); }
                    catch (java.nio.file.DirectoryIteratorException value) {
                        if (!value.getMessage().equals("ironwood.io.IOException: cause") || value.getCause().getClass() != java.io.IOException.class
                                || !value.getCause().getMessage().equals("cause")) throw new AssertionError("directory message/cause");
                    }
                    try { Errors.file(); throw new AssertionError("missing FileSystemException"); }
                    catch (java.nio.file.FileSystemException value) {
                        if (!value.getMessage().equals("file -> other: reason") || !value.getFile().equals("file")
                                || !value.getOtherFile().equals("other") || !value.getReason().equals("reason")) throw new AssertionError("file fields");
                    }
                    try { Errors.path(); throw new AssertionError("missing InvalidPathException"); }
                    catch (java.nio.file.InvalidPathException value) {
                        if (!value.getMessage().equals("reason at index 2: input") || !value.getInput().equals("input")
                                || !value.getReason().equals("reason") || value.getIndex() != 2) throw new AssertionError("path fields");
                    }
                    try { Errors.cycle(); throw new AssertionError("missing cause cycle"); }
                    catch (java.io.UncheckedIOException value) {
                        Throwable first = value.getCause(), second = first.getCause();
                        if (!first.getMessage().equals("first") || !second.getMessage().equals("second") || second.getCause() != first) throw new AssertionError("cycle identity");
                    }
                    try { Errors.chain(40); throw new AssertionError("missing bounded chain"); }
                    catch (java.io.UncheckedIOException value) {
                        Throwable last = value; int count = 1;
                        while (last.getCause() != null && count <= 34) { last = last.getCause(); count++; }
                        if (count != 33 || !last.getMessage().contains("copy limit")) throw new AssertionError("cause bound");
                    }
                    try { Errors.secondary(40); throw new AssertionError("missing secondary bound"); }
                    catch (IllegalArgumentException value) {
                        Throwable[] secondary = value.getSuppressed();
                        if (!value.getMessage().equals("primary") || secondary.length != 32 || !(secondary[31] instanceof java.io.IOException)
                                || !secondary[31].getMessage().contains("copy limit")) throw new AssertionError("secondary bound");
                        for (int i = 0; i < 31; i++) if (secondary[i].getClass() != IllegalStateException.class) throw new AssertionError("secondary order");
                        if (java.util.Arrays.stream(value.getStackTrace()).noneMatch(frame -> frame.getMethodName().equals("nativeFramesTruncated"))) throw new AssertionError("trace bound");
                    }
                    for (int i = 0; i < 3; i++) {
                        try { Errors.initialized(); throw new AssertionError("missing stored initializer failure"); }
                        catch (IllegalArgumentException value) {
                            if (!value.getMessage().equals("primary") || value.getSuppressed().length != 1
                                    || !value.getSuppressed()[0].getMessage().equals("secondary")) throw new AssertionError("initializer snapshot");
                        }
                    }
                }
                private static void javaExhaustion() {
                    dateTime();
                    pressure = new byte[1_000_000][];
                    int index = 0;
                    try { while (true) pressure[index++] = new byte[1024]; } catch (OutOfMemoryError expected) {}
                    try { while (true) pressure[index++] = new byte[1]; } catch (OutOfMemoryError expected) {}
                    boolean failed = false;
                    try { Errors.fail(); }
                    catch (OutOfMemoryError expected) { failed = true; }
                    finally { pressure = null; }
                    System.gc();
                    if (!failed || Errors.ping() != 42) throw new AssertionError("Java delivery exhaustion/continuation");
                    dateTime();
                }
                public static void main(String[] args) throws java.io.IOException {
                    String scenario = args[0];
                    if (Errors.ping() != 42) throw new AssertionError("binding/scalar");
                    if (scenario.equals("normal")) graphs();
                    else if (scenario.equals("java-oom")) javaExhaustion();
                    else if (scenario.startsWith("secondary-")) {
                        try { Errors.secondary(1); throw new AssertionError("missing secondary failure"); }
                        catch (IllegalArgumentException value) {
                            Class<?> secondary = scenario.endsWith("1") ? OutOfMemoryError.class : IllegalStateException.class;
                            if (!value.getMessage().equals("primary") || value.getSuppressed().length != 1
                                    || value.getSuppressed()[0].getClass() != secondary) throw new AssertionError("allocation-limited secondary");
                        }
                        try { Errors.fail(); throw new AssertionError("native limit disappeared"); } catch (OutOfMemoryError expected) {}
                    } else {
                        for (int i = 0; i < 3; i++) {
                            try { Errors.fail(); throw new AssertionError("missing native allocation failure"); } catch (OutOfMemoryError expected) {}
                            if (Errors.ping() != 42) throw new AssertionError("continuation after native OOM");
                        }
                    }
                    if (Errors.ping() != 42) throw new AssertionError("continued scalar call");
                    System.out.println("producer-exceptions-ok:" + scenario);
                }
            }
            """;

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
