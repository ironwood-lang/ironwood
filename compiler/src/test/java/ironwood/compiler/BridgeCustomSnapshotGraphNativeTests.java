// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class BridgeCustomSnapshotGraphNativeTests {
    static final String NAME = "Java Bridge custom native graphs preserve inherited data and reject unrepresentable edges";
    private BridgeCustomSnapshotGraphNativeTests() {}

    static void graphs() throws Exception {
        Path base = Path.of("workspace/java-bridge/evidence/p3b/custom-native-graphs").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-"); Files.writeString(directory.resolve("Cases.iron"), SOURCE);
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Cases.iron", SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var proof = BridgeObjectAdmission.prove(artifact, List.of("customgraph")); check(proof.contract().isPresent(), proof.reason());
        var admission = proof.contract().orElseThrow();
        var producer = BridgeProducerInputs.discover();
        var generation = BridgeGeneration.createObjects("custom-graphs.jar", artifact, admission,
                producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
        var java = BridgePermanentJavaSources.generate(artifact, admission, generation);
        var nativeSources = BridgePermanentNativeSources.generate(artifact, admission, generation, java);
        String llvm = new LlvmEmitter().emit(admission.program()); Path program = directory.resolve("program.ll"); Files.writeString(program, llvm);
        Files.writeString(directory.resolve("identity.txt"), "generation=" + generation.identity() + "\nllvm=" + digest(llvm)
                + "\nadapters=" + digest(nativeSources.source()) + "\ncompiler=" + producer.compilerIdentity() + "\nruntime=" + producer.runtimeIdentity() + "\n");
        var discovery = LlvmToolchain.discover(null); check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow(); Path javaHome = Path.of(System.getProperty("java.home"));
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path folder = directory.resolve(level.toString());
            var build = generation.nativeBuild("macos-arm64", Map.of("fixture", "custom-native-graphs", "llvm", digest(llvm),
                    "adapters", digest(nativeSources.source()), "optimization", level.toString()));
            Path jar = BridgeGeneratedJarTests.build(folder, program, toolchain, level, generation, build, java.declarations(),
                    nativeSources.source() + BridgeBootstrapSources.generate(generation, build, java.declarations(), nativeSources), Map.of());
            Path consumer = folder.resolve("CustomGraphConsumer.java"); Files.writeString(consumer, CONSUMER);
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                    "-cp", jar.toString(), consumer.toString()), "consumer-javac");
            String output = BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-Xmx64m", "-cp",
                    jar + System.getProperty("path.separator") + folder, "CustomGraphConsumer"), "consumer");
            check(output.equals("custom-native-graphs-ok\n"), output);
        }
        System.out.println("custom native graph evidence: " + directory);
    }

    private static String digest(String text) { return BridgeGeneration.bytesDigest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final String SOURCE = """
            package customgraph;
            public final class Cases {
                private static PathProblem path = new PathProblem();
                private static ParseProblem parse = new ParseProblem();
                private static FileProblem file = new FileProblem();
                private static TransferProblem transfer = new TransferProblem();
                private static IoProblem io = new IoProblem();
                private static ClosedProblem closed = new ClosedProblem();
                private static Node left = new Node(0);
                private static Node right = new Node(1);
                private static Node[] chain = makeChain();
                private static Wide wide = new Wide();
                private static int mode;
                private static int parsed;
                private Cases() {}
                private static Node[] makeChain() {
                    Node[] result = new Node[33];
                    for (int i = 0; i < result.length; i++) result[i] = new Node(i);
                    return result;
                }
                public static int parsed() { return parsed; }
                public static long live() { return System.liveAllocationCount(); }
                public static void fail(int kind) throws Exception {
                    if (kind == 0) throw path;
                    if (kind == 1) throw parse;
                    if (kind == 2) throw file;
                    if (kind == 3) throw transfer;
                    if (kind == 4) throw io;
                    if (kind == 5) throw closed;
                    if (kind == 9) secondary(35);
                    mode = kind - 6;
                    throw mode == 2 ? chain[0] : left;
                }
                private static void secondary(int depth) {
                    if (depth == 0) throw wide;
                    try { secondary(depth - 1); } finally { throw closed; }
                }
                public static final class PathProblem extends ironwood.nio.file.InvalidPathException {
                    public PathProblem() { super("input", "reason", 2); }
                    @Override public String getInput() { return null; }
                    @Override public int getIndex() { return -99; }
                }
                public static final class ParseProblem extends ironwood.time.format.DateTimeParseException {
                    public ParseProblem() { super("parse message", "input", 3); }
                    @Override public String getParsedString() { Cases.parsed++; return new String("copied parse"); }
                    @Override public int getErrorIndex() { return -99; }
                }
                public static final class FileProblem extends ironwood.nio.file.FileSystemException {
                    public FileProblem() { super("file", "other", "why"); }
                }
                public static final class TransferProblem extends ironwood.io.InterruptedIOException {
                    public TransferProblem() { super("transfer"); bytesTransferred = 73; }
                }
                public static final class IoProblem extends ironwood.io.UncheckedIOException {
                    public IoProblem() { super("io", new ironwood.io.IOException("ignored cause")); }
                    @Override public ironwood.io.IOException getCause() { return null; }
                }
                public static final class ClosedProblem extends ironwood.nio.file.ClosedDirectoryStreamException {
                    public ClosedProblem() {}
                    @Override public String getMessage() { return "closed message"; }
                }
                public static final class Node extends RuntimeException {
                    private final int code;
                    public Node(int code) { this.code = code; }
                    public int getCode() { return code; }
                    @Override public Node getCause() {
                        if (Cases.mode == 1) return this;
                        if (Cases.mode == 2) return code == 32 ? null : Cases.chain[code + 1];
                        return code == 0 ? Cases.right : Cases.left;
                    }
                }
                public static final class Wide extends RuntimeException {
                    public Wide() {}
                    @Override public Throwable getCause() { return this; }
                }
            }
            """;
    private static final String CONSUMER = """
            import customgraph.Cases;
            public final class CustomGraphConsumer {
                private static Exception caught(int kind) throws Exception {
                    try { Cases.fail(kind); throw new AssertionError("missing native exception"); }
                    catch (Exception value) { return value; }
                }
                public static void main(String[] args) throws Exception {
                    long live = Cases.live();
                    var path = (Cases.PathProblem) caught(0);
                    check(path instanceof java.nio.file.InvalidPathException && path.getInput() == null && path.getIndex() == -99 && path.getReason().equals("reason"));
                    var parse = (Cases.ParseProblem) caught(1);
                    check(parse.getParsedString().equals("copied parse") && parse.getErrorIndex() == -99 && parse.getMessage().equals("parse message"));
                    check(Cases.parsed() == 1);
                    var file = (Cases.FileProblem) caught(2);
                    check(file.getFile().equals("file") && file.getOtherFile().equals("other") && file.getReason().equals("why"));
                    check(file.getMessage().equals("file -> other: why"));
                    check(((Cases.TransferProblem) caught(3)).bytesTransferred == 73);
                    check(((Cases.IoProblem) caught(4)).getCause() == null);
                    check(caught(5).getMessage().equals("closed message"));
                    var node = (Cases.Node) caught(6);
                    check(node.getCode() == 0 && node.getCause().getCode() == 1 && node.getCause().getCause() == node);
                    check(node.getSuppressed().length == 0);
                    for (int kind : new int[]{7, 8}) {
                        try { Cases.fail(kind); throw new AssertionError("unrepresentable covariant edge admitted"); }
                        catch (LinkageError expected) { check(expected.getMessage().contains("snapshot graph")); }
                    }
                    var wide = (Cases.Wide) caught(9);
                    check(wide.getCause() instanceof java.io.IOException && wide.getCause().getMessage().contains("copy limit"));
                    check(wide.getSuppressed().length == 32 && wide.getSuppressed()[31] == wide.getCause());
                    for (int i = 0; i < 31; i++) check(wide.getSuppressed()[i] instanceof Cases.ClosedProblem
                            && wide.getSuppressed()[i] == wide.getSuppressed()[0]);
                    check(((Cases.Node) caught(6)).getCause().getCause().getCode() == 0);
                    check(Cases.live() == live && Cases.parsed() == 1 && parse.getParsedString().equals("copied parse"));
                    System.out.println("custom-native-graphs-ok");
                }
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
            }
            """;
}
