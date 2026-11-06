// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.backend.LlvmEmitter;
import ironwood.compiler.bridge.*;
import ironwood.compiler.backend.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

final class Gc6ManagedBridgeTests {
    static final String NAME = "GC6 managed entries preserve aliases publication and source lifetime member names";
    private Gc6ManagedBridgeTests() {}

    static CompilationArtifact artifact() {
        return new CompilerPipeline().analyzeForBridge(List.of(SourceFile.of("Counter.iron", """
                package gc6objects;
                public final class Counter {

                    private static Counter saved;

                    private Counter child;

                    private int number;

                    private String label;

                    public Counter(int value) {

                        this.number = value;

                        if (value < 0) {
                            saved = this;
                            throw new IllegalArgumentException();
                        }
                    }

                    public Counter value() {

                        return this.child;
                    }

                    public Counter retain(Counter other) {

                        this.child = other;
                        return this;
                    }

                    public void free() {

                        this.child = null;
                    }

                    public void close() {

                        ironwood.lang.Memory.collect();
                    }

                    public static Counter construct() {

                        return saved;
                    }

                    public int number() {

                        return this.number;
                    }

                    public long scalar(long value) {

                        return value + this.number;
                    }

                    public long failure(boolean fail) {

                        if (fail) throw new IllegalArgumentException();
                        return this.number;
                    }

                    public void fault(int code) throws Fault {

                        defer this.close();
                        try {
                            throw new Fault(code);
                        } finally {
                            ironwood.lang.Memory.collect();
                        }
                    }

                    public static void clear() {

                        saved = null;
                        ironwood.lang.Memory.collect();
                    }

                    public static long live() {

                        return System.liveAllocationCount();
                    }

                    public static long total() {

                        return System.allocationCount();
                    }

                    public String text(String value) {

                        this.label = value;
                        ironwood.lang.Memory.collect();
                        return value;
                    }

                    public String text() {

                        ironwood.lang.Memory.collect();
                        return this.label;
                    }

                    public int[] copy(int[] values, boolean fail) {

                        values[0] += this.number;
                        ironwood.lang.Memory.collect();
                        if (fail) throw new IllegalArgumentException();
                        return values;
                    }

                    public static int borrow(ironwood.bridge.ByteView view, int index, byte value, boolean write) {

                        ironwood.lang.Memory.collect();
                        if (write) view.put(index, value);
                        return view.get(index);
                    }

                    public static boolean absent(ironwood.bridge.ByteView view) {

                        ironwood.lang.Memory.collect();
                        return null == view;
                    }

                    public static boolean same(ironwood.bridge.ByteView first, ironwood.bridge.ByteView second) {

                        ironwood.lang.Memory.collect();
                        return first == second;
                    }

                    public enum Kind {

                        ALPHA, BETA;

                        public Counter create(int value) {

                            ironwood.lang.Memory.collect();
                            return new Counter(value);
                        }

                        public Kind pick(boolean present) {

                            ironwood.lang.Memory.collect();
                            return present ? this : null;
                        }
                    }

                    public static final class Fault extends RuntimeException {

                        private final int code;

                        public Fault(int code) {

                            super("snapshot");
                            this.code = code;
                        }

                        public int getCode() {

                            ironwood.lang.Memory.collect();
                            return this.code;
                        }

                        public String getLabel() {

                            ironwood.lang.Memory.collect();
                            return "code=" + this.code;
                        }
                    }
                }
                """), SourceFile.of("Box.iron", """
                package gc6objects;
                public final class Box<T> {

                    private T value;

                    private Box(T value) {

                        this.value = value;
                    }

                    public static Box<Counter> counter(Counter value) {

                        return new Box<Counter>(value);
                    }

                    public static Box<Trade> trade(long value) {

                        return new Box<Trade>(new Trade(value));
                    }

                    public T get() {

                        ironwood.lang.Memory.collect();
                        return this.value;
                    }
                }
                """), SourceFile.of("Trade.iron", """
                package gc6objects;
                public final class Trade {

                    private final long number;

                    public Trade(long value) {

                        this.number = value;
                    }

                    public long number() {

                        return this.number;
                    }
                }
                """), SourceFile.of("Bound.iron", """
                package gc6objects;
                public final class Bound<T extends Counter> {

                    private T value;

                    public Bound(T value) {

                        this.value = value;
                    }

                    public T get() {

                        ironwood.lang.Memory.collect();
                        return this.value;
                    }

                    public Bound<T> self() {

                        return this;
                    }
                }
                """), SourceFile.of("Resource.iron", """
                package gc6objects;
                public final class Resource {

                    private static Resource saved;
                    private final ironwood.io.FileOutputStream stream;

                    public Resource(String path) throws ironwood.io.IOException {

                        this.stream = new ironwood.io.FileOutputStream(path);
                        saved = this;
                    }

                    public void write(int value) throws ironwood.io.IOException {

                        ironwood.lang.Memory.collect();
                        this.stream.write(value);
                        ironwood.lang.Memory.collect();
                    }

                    public void close() throws ironwood.io.IOException {

                        this.stream.close();
                    }

                    public static Resource saved() {

                        ironwood.lang.Memory.collect();
                        return saved;
                    }

                    public static void clearSaved() {

                        saved = null;
                        ironwood.lang.Memory.collect();
                    }
                }
                """)));
    }

