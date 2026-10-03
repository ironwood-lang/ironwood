// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeGeneration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipFile;

final class BridgeOwnedCallbackProducerTests {
    static final String NAME = "Java Bridge producer packages retained owner listeners with source class archive parity";
    private BridgeOwnedCallbackProducerTests() {}

    static void producer() throws Exception {
        Path base = Path.of("workspace/java-bridge/evidence/p5/owner-producer").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        var sources = new ArrayList<>(BridgeOwnedCallbackJavaTests.sources());
        var holderSource = sources.get(1);
        sources.set(1, ironwood.compiler.source.SourceFile.of(holderSource.path().toString(),
                holderSource.content().replace("private long value;", "private long value;\n" + EXCEPTIONS)));
        var paths = new ArrayList<Path>();
        for (var source : sources) {
            Path path = directory.resolve("source").resolve(source.path().getFileName()); Files.createDirectories(path.getParent());
            Files.writeString(path, source.content()); paths.add(path);
        }
        Path classes = directory.resolve("iron-classes"), archive = directory.resolve("owners.ironjar");
        var compile = new ArrayList<>(List.of("-d", classes.toString())); paths.forEach(path -> compile.add(path.toString()));
        BridgeProducerTests.command(directory, "compile", 0, compile.toArray(String[]::new));
        IronJar.create(archive, List.of(classes), List.of());
        Properties reference = null; Path first = null;
        for (String variant : List.of("source", "classes", "archive")) {
            Path folder = directory.resolve(variant); Files.createDirectories(folder);
            Path jar = folder.resolve("owners.jar");
            var arguments = new ArrayList<>(List.of("--java-bridge", "--export", "ownerfacades", "--unfreed=error",
                    "-o", jar.toString(), variant.equals("source") ? "-O0" : "-O3"));
            if (variant.equals("source")) paths.forEach(path -> arguments.add(path.toString()));
            else arguments.addAll(List.of("--source-path", directory.resolve("absent").toString(), "-cp", (variant.equals("classes") ? classes : archive).toString()));
            BridgeProducerTests.command(folder, "producer", 0, arguments.toArray(String[]::new));
            var manifest = inspect(jar);
            if (reference == null) { reference = manifest; first = jar; }
            else for (String key : List.of("generation", "program", "api")) check(reference.getProperty(key).equals(manifest.getProperty(key)), "owner artifact parity " + key);
            consumer(folder, jar, manifest);
            if (variant.equals("source")) for (Path path : paths) Files.delete(path);
        }
        check(first != null, "no owner artifact");
        byte[] accepted = Files.readAllBytes(first);
        for (int i = 0; i < paths.size(); i++) Files.writeString(paths.get(i), sources.get(i).content());
        int holder = 1;
        String original = sources.get(holder).content();
        var rejected = List.of(
                original.replace("long result = listener.call(n);", "listener = null; long result = 1L;"),
                original.replace("private long value;", "private long value; private static Listener hidden;")
                        .replace("listener = input;", "listener = input; hidden = input;"),
                original.replace("private long value;", "private long value; private Holder retained;")
                        .replace("long result = listener.call(n);", "retained = other; long result = listener.call(n);"),
                original.replace("public long value()", "public Listener unsupported() { return listener; } public long value()"),
                original.replace("private long value;", "private long value; private static String published;")
                        .replace("long before = hash(first) + hash(second);", "published = first; long before = hash(first) + hash(second);"),
                original.replace("private long value;", "private long value; private static Holder exposed;")
                        .replace("return input.call(this, other, foreign);", "exposed = other; return input.call(this, other, foreign);"),
                original.replace("return input.call(this, other, foreign);", "long value = input.call(this, other, foreign); free other; return value;"),
                original.replace("failure.initCause(addition);", "free failure;"),
                original.replace("public long value()", "public long badMessage(String text) { try { return listener.call(1L); } "
                        + "catch (RuntimeException failure) { savedFailure = new IllegalStateException(text, failure); throw savedFailure; } } public long value()"));
        for (int n = 0; n < rejected.size(); n++) {
            Files.writeString(paths.get(holder), rejected.get(n));
            for (var mode : UnfreedMode.values()) {
                var arguments = new ArrayList<>(List.of("--java-bridge", "--export", "ownerfacades", "--unfreed=" + mode.name().toLowerCase(java.util.Locale.ROOT), "-o", first.toString()));
                paths.forEach(path -> arguments.add(path.toString()));
                BridgeProducerTests.command(directory, "reject-" + n + "-" + mode, 1, arguments.toArray(String[]::new));
                check(java.util.Arrays.equals(accepted, Files.readAllBytes(first)), "refused owner changed existing artifact");
            }
        }
        Files.writeString(paths.get(holder), original.replace("public long expose(", "public Holder expose("));
        Files.writeString(paths.get(3), sources.get(3).content().replace("long call(", "Holder call("));
        for (var mode : UnfreedMode.values()) {
            var arguments = new ArrayList<>(List.of("--java-bridge", "--export", "ownerfacades", "--unfreed=" + mode.name().toLowerCase(java.util.Locale.ROOT), "-o", first.toString()));
            paths.forEach(path -> arguments.add(path.toString()));
            BridgeProducerTests.command(directory, "reject-reference-result-" + mode, 1, arguments.toArray(String[]::new));
            check(java.util.Arrays.equals(accepted, Files.readAllBytes(first)), "refused reference result changed existing artifact");
        }
        System.out.println("owner producer evidence: " + directory);
    }

