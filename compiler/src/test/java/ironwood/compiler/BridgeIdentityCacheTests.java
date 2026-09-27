// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeGeneration;
import ironwood.compiler.bridge.BridgeIdentityCacheSources;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class BridgeIdentityCacheTests {
    static final String NAME = "Java Bridge generated permanent cache preserves weak identity and allocation bounds";

    private BridgeIdentityCacheTests() {}

    static void cache() throws Exception {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(
                List.of(SourceFile.of("Holder.iron", BridgeMixedLifetimeTests.SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var proof = BridgeObjectAdmission.prove(artifact, List.of("mixedlife"));
        check(proof.contract().isPresent(), proof.reason());
        var admission = proof.contract().orElseThrow();
        var generation = BridgeGeneration.createObjects("cache.jar", artifact, admission, "test", "1".repeat(64), "2".repeat(64));
        var generated = BridgeIdentityCacheSources.generate(artifact, admission, generation);
        check(generated.types().equals(List.of(generation.supportPackage() + ".PermanentCache",
                generation.supportPackage() + ".PermanentCache$Entry")), "missing cache identity inventory");
        var changed = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Holder.iron",
                BridgeMixedLifetimeTests.SOURCE.replace("return 17;", "return 19;"))));
        try {
            BridgeIdentityCacheSources.generate(changed, admission, generation);
            throw new AssertionError("cache generator accepted stale final admission");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("matching final object admission"), expected.getMessage());
        }
        var rootArtifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Item.iron",
                "package cacheonly; public final class Item { public Item() {} public int number() { return 17; } }")));
        check(rootArtifact.valid(), rootArtifact.diagnostics().toString());
        var rootProof = BridgeObjectAdmission.prove(rootArtifact, List.of("cacheonly"));
        check(rootProof.contract().isPresent(), rootProof.reason());
        var roots = rootProof.contract().orElseThrow();
        var rootGeneration = BridgeGeneration.createObjects("roots.jar", rootArtifact, roots, "test", "1".repeat(64), "2".repeat(64));
        try {
            BridgeIdentityCacheSources.generate(rootArtifact, roots, rootGeneration);
            throw new AssertionError("reclaimable root surface acquired a permanent cache");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("proved permanent concrete facade"), expected.getMessage());
        }
        Path base = Path.of("workspace/java-bridge/evidence/p3b/permanent-cache").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        var javaHome = Path.of(System.getProperty("java.home"));
        String generatedSource = generated.sources().values().iterator().next();
        Files.writeString(directory.resolve("identity.txt"), "generation=" + generation.identity()
                + "\nsource-sha256=" + BridgeGeneration.bytesDigest(generatedSource.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "\njava-runtime=" + System.getProperty("java.runtime.version") + "\njava-home=" + javaHome
                + "\nJava component evidence only; no JNI, facade or jar qualification\n");
        for (boolean faults : List.of(false, true)) {
            Path output = directory.resolve(faults ? "injected" : "production");
            Path sourceDir = output.resolve("sources").resolve(generation.supportPackage().replace('.', '/'));
            Files.createDirectories(sourceDir);
            String cache = generatedSource;
            if (faults) {
                cache = cache.replace("Entry entry = new Entry(address, facade);", "Fault.beforeEntry(); Entry entry = new Entry(address, facade);")
                        .replace("Entry[] expanded = new Entry[buckets.length * 2];", "Fault.beforeGrowth(); Entry[] expanded = new Entry[buckets.length * 2];");
                check(!cache.equals(generatedSource) && cache.contains("Fault.beforeEntry()") && cache.contains("Fault.beforeGrowth()"),
                        "allocation injection anchors changed");
                Files.writeString(sourceDir.resolve("Fault.java"), "package " + generation.supportPackage() + ";\n" + FAULT);
            }
            Files.writeString(sourceDir.resolve("PermanentCache.java"), cache);
            Files.writeString(sourceDir.resolve("Identity.java"), "package " + generation.supportPackage()
                    + "; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) @interface Identity { String value(); }\n");
            Files.writeString(sourceDir.resolve("CacheConsumer.java"), "package " + generation.supportPackage() + ";\n" + (faults ? FAULT_CONSUMER : CONSUMER));
            Files.writeString(output.resolve("scope.txt"), faults ? "test-only entry and growth allocation-failure injection\n" : "unmodified generated cache\n");
            Path classes = output.resolve("classes");
            Files.createDirectories(classes);
            var command = new ArrayList<>(List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-d", classes.toString()));
            try (var files = Files.list(sourceDir)) { files.filter(file -> file.toString().endsWith(".java")).sorted().forEach(file -> command.add(file.toString())); }
            BridgeEntryTests.run(output, command, "javac");
            String result = BridgeEntryTests.run(output, List.of(javaHome.resolve("bin/java").toString(), "-Xmx64m", "-XX:-DoEscapeAnalysis",
                    "-cp", classes.toString(), generation.supportPackage() + ".CacheConsumer"), "consumer");
            check(faults ? result.equals("cache-faults-ok:entry:growth:retry\n")
                    : result.matches("cache-hits:500000:0\\ncache-misses:1000:[0-9]+:[0-9]+\\ncache-ok:identity:growth:collection:recreation:delayed-queue\\n"), result);
        }
        System.out.println("permanent cache component evidence: " + directory);
    }

    private static final String CONSUMER = """
            import java.lang.ref.ReferenceQueue;
            import java.lang.ref.WeakReference;
            import java.lang.management.ManagementFactory;
            import java.lang.reflect.Field;

            public final class CacheConsumer {
                private static final Field SIZE;
                private static final Field BUCKETS;
                private static volatile Object sink;
                static {
                    try {
                        SIZE = PermanentCache.class.getDeclaredField("size"); SIZE.setAccessible(true);
                        BUCKETS = PermanentCache.class.getDeclaredField("buckets"); BUCKETS.setAccessible(true);
                    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                }
                // Same weak-entry field layout for the measured support-object budget.
                private static final class BudgetEntry extends WeakReference<Object> {
                    final long address;
                    BudgetEntry next;
                    BudgetEntry(long address, Object value, ReferenceQueue<Object> queue) {
                        super(value, queue); this.address = address;
                    }
                }
                public static void main(String[] args) throws Exception {
                    Object[] held = new Object[4096];
                    for (int index = 0; index < held.length; index++) {
                        held[index] = new Object();
                        check(PermanentCache.remember(index + 1L, held[index]) == held[index]);
                    }
                    check(size() == held.length && ((Object[]) BUCKETS.get(null)).length >= 8192);
                    for (int pass = 0; pass < 3; pass++) {
                        for (int index = 0; index < 200000; index++) sink = PermanentCache.lookup((index & 4095) + 1L);
                    }
                    var counter = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                    check(counter.isThreadAllocatedMemorySupported());
                    counter.setThreadAllocatedMemoryEnabled(true);
                    long thread = Thread.currentThread().threadId();
                    for (int index = 0; index < 10000; index++) counter.getThreadAllocatedBytes(thread);
                    long before = counter.getThreadAllocatedBytes(thread);
                    for (int index = 0; index < 500000; index++) {
                        Object value = PermanentCache.lookup((index & 4095) + 1L);
                        check(value == held[index & 4095]); sink = value;
                    }
                    long hitBytes = counter.getThreadAllocatedBytes(thread) - before;
                    System.out.println("cache-hits:500000:" + hitBytes); check(hitBytes == 0);
                    Object[] created = new Object[1000];
                    Object[] budget = new Object[1000];
                    ReferenceQueue<Object> queue = new ReferenceQueue<>();
                    for (int index = 0; index < created.length; index++) created[index] = new Object();
                    for (int index = 0; index < 10000; index++) sink = new BudgetEntry(index, held[0], queue);
                    before = counter.getThreadAllocatedBytes(thread);
                    for (int index = 0; index < budget.length; index++) budget[index] = new BudgetEntry(index, held[0], queue);
                    long supportBytes = counter.getThreadAllocatedBytes(thread) - before;
                    before = counter.getThreadAllocatedBytes(thread);
                    for (int index = 0; index < created.length; index++) check(PermanentCache.remember(10000L + index, created[index]) == created[index]);
                    long missBytes = counter.getThreadAllocatedBytes(thread) - before;
                    System.out.println("cache-misses:1000:" + missBytes + ":" + supportBytes);
                    check(missBytes == supportBytes && supportBytes > 0);
                    int baseline = size();
                    for (int pass = 0; pass < 8; pass++) {
                        WeakReference<Object> weak = collectible();
                        for (int attempt = 0; attempt < 200 && weak.get() != null; attempt++) { System.gc(); Thread.sleep(10); }
                        check(weak.get() == null);
                        check(PermanentCache.lookup(-1) == null && size() == baseline);
                    }
                    Object first = new Object();
                    check(PermanentCache.remember(Long.MIN_VALUE, first) == first);
                    WeakReference<?> old = entry(Long.MIN_VALUE);
                    old.clear();
                    Object replacement = new Object();
                    check(PermanentCache.remember(Long.MIN_VALUE, replacement) == replacement);
                    check(old.enqueue());
                    check(PermanentCache.lookup(Long.MIN_VALUE) == replacement && size() == baseline + 1);
                    check(PermanentCache.remember(Long.MIN_VALUE, new Object()) == replacement && size() == baseline + 1);
                    for (int index = 0; index < held.length; index++) check(PermanentCache.lookup(index + 1L) == held[index]);
                    for (int index = 0; index < created.length; index++) check(PermanentCache.lookup(10000L + index) == created[index]);
                    java.lang.ref.Reference.reachabilityFence(budget);
                    System.out.println("cache-ok:identity:growth:collection:recreation:delayed-queue");
                }
                private static WeakReference<Object> collectible() {
                    Object facade = new Object();
                    check(PermanentCache.remember(-1, facade) == facade);
                    return new WeakReference<>(facade);
                }
                private static WeakReference<?> entry(long address) throws Exception {
                    for (Object first : (Object[]) BUCKETS.get(null)) {
                        Object current = first;
                        while (current != null) {
                            Field key = current.getClass().getDeclaredField("address"); key.setAccessible(true);
                            if (key.getLong(current) == address) return (WeakReference<?>) current;
                            Field next = current.getClass().getDeclaredField("next"); next.setAccessible(true);
                            current = next.get(current);
                        }
                    }
                    throw new AssertionError("missing entry");
                }
                private static int size() throws Exception { return SIZE.getInt(null); }
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
            }
            """;

    private static final String FAULT = """
            final class Fault {
                static boolean entry;
                static boolean growth;
                static void beforeEntry() { if (entry) throw new OutOfMemoryError("injected entry allocation"); }
                static void beforeGrowth() { if (growth) throw new OutOfMemoryError("injected bucket allocation"); }
            }
            """;

    private static final String FAULT_CONSUMER = """
            public final class CacheConsumer {
                public static void main(String[] args) {
                    Object[] held = new Object[13];
                    for (int index = 0; index < held.length; index++) held[index] = new Object();
                    for (int index = 0; index < 12; index++) check(PermanentCache.remember(index + 1L, held[index]) == held[index]);
                    Fault.entry = true;
                    try { PermanentCache.remember(13, held[12]); throw new AssertionError("entry allocation did not fail"); }
                    catch (OutOfMemoryError expected) { check(expected.getMessage().equals("injected entry allocation")); }
                    Fault.entry = false; Fault.growth = true;
                    try { PermanentCache.remember(13, held[12]); throw new AssertionError("growth allocation did not fail"); }
                    catch (OutOfMemoryError expected) { check(expected.getMessage().equals("injected bucket allocation")); }
                    check(PermanentCache.lookup(13) == null);
                    for (int index = 0; index < 12; index++) check(PermanentCache.lookup(index + 1L) == held[index]);
                    Fault.entry = true;
                    check(PermanentCache.remember(1, new Object()) == held[0]);
                    Fault.entry = false; Fault.growth = false;
                    check(PermanentCache.remember(13, held[12]) == held[12]);
                    for (int index = 0; index < held.length; index++) check(PermanentCache.lookup(index + 1L) == held[index]);
                    System.out.println("cache-faults-ok:entry:growth:retry");
                }
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
            }
            """;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
