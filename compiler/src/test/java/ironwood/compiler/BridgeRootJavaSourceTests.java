// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class BridgeRootJavaSourceTests {
    static final String NAME = "Java Bridge root Java facades preserve private lifetime metadata and bounded ABI";
    static final String SOURCE = """
            package rootjava;
            public final class Root {
                public static final int $ironwood$state = 3;
                private final Child child = new Child();
                private final View view = new View();
                public Root() {}
                destructor { free child; free view; }
                public int value() { return 17; }
                public Root self() { return this; }
                public Child child() { return child; }
                public View view() { return view; }
                public static Root fresh(boolean absent) { return absent ? null : new Root(); }
                public static Root pick(Root left, Root right, boolean choose) { return choose ? left : right; }
                public static final class Child {
                    public Child() {}
                    public int value() { return 19; }
                    public Child self() { return this; }
                }
                public static final class View { private View() {} public int value() { return 23; } }
                public static final class Catalog {
                    private static Catalog saved;
                    public Catalog() {}
                    public Catalog publish() { saved = this; return this; }
                }
                public enum Mode {
                    ONLY;
                    public Root create() { return new Root(); }
                    public static Root fresh() { return new Root(); }
                }
            }
            """;
    private BridgeRootJavaSourceTests() {}

    static void declarations() throws Exception {
        var artifact = analyze(SOURCE, "Root.iron"); var admission = admit(artifact, "rootjava");
        var generation = BridgeGeneration.createObjects("root-java.jar", artifact, admission, "test", "1".repeat(64), "2".repeat(64));
        var projected = BridgePermanentJavaSources.generateRoots(artifact, admission, generation);
        var declarations = projected.declarations();
        check(projected.facades().size() == 4 && projected.facades().stream().filter(BridgePermanentJavaSources.Facade::rooted).count() == 3,
                "root/view/permanent classification changed");
        check(declarations.rootDestructions().size() == 2 && declarations.facadeRegistrations().size() == 1, "lifetime helpers were omitted or fabricated");
        check(projected.facades().stream().filter(facade -> facade.binaryName().equals("rootjava.Root"))
                .noneMatch(facade -> facade.stateField().equals("$ironwood$state")), "source field collided with root state");
        try { BridgePermanentJavaSources.generateRoots(analyze(SOURCE.replace("return 17", "return 18"), "Root.iron"), admission, generation);
            throw new AssertionError("stale root Java admission accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("matching final root admission"), expected.getMessage()); }
        var retainedArtifact = analyze(BridgeMixedLifetimeTests.SOURCE, "Holder.iron"); var retained = admit(retainedArtifact, "mixedlife");
        var retainedGeneration = BridgeGeneration.createObjects("retained.jar", retainedArtifact, retained, "test", "1".repeat(64), "2".repeat(64));
        try { BridgePermanentJavaSources.generateRoots(retainedArtifact, retained, retainedGeneration); throw new AssertionError("pending retention admitted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("retention slots"), expected.getMessage()); }
        var build = generation.nativeBuild("macos-arm64", java.util.Map.of("fixture", "root-declarations"));
        try { BridgeBootstrapSources.generate(generation, build, declarations, new BridgeValueNativeSources("", List.of()));
            throw new AssertionError("value bootstrap accepted root destruction"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("object helpers"), expected.getMessage()); }
        var manifest = new java.util.Properties();
        manifest.load(new java.io.ByteArrayInputStream(BridgePackageManifest.create(generation, build, declarations,
                new BridgeMacPayload("14.0", "26.0", List.of()), "fixture.dylib", java.util.Map.of("fixture.dylib", new byte[]{1}))));
        String loader = BridgeLoaderSources.generate(generation, declarations, new BridgeLoaderSources.Payload(build, "14.0", "3".repeat(64)));
        for (int index = 0; index < declarations.rootDestructions().size(); index++) {
            var binding = declarations.rootDestructions().get(index); String prefix = "java.root.destruction." + index;
            check(binding.binaryName().equals(manifest.getProperty(prefix + ".type")) && binding.descriptor().equals(manifest.getProperty(prefix + ".descriptor"))
                    && !manifest.containsKey(prefix + ".entry") && loader.contains(BridgeJavaSources.quote(binding.descriptor())), "root helper inventory changed");
        }
        Path base = Path.of("workspace/java-bridge/evidence/p3c/root-java").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-"), classes = directory.resolve("classes");
        var files = new ArrayList<String>();
        for (var entry : declarations.sources().entrySet()) {
            Path file = directory.resolve("sources").resolve(entry.getKey()); Files.createDirectories(file.getParent()); Files.writeString(file, entry.getValue()); files.add(file.toString());
        }
        Path stub = directory.resolve("sources").resolve(generation.supportPackage().replace('.', '/') + "/Support.java");
        Files.writeString(stub, "package " + generation.supportPackage() + "; public final class Support { public static void " + declarations.ensureMethod() + "() {} }");
        files.add(stub.toString());
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-d", classes.toString()));
        command.addAll(files); BridgeEntryTests.run(directory, command, "javac");
        try (var classesLoader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, null)) {
            var stateType = Class.forName(generation.supportPackage() + ".RootState", true, classesLoader);
            var refusalType = Class.forName(generation.supportPackage() + ".BridgeLifetimeException", false, classesLoader);
            var address = stateType.getDeclaredField("address"); address.setAccessible(true);
            var status = stateType.getDeclaredField("status"); status.setAccessible(true);
            var incoming = stateType.getDeclaredField("incoming"); incoming.setAccessible(true);
            Set<String> natives = new HashSet<>();
            for (String name : declarations.generatedTypes()) {
                var type = Class.forName(name, false, classesLoader);
                for (var method : type.getDeclaredMethods()) if (Modifier.isNative(method.getModifiers())) {
                    check(Modifier.isPrivate(method.getModifiers()) && Modifier.isStatic(method.getModifiers()), "public native handle exposed");
                    natives.add(name + "." + method.getName() + MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString());
                }
            }
            check(natives.equals(declarations.nativeDeclarations().stream().map(binding -> binding.binaryName() + "." + binding.nativeName() + binding.descriptor())
                    .collect(java.util.stream.Collectors.toSet())), "native ABI inventory mismatch");
            for (var facade : projected.facades()) {
                var type = Class.forName(facade.binaryName(), true, classesLoader);
                check(Modifier.isFinal(type.getModifiers()), "subclassable root facade");
                for (var field : type.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())) {
                    check(Modifier.isPrivate(field.getModifiers()) && Modifier.isFinal(field.getModifiers()), "mutable or public facade metadata");
                }
                if (!facade.rooted()) { check(java.util.Arrays.stream(type.getDeclaredMethods()).noneMatch(method -> method.getName().equals("free")), "permanent gained free"); continue; }
                var ctor = type.getDeclaredConstructor(long.class, stateType, Void.class); ctor.setAccessible(true);
                check(Modifier.isPrivate(ctor.getModifiers()) && MethodType.methodType(void.class, ctor.getParameterTypes()).toMethodDescriptorString()
                        .equals(facade.constructorDescriptor()), "raw constructor mismatch");
                Object state = stateType.getConstructor().newInstance(); address.setLong(state, 1000);
                Object owner = ctor.newInstance(1000L, state, null), borrowed = ctor.newInstance(2000L, state, null);
                int hash = owner.hashCode(); String text = owner.toString();
                check(owner.equals(owner) && !owner.equals(borrowed), "root identity changed");
                boolean canOwn = declarations.rootDestructions().stream().anyMatch(binding -> binding.binaryName().equals(facade.binaryName()));
                if (canOwn) {
                    expect(refusalType, () -> type.getMethod("free").invoke(borrowed));
                    incoming.setLong(state, 1); expect(refusalType, () -> type.getMethod("free").invoke(owner)); incoming.setLong(state, 0);
                    expect(UnsatisfiedLinkError.class, () -> type.getMethod("free").invoke(owner));
                    check(status.getInt(state) == 0, "Java facade began destruction before native preparation");
                } else check(java.util.Arrays.stream(type.getDeclaredMethods()).noneMatch(method -> method.getName().equals("free")), "borrowed-only type gained free");
                expect(UnsatisfiedLinkError.class, () -> type.getMethod("value").invoke(owner));
                for (int phase : List.of(1, 2)) {
                    status.setInt(state, phase); expect(refusalType, () -> type.getMethod("value").invoke(owner));
                    if (canOwn) {
                        expect(refusalType, () -> type.getMethod("free").invoke(borrowed));
                        if (phase == 1) expect(refusalType, () -> type.getMethod("free").invoke(owner)); else type.getMethod("free").invoke(owner);
                    }
                    check(owner.hashCode() == hash && owner.toString().equals(text) && owner.equals(owner), "identity touched dead native state");
                }
            }
        }
        Files.writeString(directory.resolve("scope.txt"), "Java metadata/refusal component with inert loader and test-supplied state; native adapters remain pending.\n");
        System.out.println("root Java component evidence: " + directory);
    }
    private interface Action { void run() throws Exception; }
    private static void expect(Class<?> expected, Action action) throws Exception {
        try { action.run(); throw new AssertionError("missing " + expected.getName()); }
        catch (InvocationTargetException wrapped) { check(wrapped.getCause().getClass() == expected, wrapped.getCause().toString()); }
    }
    private static CompilationArtifact analyze(String source, String name) {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of(name, source)));
        check(artifact.valid(), artifact.diagnostics().toString()); return artifact;
    }
    private static BridgeObjectAdmission admit(CompilationArtifact artifact, String pkg) {
        var proof = BridgeObjectAdmission.prove(artifact, List.of(pkg)); check(proof.contract().isPresent(), proof.reason()); return proof.contract().orElseThrow();
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