    private static Properties inspect(Path jar) throws Exception {
        var properties = new Properties();
        try (var zip = new ZipFile(jar.toFile())) {
            try (var input = zip.getInputStream(zip.getEntry(BridgePackageManifest.PATH))) { properties.load(input); }
            check(properties.getProperty("projection").equals("owner-callbacks-v1"), "wrong owner projection");
            var restored = new java.util.TreeMap<String, String>(); properties.stringPropertyNames().forEach(key -> restored.put(key, properties.getProperty(key)));
            check(BridgeGeneration.fromManifest(restored).identity().equals(properties.getProperty("generation")), "owner manifest did not restore");
            for (var entry : zip.stream().toList()) {
                if (entry.getName().equals(BridgePackageManifest.PATH)) continue;
                try (var input = zip.getInputStream(entry)) {
                    check(BridgeGeneration.bytesDigest(input.readAllBytes()).equals(properties.getProperty("content.sha256." + entry.getName())), "unpaired entry " + entry.getName());
                }
            }
            for (String name : List.of("ownerfacades/Holder.class", "ownerfacades/Listener.class", "ownerfacades/OtherOwner.class",
                    "META-INF/ironwood/java-sources/ownerfacades/Holder.java", "META-INF/ironwood/javadoc/ownerfacades/Listener.html")) {
                check(zip.getEntry(name) != null, "missing owner artifact " + name);
            }
            try (var input = zip.getInputStream(zip.getEntry(BridgePackageManifest.PATH))) { Files.write(jar.resolveSibling("paired.properties"), input.readAllBytes()); }
            String resource = properties.getProperty("native.resource");
            try (var input = zip.getInputStream(zip.getEntry(resource))) {
                byte[] payload = input.readAllBytes();
                check(BridgeGeneration.bytesDigest(payload).equals(properties.getProperty("native.sha256")), "native payload mismatch");
                Files.write(jar.resolveSibling(resource.endsWith(".dylib") ? "paired.dylib" : "paired.so"), payload);
            }
        }
        return properties;
    }

