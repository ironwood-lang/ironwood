// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class BridgeRootStateTests {
    static final String NAME = "Java Bridge generated root state preserves refusal identity and isolated weak caches";
    private BridgeRootStateTests() {}

    static void state() throws Exception {
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Holder.iron", BridgeMixedLifetimeTests.SOURCE)));
        check(artifact.valid(), artifact.diagnostics().toString());
        var proof = BridgeObjectAdmission.prove(artifact, List.of("mixedlife")); check(proof.contract().isPresent(), proof.reason());
        var admission = proof.contract().orElseThrow();
        var generation = BridgeGeneration.createObjects("root-state.jar", artifact, admission, "test", "1".repeat(64), "2".repeat(64));
        var generated = BridgeRootStateSources.generate(artifact, admission, generation);
        check(generated.slotCapacity() == 1 && generated.types().size() == 4, "root state lost fixed slot layout or complete inventory");
        var changed = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Holder.iron",
                BridgeMixedLifetimeTests.SOURCE.replace("return 17;", "return 19;"))));
        try { BridgeRootStateSources.generate(changed, admission, generation); throw new AssertionError("stale root state admitted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("matching final root admission"), expected.getMessage()); }
        var permanent = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("Box.iron",
                "package perm; public final class Box { private static Box stored; public Box() {} public Box publish() { stored = this; return stored; } }")));
        check(permanent.valid(), permanent.diagnostics().toString());
        var permanentProof = BridgeObjectAdmission.prove(permanent, List.of("perm")); check(permanentProof.contract().isPresent(), permanentProof.reason());
        var permanentAdmission = permanentProof.contract().orElseThrow();
        var permanentGeneration = BridgeGeneration.createObjects("permanent.jar", permanent, permanentAdmission, "test", "1".repeat(64), "2".repeat(64));
        try { BridgeRootStateSources.generate(permanent, permanentAdmission, permanentGeneration); throw new AssertionError("permanent world acquired root state"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("matching final root admission"), expected.getMessage()); }
        Path base = Path.of("workspace/java-bridge/evidence/p3c/root-state").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path javaHome = Path.of(System.getProperty("java.home"));
        for (boolean faults : List.of(false, true)) {
            Path folder = directory.resolve(faults ? "injected" : "production"), classes = folder.resolve("classes"); Files.createDirectories(folder);
            var sources = new java.util.TreeMap<>(generated.sources()); String prefix = generation.supportPackage().replace('.', '/') + "/";
            if (faults) {
                String cache = sources.get(prefix + "RootCache.java").replace("Entry entry = new Entry(address, facade, collected);",
                        "Fault.beforeEntry(); Entry entry = new Entry(address, facade, collected);")
                        .replace("Entry[] expanded = new Entry[buckets.length * 2];", "Fault.beforeGrowth(); Entry[] expanded = new Entry[buckets.length * 2];");
                String state = sources.get(prefix + "RootState.java").replace("if (cache == null) cache = new RootCache();",
                        "if (cache == null) { Fault.beforeCache(); cache = new RootCache(); }");
                check(cache.contains("Fault.beforeEntry()") && cache.contains("Fault.beforeGrowth()") && state.contains("Fault.beforeCache()"), "root fault anchor changed");
                sources.put(prefix + "RootCache.java", cache); sources.put(prefix + "RootState.java", state);
                sources.put(prefix + "Fault.java", "package " + generation.supportPackage() + ";\n" + FAULT);
            }
            sources.put(prefix + "Identity.java", "package " + generation.supportPackage() + "; @interface Identity { String value(); }");
            sources.put(prefix + "StateConsumer.java", "package " + generation.supportPackage() + ";\n" + (faults ? FAULT_CONSUMER : CONSUMER));
            var command = new ArrayList<>(List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-d", classes.toString()));
            var hashes = new java.util.TreeMap<String, String>();
            for (var entry : sources.entrySet()) {
                Path source = folder.resolve("sources").resolve(entry.getKey()); Files.createDirectories(source.getParent()); Files.writeString(source, entry.getValue());
                command.add(source.toString()); hashes.put(entry.getKey(), BridgeGeneration.bytesDigest(Files.readAllBytes(source)));
            }
            BridgeEntryTests.run(folder, command, "javac");
            String output = BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/java").toString(), "-Xmx64m", "-XX:-DoEscapeAnalysis", "-cp", classes.toString(),
                    generation.supportPackage() + ".StateConsumer"), "consumer");
            check(faults ? output.equals("root-state-faults-ok\n") : output.matches("root-state-hits:500000:0\\nroot-state-ok:refusal:isolation:collection:reuse\\n"), output);
            Files.writeString(folder.resolve("identity.txt"), "generation=" + generation.identity() + "\nsource=" + BridgeGeneration.contentIdentity(hashes)
                    + "\nscope=Java component only; test reflection supplies state changes; no native registration or destruction evidence\n");
        }
        System.out.println("root state component evidence: " + directory);
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final String CONSUMER = """
            import java.lang.ref.WeakReference;
            import java.lang.reflect.Field;
            public final class StateConsumer {
                private static volatile Object sink;
                private static Field field(String name) throws Exception {
                    Field value = RootState.class.getDeclaredField(name); value.setAccessible(true); return value;
                }
                private static void refusal(Runnable call) {
                    try { call.run(); throw new AssertionError("missing lifetime refusal"); }
                    catch (IllegalStateException expected) { check(expected.getClass() == BridgeLifetimeException.class); }
                }
                public static void main(String[] args) throws Exception {
                    var address = field("address"); var status = field("status"); var incoming = field("incoming"); var cache = field("cache");
                    RootState root = new RootState(); address.setLong(root, 1000);
                    check(cache.get(root) == null && field("dependency0").get(root) == null);
                    check(root.address() == 1000 && root.prepareFree(1000)); root.checkLive();
                    check(status.getInt(root) == 0);
                    refusal(() -> root.prepareFree(2000));
                    incoming.setLong(root, 1); refusal(() -> root.prepareFree(1000));
                    check(status.getInt(root) == 0); incoming.setLong(root, 0);
                    Object owner = new Object(), child = new Object();
                    check(root.remember(1000, owner) == owner && root.remember(2000, child) == child);
                    check(root.lookup(1000) == owner && root.lookup(2000) == child && root.remember(1000, new Object()) == owner);
                    status.setInt(root, 1); refusal(root::checkLive); refusal(() -> root.prepareFree(1000));
                    status.setInt(root, 2); refusal(root::checkLive); refusal(() -> root.prepareFree(2000)); check(!root.prepareFree(1000));
                    check(root.address() == 1000 && root.lookup(1000) == owner);
                    RootState reused = new RootState(); address.setLong(reused, 1000);
                    Object replacement = new Object(); check(reused.lookup(1000) == null && reused.remember(1000, replacement) == replacement);
                    check(root.lookup(1000) == owner && reused.lookup(1000) == replacement && reused.prepareFree(1000));
                    // The reference handler enqueues a cleared entry after the collector clears it.
                    // Consuming that delivery keeps the measured lookups free of ReferenceQueue lock
                    // contention, which would allocate queue nodes on this thread.
                    Field collected = RootCache.class.getDeclaredField("collected"); collected.setAccessible(true);
                    var queue = (java.lang.ref.ReferenceQueue<?>) collected.get(cache.get(reused));
                    for (int pass = 0; pass < 6; pass++) {
                        WeakReference<Object> weak = collectible(reused);
                        for (int attempt = 0; attempt < 200 && weak.get() != null; attempt++) { System.gc(); Thread.sleep(10); }
                        check(weak.get() == null && queue.remove(10000) != null && reused.lookup(-1) == null);
                    }
                    Field size = RootCache.class.getDeclaredField("size"); size.setAccessible(true); check(size.getInt(cache.get(reused)) == 1);
                    Object[] held = new Object[4096];
                    for (int i = 0; i < held.length; i++) { held[i] = new Object(); check(reused.remember(i + 10000L, held[i]) == held[i]); }
                    for (int i = 0; i < 500000; i++) { reused.checkLive(); sink = reused.lookup((i & 4095) + 10000L); }
                    var counter = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
                    check(counter.isThreadAllocatedMemorySupported()); counter.setThreadAllocatedMemoryEnabled(true);
                    long thread = Thread.currentThread().threadId(); for (int i = 0; i < 10000; i++) counter.getThreadAllocatedBytes(thread);
                    // Tier transitions inside a first measured round have allocated on this thread
                    // under Rosetta; later rounds are the warm steady state that the claim covers.
                    long bytes = -1L;
                    for (int round = 0; round < 3 && bytes != 0L; round++) {
                        long before = counter.getThreadAllocatedBytes(thread);
                        for (int i = 0; i < 500000; i++) { reused.checkLive(); Object value = reused.lookup((i & 4095) + 10000L); check(value == held[i & 4095]); sink = value; }
                        bytes = counter.getThreadAllocatedBytes(thread) - before;
                    }
                    System.out.println("root-state-hits:500000:" + bytes); check(bytes == 0);
                    check(new IllegalStateException().getClass() != (Class<?>) BridgeLifetimeException.class);
                    java.lang.ref.Reference.reachabilityFence(owner); java.lang.ref.Reference.reachabilityFence(replacement);
                    System.out.println("root-state-ok:refusal:isolation:collection:reuse");
                }
                private static WeakReference<Object> collectible(RootState root) {
                    Object value = new Object(); check(root.remember(-1, value) == value); return new WeakReference<>(value);
                }
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
            }
            """;
    private static final String FAULT = """
            final class Fault {
                static int site;
                static void beforeCache() { if (site == 1) throw new OutOfMemoryError("cache"); }
                static void beforeEntry() { if (site == 2) throw new OutOfMemoryError("entry"); }
                static void beforeGrowth() { if (site == 3) throw new OutOfMemoryError("growth"); }
            }
            """;
    private static final String FAULT_CONSUMER = """
            public final class StateConsumer {
                public static void main(String[] args) {
                    RootState root = new RootState(); Object[] held = new Object[13];
                    for (int i = 0; i < held.length; i++) held[i] = new Object();
                    for (int site : new int[]{1, 2}) {
                        Fault.site = site;
                        try { root.remember(1, held[0]); throw new AssertionError("missing allocation failure"); }
                        catch (OutOfMemoryError expected) { check(root.lookup(1) == null); root.checkLive(); }
                    }
                    Fault.site = 0;
                    for (int i = 0; i < 12; i++) check(root.remember(i + 1, held[i]) == held[i]);
                    Fault.site = 3;
                    try { root.remember(13, held[12]); throw new AssertionError("missing growth failure"); }
                    catch (OutOfMemoryError expected) { check(root.lookup(13) == null); }
                    for (int i = 0; i < 12; i++) check(root.lookup(i + 1) == held[i]);
                    Fault.site = 0; check(root.remember(13, held[12]) == held[12]);
                    System.out.println("root-state-faults-ok");
                }
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
            }
            """;
}
