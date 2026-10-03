// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.LlvmToolchain;
import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class BridgePermanentJavaSourceTests {
    static final String NAME = "Java Bridge permanent Java facades preserve private metadata and inherited identity";
    private static final String SOURCE = """
            package permanentjava;
            public final class Item {
                private static Item saved;
                public static final int java = 1;
                public static final int $ironwood$address = 2;
                public Item() {}
                public Item(long number) {}
                public Item(long number, String label) {}
                public Item publish() { saved = this; return saved; }
                public Item accept(Item value) { return value; }
                public int number() { return 17; }
                public static int $ironwood$native$0() { return 9; }
                public static void $ironwood$ensure() {}
                public static int overloaded(int value) throws Exception { return value; }
                public static long overloaded(long value) { return value; }
                public static final class Child {
                    private static Child saved;
                    public Child(long number) {}
                    public Child publish() { saved = this; return saved; }
                    @Override public int hashCode() { return 777; }
                }
            }
            """;

    private BridgePermanentJavaSourceTests() {}

    static void declarations() throws Exception {
        var artifact = analyze(SOURCE);
        var admission = admit(artifact);
        var generation = generation(artifact, admission);
        var projected = BridgePermanentJavaSources.generate(artifact, admission, generation);
        var declarations = projected.declarations();
        check(projected.facades().size() == 2 && declarations.facadeRegistrations().size() == 2, "missing nested facade metadata");
        check(!declarations.ensureMethod().equals("$ironwood$ensure"), "source method collided with bootstrap import");
        check(projected.facades().stream().filter(facade -> facade.binaryName().equals("permanentjava.Item"))
                .noneMatch(facade -> facade.addressField().equals("$ironwood$address")), "source field collided with private address");
        var changed = analyze(SOURCE.replace("return 17;", "return 19;"));
        try {
            BridgePermanentJavaSources.generate(changed, admission, generation);
            throw new AssertionError("stale permanent declaration proof admitted");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("matching final object admission"), expected.getMessage());
        }
        for (String unsupported : List.of(
                "package permanentjava; public final class Item { public Item() {} public int number() { return 17; } }")) {
            var other = analyze(unsupported);
            var proof = admit(other);
            try {
                BridgePermanentJavaSources.generate(other, proof, generation(other, proof));
                throw new AssertionError("incomplete lifetime/projection adapter admitted");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("do not yet project"), expected.getMessage());
            }
        }
        var build = generation.nativeBuild("macos-arm64", java.util.Map.of("fixture", "declarations"));
        try {
            BridgeBootstrapSources.generate(generation, build, declarations, new BridgeValueNativeSources("", List.of()));
            throw new AssertionError("value bootstrap accepted facade registrations");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("cannot register object helpers"), expected.getMessage());
        }
        String loader = BridgeLoaderSources.generate(generation, declarations, new BridgeLoaderSources.Payload(build, "14.0", "3".repeat(64)));
        for (var registration : declarations.facadeRegistrations()) {
            check(loader.contains(BridgeJavaSources.quote(registration.nativeName()))
                    && loader.contains(BridgeJavaSources.quote(registration.descriptor())), "loader omitted host helper");
        }
        var manifest = new java.util.Properties();
        manifest.load(new java.io.ByteArrayInputStream(BridgePackageManifest.create(generation, build, declarations,
                new BridgeMacPayload("14.0", "26.0", List.of()), "fixture.dylib", java.util.Map.of("fixture.dylib", new byte[]{1}))));
        for (int index = 0; index < declarations.facadeRegistrations().size(); index++) {
            var registration = declarations.facadeRegistrations().get(index);
            String prefix = "java.facade.registration." + index;
            check(registration.binaryName().equals(manifest.getProperty(prefix + ".type"))
                    && registration.nativeName().equals(manifest.getProperty(prefix + ".name"))
                    && registration.descriptor().equals(manifest.getProperty(prefix + ".descriptor"))
                    && !manifest.containsKey(prefix + ".entry"), "host helper inventory fabricated a source entry");
        }
        Path base = Path.of("workspace/java-bridge/evidence/p3b/permanent-java").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path sources = directory.resolve("sources"), classes = directory.resolve("classes");
        Files.createDirectories(classes);
        var files = new ArrayList<String>();
        for (var entry : declarations.sources().entrySet()) {
            Path file = sources.resolve(entry.getKey()); Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue()); files.add(file.toString());
        }
        // The loader is deliberately stubbed for declarations/Java identity tests.
        // No public native constructor or source-native member is made usable here.
        Path stub = sources.resolve(generation.supportPackage().replace('.', '/') + "/Support.java");
        Files.writeString(stub, "package " + generation.supportPackage() + "; public final class Support { public static int calls; public static void "
                + declarations.ensureMethod() + "() { calls++; } }\n"); files.add(stub.toString());
        Path caller = sources.resolve("CompileConsumer.java");
        Files.writeString(caller, "class CompileConsumer { permanentjava.Item value() { return new permanentjava.Item(1L, null); } }\n");
        files.add(caller.toString());
        var javaHome = Path.of(System.getProperty("java.home"));
        var command = new ArrayList<>(List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-d", classes.toString()));
        command.addAll(files);
        BridgeEntryTests.run(directory, command, "javac");
        long[] addresses = {0, 1, 8, 0x0123456789abcdefL, Long.MAX_VALUE, Long.MIN_VALUE, -1, -4096};
        var expected = runtimeHashes(directory, addresses);
        try (var classesLoader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, null)) {
            for (String name : declarations.generatedTypes()) Class.forName(name, false, classesLoader);
            Set<String> actualNatives = new HashSet<>();
            for (String name : declarations.generatedTypes()) {
                var type = Class.forName(name, false, classesLoader);
                for (var method : type.getDeclaredMethods()) {
                    if (!Modifier.isNative(method.getModifiers())) continue;
                    check(Modifier.isPrivate(method.getModifiers()) && Modifier.isStatic(method.getModifiers()), "native handle API exposed");
                    actualNatives.add(name + "." + method.getName() + MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString());
                }
            }
            check(actualNatives.equals(declarations.nativeDeclarations().stream().map(binding -> binding.binaryName() + "."
                    + binding.nativeName() + binding.descriptor()).collect(java.util.stream.Collectors.toSet())), "native descriptor inventory mismatch");
            var support = Class.forName(generation.supportPackage() + ".Support", true, classesLoader);
            for (var facade : projected.facades()) {
                var type = Class.forName(facade.binaryName(), true, classesLoader);
                check(Modifier.isFinal(type.getModifiers()), "permanent facade is subclassable");
                for (var field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    check(Modifier.isPrivate(field.getModifiers()) && Modifier.isFinal(field.getModifiers())
                            && Set.of(facade.addressField(), facade.typeNameField()).contains(field.getName()), "mutable lifetime state on permanent facade");
                }
                check(java.util.Arrays.stream(type.getDeclaredMethods()).noneMatch(method -> method.getName().equals("free")), "permanent destruction exposed");
                var constructor = type.getDeclaredConstructor(long.class, Void.class); constructor.setAccessible(true);
                check(Modifier.isPrivate(constructor.getModifiers()), "raw handle constructor exposed");
                var self = constructor.newInstance(addresses[0], null);
                var distinct = constructor.newInstance(addresses[0], null);
                check(self.equals(self) && !self.equals(distinct) && !distinct.equals(self) && !self.equals(null) && !self.equals(new Object()),
                        "facade equality lost Java reference semantics");
                boolean override = facade.binaryName().endsWith("$Child");
                int boots = support.getField("calls").getInt(null);
                for (int index = 0; index < addresses.length; index++) {
                    Object value = constructor.newInstance(addresses[index], null);
                    check(value.toString().equals(facade.binaryName() + "@" + Integer.toHexString(expected[index])), "text differs from paired runtime identity");
                    if (!override) {
                        check(value.hashCode() == expected[index], "hash differs from paired runtime identity");
                        var set = new HashSet<Object>(); set.add(value); check(set.remove(value), "hash collection identity failed");
                    } else {
                        try { value.hashCode(); throw new AssertionError("source override silently projected in Java"); }
                        catch (UnsatisfiedLinkError expectedNative) { /* Private native source override is deliberately unbound. */ }
                    }
                    Throwable[] failure = new Throwable[1];
                    var thread = new Thread(() -> {
                        try { check(value.equals(value) && !value.toString().isEmpty(), "asynchronous identity failed"); }
                        catch (Throwable problem) { failure[0] = problem; }
                    });
                    thread.start(); thread.join(); check(failure[0] == null, String.valueOf(failure[0]));
                }
                check(support.getField("calls").getInt(null) == boots, "identity operation entered bootstrap");
            }
            var item = Class.forName("permanentjava.Item", false, classesLoader);
            check(item.getConstructor(long.class, String.class).getParameterCount() == 2, "public constructor signature changed");
            check(item.getMethod("overloaded", int.class).getExceptionTypes()[0] == Exception.class, "checked declaration lost");
            check(item.getMethod("overloaded", long.class).getReturnType() == long.class, "overload changed");
            check(item.getMethod("accept", item).getReturnType() == item, "same-world reference signature changed");
        }
        Files.writeString(directory.resolve("scope.txt"), "generated Java declarations and actual runtime hash-function parity; test-only loader stub; no JNI or jar qualification\n");
        System.out.println("permanent Java declaration evidence: " + directory);
    }

    private static int[] runtimeHashes(Path directory, long[] addresses) throws Exception {
        String runtime = Files.readString(Path.of("runtime/src/ironwood_runtime.c"));
        int start = runtime.indexOf("static uint32_t identity_hash(const void *object) {");
        int end = runtime.indexOf("\nint32_t ironwood_object_hash_code", start);
        check(start >= 0 && end > start, "paired runtime hash function missing");
        String function = runtime.substring(start, end);
        Path source = directory.resolve("runtime-hash.c"), executable = directory.resolve("runtime-hash");
        Files.writeString(source, "#include <stdint.h>\n#include <inttypes.h>\n#include <stdio.h>\n#include <stdlib.h>\n"
                + function + "\nint main(int argc, char **argv) { for (int i = 1; i < argc; i++) printf(\"%\" PRIu32 \"\\n\", identity_hash((void *)(uintptr_t)strtoull(argv[i], NULL, 16))); return 0; }\n");
        Files.writeString(directory.resolve("runtime-hash.sha256"), BridgeGeneration.bytesDigest(function.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "\n");
        var discovery = LlvmToolchain.discover(null); check(discovery.successful(), discovery.error());
        BridgeEntryTests.run(directory, List.of(discovery.toolchain().orElseThrow().clang().toString(), "-std=c11", "-O3", "-Wall", "-Wextra", "-Werror",
                source.toString(), "-o", executable.toString()), "runtime-hash-build");
        var command = new ArrayList<>(List.of(executable.toString()));
        for (long address : addresses) command.add(Long.toUnsignedString(address, 16));
        return BridgeEntryTests.run(directory, command, "runtime-hash").lines().mapToInt(value -> (int) Long.parseLong(value)).toArray();
    }

    private static CompilationArtifact analyze(String source) {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Item.iron", source)));
        check(artifact.valid(), artifact.diagnostics().toString()); return artifact;
    }

    private static BridgeObjectAdmission admit(CompilationArtifact artifact) {
        var proof = BridgeObjectAdmission.prove(artifact, List.of("permanentjava"));
        check(proof.contract().isPresent(), proof.reason()); return proof.contract().orElseThrow();
    }

    private static BridgeGeneration generation(CompilationArtifact artifact, BridgeObjectAdmission admission) {
        return BridgeGeneration.createObjects("permanent.jar", artifact, admission, "test", "1".repeat(64), "2".repeat(64));
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