    private static void consumer(Path folder, Path jar, Properties manifest) throws Exception {
        Path source = folder.resolve("OwnerConsumer.java"), classes = folder.resolve("consumer"), jdk = Path.of(System.getProperty("java.home"));
        Files.writeString(source, CONSUMER);
        BridgeEntryTests.run(folder, List.of(jdk.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-cp", jar.toString(), "-d", classes.toString(), source.toString()), "javac");
        for (boolean module : List.of(false, true)) {
            var command = new ArrayList<>(List.of(jdk.resolve("bin/java").toString(), "-Xcheck:jni"));
            if (module) command.addAll(List.of("--module-path", jar.toString(), "--add-modules", manifest.getProperty("java.module"), "-cp", classes.toString()));
            else command.addAll(List.of("-cp", jar + java.io.File.pathSeparator + classes));
            command.add("OwnerConsumer");
            check(BridgeEntryTests.run(folder, command, module ? "module" : "classpath").equals("owner-producer-ok\n"), "owner consumer failed");
        }
    }

    private static final String CONSUMER = """
            import ownerfacades.Holder;
            import ownerfacades.OtherOwner;
            import ownerfacades.Listener;
            public final class OwnerConsumer {
                public static void main(String[] args) {
                    Holder left = new Holder(5L), right = new Holder(7L);
                    OtherOwner foreign = new OtherOwner();
                    try {
                        Listener a = n -> n + 1L, b = n -> n * 2L;
                        left.store(a); foreign.store(b);
                        check(left.fire(right, foreign, 3L) == 17L);
                        check(left.textValue(null) == -1L && left.text("a", "b", 0L) == 257L);
                        Holder[] retained = new Holder[1];
                        check(left.expose((self, other, different) -> {
                            check(self == left && other == right && different == foreign);
                            retained[0] = self; refuse(self::free); return other.value();
                        }, right, foreign) == 7L && retained[0] == left);
                        left.store(n -> { refuse(left::free); refuse(right::free); refuse(foreign::free); left.store(b); return n; });
                        check(left.fire(right, foreign, 3L) == 16L && left.twice(1L) == 6L);
                        Holder.put(left, left, a, b); check(left.twice(1L) == 6L);
                        RuntimeException failure = new RuntimeException("original");
                        left.store(n -> { throw failure; });
                        try { left.fire(right, foreign, 3L); throw new AssertionError(); }
                        catch (RuntimeException actual) { check(actual == failure); }
                        left.store(a);
                        try { left.choose(b, a, true); throw new AssertionError(); }
                        catch (IllegalStateException expected) { check(expected.getClass() == IllegalStateException.class); }
                        check(left.twice(1L) == 6L && Holder.direct(right, a, 2L) == 10L);
                        exceptions(left);
                    } finally { left.free(); right.free(); foreign.free(); }
                    left.free(); right.free(); foreign.free(); refuse(() -> left.value());
                    System.out.println("owner-producer-ok");
                }
                private static void refuse(Runnable action) {
                    try { action.run(); throw new AssertionError("missing lifetime refusal"); }
                    catch (IllegalStateException expected) { check(expected.getClass().getSimpleName().equals("BridgeLifetimeException")); }
                }
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
                private static RuntimeException thrown(Runnable action) {
                    try { action.run(); throw new AssertionError("missing exception"); }
                    catch (RuntimeException actual) { return actual; }
                }
                private static void exceptions(Holder holder) {
                    RuntimeException original = new RuntimeException("original", null, false, true) {};
                    holder.store(n -> { throw original; });
                    check(thrown(() -> holder.temporary(0L)) == original);
                    check(holder.temporary(1L) == 91L);
                    check(thrown(() -> holder.temporary(2L)).getClass() == IllegalStateException.class);
                    check(holder.exception(3L) == 93L);
                    check(thrown(holder::rethrowSaved) == original);
                    RuntimeException outer = thrown(() -> holder.exception(8L));
                    check(outer.getClass() == IllegalStateException.class && outer.getCause() == original);
                    RuntimeException secondary = thrown(() -> holder.exception(10L));
                    check(secondary != original && secondary.getCause() == original && secondary.getSuppressed().length == 1);
                    check(secondary.getSuppressed()[0].getMessage().equals("native secondary"));
                    for (long mode : new long[]{11L, 13L}) {
                        RuntimeException modified = thrown(() -> holder.exception(mode));
                        check(modified != original && modified.getCause() == original && modified.getSuppressed().length == 1);
                        Throwable label = modified.getSuppressed()[0];
                        check(label.getMessage().equals("Ironwood native cause"));
                        check(label.getCause().getClass() == IllegalStateException.class);
                        check(label.getCause().getMessage().equals("native cause"));
                        if (mode == 13L) check(label.getCause().getCause() == modified);
                        RuntimeException again = thrown(holder::rethrowSaved);
                        check(again != modified && again.getCause() == original && again.getSuppressed().length == 1);
                    }
                    check(original.getCause() == null && original.getSuppressed().length == 0);
                    holder.store(n -> n + 1L);
                    check(holder.twice(1L) == 5L);
                }
            }
            """;
    private static final String EXCEPTIONS = """
            private static RuntimeException savedFailure;
            public long rethrowSaved() { throw savedFailure; }
            public long temporary(long mode) {
                try { return listener.call(mode); }
                catch (RuntimeException failure) {
                    if (mode == 1L) return 91L;
                    if (mode == 2L) throw new IllegalStateException("replacement");
                    throw failure;
                }
            }
            public long exception(long mode) {
                if (mode == 10L) {
                    try { return listener.call(mode); }
                    finally { throw new IllegalStateException("native secondary"); }
                }
                try { return listener.call(mode); }
                catch (RuntimeException failure) {
                    if (mode == 3L) { savedFailure = failure; return 93L; }
                    if (mode == 8L) throw new IllegalStateException("wrapped", failure);
                    if (mode == 11L || mode == 13L) {
                        IllegalStateException addition = new IllegalStateException("native cause");
                        failure.initCause(addition);
                        if (mode == 13L) addition.initCause(failure);
                        savedFailure = failure;
                    }
                    throw failure;
                }
            }
            """;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
