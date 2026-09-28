// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.BridgeObjectAdmission;
import ironwood.compiler.CompilerPipeline;
import ironwood.compiler.UnfreedMode;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

public final class BridgeCallbackActiveUseTests {
    public static final String NAME = "Java Bridge active callback guards balance aliases failures and refused frees without allocation";

    private BridgeCallbackActiveUseTests() {}

    public static void guards() throws Exception {
        var artifact = new CompilerPipeline(UnfreedMode.ERROR).analyzeForBridge(List.of(SourceFile.of("Cell.iron", """
                package guardfixture;
                public final class Cell { public Cell() {} public long value() { return 1L; } }
                """)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var proof = BridgeObjectAdmission.prove(artifact, List.of("guardfixture"));
        check(proof.contract().isPresent(), proof.reason());
        var admission = proof.contract().orElseThrow();
        var generation = BridgeGeneration.createObjects("guard-test.jar", artifact, admission, "test", "1".repeat(64), "2".repeat(64));
        var ordinary = BridgeRootStateSources.generate(artifact, admission, generation);
        String prefix = generation.supportPackage().replace('.', '/') + "/";
        check(!ordinary.sources().get(prefix + "RootState.java").contains("activeUses")
                && !ordinary.sources().get(prefix + "RootState.java").contains("CallbackUse"),
                "callback-free production state acquired callback bookkeeping");
        var sources = new TreeMap<>(ordinary.sources());
        // Component qualification only. Public callback admission still rejects
        // this shape; these sources do not constitute an admitted producer JAR.
        sources.put(prefix + "RootState.java", BridgeRootStateSources.state(ordinary.slotCapacity(), true)
                .replace("@PACKAGE@", generation.supportPackage()).replace("@GENERATION@", generation.identity()));
        sources.put(prefix + "Identity.java", "package " + generation.supportPackage() + "; @interface Identity { String value(); }");
        sources.put(prefix + "GuardConsumer.java", "package " + generation.supportPackage() + ";\n"
                + CONSUMER.replace("@BODY@", BridgeCallbackGuardSources.wrap("callback.run();", List.of("first", "second"))));
        try {
            BridgeCallbackGuardSources.wrap("return;", List.of("owner.state()"));
            throw new AssertionError("mutable guard expression accepted");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("stable root-state locals"), expected.toString());
        }
        Path evidence = Path.of("workspace/java-bridge/evidence/p5/active-use").toAbsolutePath();
        Files.createDirectories(evidence);
        Path directory = Files.createTempDirectory(evidence, "run-");
        Path jdk = Path.of(System.getProperty("java.home"));
        var command = new ArrayList<>(List.of(jdk.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                "-d", directory.resolve("classes").toString()));
        for (var source : sources.entrySet()) {
            Path path = directory.resolve("sources").resolve(source.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, source.getValue());
            command.add(path.toString());
        }
        run(directory, command, "javac");
        String result = run(directory, List.of(jdk.resolve("bin/java").toString(), "-XX:-DoEscapeAnalysis", "-cp",
                directory.resolve("classes").toString(), generation.supportPackage() + ".GuardConsumer"), "consumer");
        check(result.equals("active-use-ok:500000:0\n"), result);
        System.out.println("callback active-use component evidence: " + directory);
    }

    private static String run(Path directory, List<String> command, String name) throws Exception {
        Path log = directory.resolve(name + ".log");
        Files.writeString(directory.resolve(name + ".command.txt"), String.join("\n", command) + "\n");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("guard child timed out");
        }
        String output = Files.readString(log);
        check(process.exitValue() == 0, output);
        return output;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final String CONSUMER = """
            import java.lang.reflect.Field;
            public final class GuardConsumer {
                private static long destroyed;
                private static volatile long sink;
                private static void invoke(RootState first, RootState second, Runnable callback) { @BODY@ }
                private static Field field(String name) throws Exception {
                    Field field = RootState.class.getDeclaredField(name); field.setAccessible(true); return field;
                }
                private static void free(RootState root) { if (root.prepareFree(root.address())) destroyed++; }
                private static void refused(Runnable action) {
                    long before = destroyed;
                    try { action.run(); throw new AssertionError("missing lifetime refusal"); }
                    catch (IllegalStateException expected) { check(expected.getClass() == BridgeLifetimeException.class); }
                    check(destroyed == before);
                }
                public static void main(String[] args) throws Exception {
                    Field address = field("address"), active = field("activeUses"), status = field("status");
                    RootState first = new RootState(), second = new RootState(), idle = new RootState();
                    address.setLong(first, 1000L); address.setLong(second, 2000L); address.setLong(idle, 3000L);
                    invoke(first, second, () -> {
                        refused(() -> free(first)); refused(() -> free(second));
                        check(idle.prepareFree(3000L));
                        invoke(first, first, () -> { refused(() -> free(first)); refused(() -> free(second)); });
                        refused(() -> free(first));
                    });
                    check(active.getLong(first) == 0 && active.getLong(second) == 0 && first.prepareFree(1000L));
                    RuntimeException failure = new RuntimeException("callback");
                    try { invoke(first, first, () -> { throw failure; }); throw new AssertionError(); }
                    catch (RuntimeException caught) { check(caught == failure); }
                    check(active.getLong(first) == 0);
                    status.setInt(second, 2);
                    refused(() -> invoke(first, second, () -> { throw new AssertionError("dead argument ran"); }));
                    check(active.getLong(first) == 0 && active.getLong(second) == 0);
                    status.setInt(second, 0); active.setLong(second, Long.MAX_VALUE);
                    refused(() -> invoke(first, second, () -> { throw new AssertionError("overflow ran"); }));
                    check(active.getLong(first) == 0 && active.getLong(second) == Long.MAX_VALUE);
                    active.setLong(second, 0);
                    invoke(null, first, () -> refused(() -> free(first)));
                    check(active.getLong(first) == 0);
                    Runnable callback = () -> sink++;
                    for (int index = 0; index < 100000; index++) invoke(first, first, callback);
                    var counter = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
                    check(counter.isThreadAllocatedMemorySupported()); counter.setThreadAllocatedMemoryEnabled(true);
                    long thread = Thread.currentThread().threadId();
                    for (int index = 0; index < 10000; index++) counter.getThreadAllocatedBytes(thread);
                    long before = counter.getThreadAllocatedBytes(thread);
                    for (int index = 0; index < 500000; index++) invoke(first, first, callback);
                    long bytes = counter.getThreadAllocatedBytes(thread) - before;
                    check(bytes == 0 && active.getLong(first) == 0 && destroyed == 0);
                    System.out.println("active-use-ok:500000:" + bytes);
                }
                private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
            }
            """;
}