    static void proofs() throws Exception {
        var artifact = artifact();
        check(artifact.valid(), artifact.diagnostics().toString());
        var result = BridgeObjectAdmission.proveManaged(artifact, List.of("gc6objects"));
        check(result.status() == BridgeProof.Status.PROVED, result.reason());
        var admission = result.contract().orElseThrow();
        check(admission.matches(artifact, admission.surface()), "admission lost source binding");
        String llvm = new LlvmEmitter().emit(admission);
        check(llvm.contains("@iw_gc_root_abi = hidden constant i32 3") && llvm.contains("@ironwood_gc_start_roots"),
                "managed library omitted root/owner startup");
        check(llvm.contains("@iw_gc_bridge_boundary") && llvm.contains("gc \"statepoint-example\""), "missing native entry metadata");
        var generation = BridgeGeneration.createObjects("gc6objects", artifact, admission, "test", "a".repeat(64), "b".repeat(64));
        var java = BridgeFacadeSources.generateRoots(artifact, admission, generation);
        String facade = java.declarations().sources().get("gc6objects/Counter.java");
        String roots = java.declarations().sources().get(generation.supportPackage().replace('.', '/') + "/Roots.java");
        check(!facade.contains("public Counter(") && !facade.contains("implements AutoCloseable"), "facade owns reclamation");
        check(roots.contains("construct_6763366f626a656374732e436f756e746572"), "constructor tag differs from UTF-8 contract");
        var nativeSources = BridgeFacadeNativeSources.generateRoots(artifact, admission, generation, java);
        var build = generation.nativeBuild("macos-arm64", syntheticBuildInputs());
        String combined = nativeSources.source() + BridgeBootstrapSources.generate(generation, build, java.declarations(), nativeSources);
        check(BridgeGcTransitions.compose(admission, combined).contains("iw_gc_segment_suspend"), "JNI execution omitted segment capture");
        var program = admission.program();
        var rename = new IrCfgRenamer(v -> v.type().equals(IrType.reference("ironwood.lang.Throwable"))
                ? new IrValueReference(v.id(), IrType.EXCEPTION, v.sourceSpan()) : v, label -> label);
        var entries = admission.entries().primaryEntries().stream().map(e -> e.function().linkageName()).toList();
        var functions = program.functions().stream().map(f -> !entries.contains(f.linkageName()) ? f : new IrFunction(f.ownerClass(), f.sourceName(),
                f.linkageName(), f.returnType(), f.parameters(), f.blocks().stream().map(rename::block).toList(), f.sourceSpan(), f.sourceFileName(), f.kind())).toList();
        var invalid = new IrProgram(program.moduleName(), program.classes(), program.staticFields(), program.typeInitializations(), program.arrayTypes(),
                program.stringConstants(), program.dispatchSlots(), functions, program.entryPoint(), program.allocationFailure(), program.exportRoots());
        try { GcManagedHeapVerifier.verifyBridge(invalid); throw new AssertionError("opaque managed exception accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("typed managed exception"), expected.getMessage()); }
        var borrowed = admission.surface().roots().roots().stream().map(BridgeRootSet.Root::callable).filter(c -> c.name().equals("borrow")).findFirst().orElseThrow();
        check(ironwood.compiler.semantic.BridgeByteViews.analyzeManaged(artifact, borrowed).status() == BridgeProof.Status.PROVED,
                "collecting view borrow refused");
        check(ironwood.compiler.semantic.BridgeByteViews.analyze(artifact, borrowed).status() == BridgeProof.Status.PROVED,
                "borrowed view analysis lost the managed collection contract");
        String viewHeader = llvm.lines().filter(line -> line.startsWith("define ") && line.contains("@\"" + borrowed.linkage() + "\"(")).findFirst().orElseThrow();
        check(viewHeader.contains("(ptr ") && !viewHeader.contains("ptr addrspace(1)"), "borrowed descriptor used a managed address space");
        for (String member : List.of("private static ironwood.bridge.ByteView saved; public static void save(ironwood.bridge.ByteView view) { saved = view; }",
                "private static int[] saved; public static void save(int[] array) { saved = array; ironwood.lang.Memory.collect(); }")) {
            var escaped = new CompilerPipeline().analyzeForBridge(List.of(SourceFile.of("Escaped.iron",
                    "package gc6escape; public final class Escaped { private Escaped() {} " + member + " }")));
            check(!escaped.valid() || BridgeObjectAdmission.proveManaged(escaped, List.of("gc6escape")).status() != BridgeProof.Status.PROVED,
                    "foreign conversion input escaped its supported lifetime");
        }
        Path directory = Files.createTempDirectory(Path.of("workspace"), "gc6-object-inputs-");
        var units = admission.surface().types().stream().filter(type -> type.enclosingType().isEmpty()).toList();
        var loose = new java.util.ArrayList<Path>();
        for (var type : units) {
            Path file = directory.resolve(type.binaryName().substring(type.binaryName().lastIndexOf('.') + 1) + ".ironclass");
            IronClass.write(file, SourceParser.parse(type.source()).unit().orElseThrow(), type.binaryName()); loose.add(file);
        }
        Path archive = directory.resolve("counter.ironjar"); IronJar.create(archive, loose);
        var classSources = new java.util.ArrayList<SourceFile>(); var archiveSources = new java.util.ArrayList<SourceFile>();
        for (int i = 0; i < units.size(); i++) {
            classSources.add(IronClass.read(loose.get(i)).source(units.get(i).binaryName()).orElseThrow());
            archiveSources.add(IronJar.read(archive).source(units.get(i).binaryName()).orElseThrow());
        }
        for (var restored : List.of(classSources, archiveSources)) {
            var reconstructed = new CompilerPipeline().analyzeForBridge(restored);
            check(reconstructed.valid(), reconstructed.diagnostics().toString());
            var restoredProof = BridgeObjectAdmission.proveManaged(reconstructed, List.of("gc6objects"));
            check(restoredProof.status() == BridgeProof.Status.PROVED, restoredProof.reason());
            var restoredAdmission = restoredProof.contract().orElseThrow();
            var restoredGeneration = BridgeGeneration.createObjects("gc6objects", reconstructed, restoredAdmission, "test", "a".repeat(64), "b".repeat(64));
            check(restoredGeneration.identity().equals(generation.identity()), "generation changed after source reconstruction");
            var restoredSources = BridgeFacadeSources.generateRoots(reconstructed, restoredAdmission, restoredGeneration).declarations().sources();
            if (!restoredSources.equals(java.declarations().sources())) {
                for (var entry : restoredSources.entrySet()) {
                    Path file = directory.resolve("restored").resolve(entry.getKey()); Files.createDirectories(file.getParent()); Files.writeString(file, entry.getValue());
                }
                for (var entry : java.declarations().sources().entrySet()) {
                    Path file = directory.resolve("expected").resolve(entry.getKey()); Files.createDirectories(file.getParent()); Files.writeString(file, entry.getValue());
                }
                throw new AssertionError("lifetime projection changed after source reconstruction: " + directory);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        var artifact = artifact();
        var proof = BridgeObjectAdmission.proveManaged(artifact, List.of("gc6objects"));
        check(proof.status() == BridgeProof.Status.PROVED, proof.reason());
        var admission = proof.contract().orElseThrow();
        var inputs = BridgeProducerInputs.discover();
        var generation = BridgeGeneration.createObjects("gc6objects", artifact, admission,
                inputs.compilerVersion(), inputs.compilerIdentity(), inputs.runtimeIdentity(), args.length > 3 && args[3].equals("critical"));
        var java = BridgeFacadeSources.generateRoots(artifact, admission, generation);
        var nativeSources = BridgeFacadeNativeSources.generateRoots(artifact, admission, generation, java);
        var level = OptimizationLevel.valueOf(args[2]);
        var tools = LlvmToolchain.discover(Path.of(args[1])); check(tools.successful(), tools.error());
        var buildInputs = new java.util.TreeMap<>(BridgeGcBuildInputs.discover(tools.toolchain().orElseThrow()));
        buildInputs.putAll(Map.of("fixture", "managed-objects", "optimization", level.toString()));
        var build = generation.nativeBuild(ManagedBridgeTestBuild.target(), buildInputs);
        var out = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(out);
        var llvm = out.resolve("program.ll"); Files.writeString(llvm, new LlvmEmitter().emit(admission));
        var source = BridgeGcTransitions.compose(admission, nativeSources.source() + BridgeBootstrapSources.generate(generation, build, java.declarations(), nativeSources));
        ManagedBridgeTestBuild.build(out, llvm, tools.toolchain().orElseThrow(), level, generation, build, java.declarations(), source, Map.of(), admission);
        Files.writeString(out.resolve("support.txt"), generation.supportPackage());
    }

    /** Projection-only unit input; never linked or delivered to a JVM consumer. */
    static Map<String, String> syntheticBuildInputs() {
        var inputs = new java.util.TreeMap<>(BridgeGeneration.managedContract());
        for (String key : List.of("gc.config.sha256", "gc.sdk.manifest.sha256", "gc.archive.sha256", "gc.adapter.sha256", "gc.root-reader.sha256"))
            inputs.put(key, "c".repeat(64));
        inputs.put("llvm.stackmap.version", "3"); inputs.put("gc.isolation", "private-per-image");
        return Map.copyOf(inputs);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
