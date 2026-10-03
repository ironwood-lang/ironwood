// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.LlvmEmitter;
import ironwood.compiler.backend.LlvmToolchain;
import ironwood.compiler.backend.OptimizationLevel;
import ironwood.compiler.bridge.BridgeBootstrapSources;
import ironwood.compiler.bridge.BridgeGeneration;
import ironwood.compiler.bridge.BridgePermanentJavaSources;
import ironwood.compiler.bridge.BridgePermanentNativeSources;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Counters are confined to identified experiment copies, never producer output. */
final class BridgeOrderBookAllocationTests {
    static final String NAME = "Java Bridge actual OrderBook has allocation free warm paths and bounded weak recreation";
    private BridgeOrderBookAllocationTests() {}

    static void allocations() throws Exception {
        var artifact = BridgeOrderBookTests.sourceArtifact();
        var proof = BridgeObjectAdmission.prove(artifact, List.of("org.ironwood.orderbook"));
        check(proof.contract().isPresent(), proof.reason());
        var admission = proof.contract().orElseThrow();
        check(admission.roots().isEmpty(), "actual pool acquired lifetime bookkeeping");
        var producer = BridgeProducerInputs.discover();
        var generation = BridgeGeneration.createObjects("orderbook.jar", artifact, admission,
                producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
        var java = BridgePermanentJavaSources.generate(artifact, admission, generation);
        var adapters = BridgePermanentNativeSources.generate(artifact, admission, generation, java);
        String cachePath = generation.supportPackage().replace('.', '/') + "/PermanentCache.java";
        String originalCache = java.declarations().sources().get(cachePath);
        String cache = originalCache.replace("private static int size;", "private static int size; static long fixtureEntries; static int fixtureGrows; static int fixtureSize() { drain(); return size; }")
                .replace("static int fixtureSize()", "static int fixtureCapacity() { return buckets.length; } static int fixtureSize()")
                .replace("this.address = address;", "this.address = address; fixtureEntries++;")
                .replace("private static void grow() {", "private static void grow() { fixtureGrows++;");
        check(cache.contains("fixtureEntries++;") && cache.contains("fixtureSize()"), "cache counter anchors changed");
        String counterPath = generation.supportPackage().replace('.', '/') + "/OrderBookCounters.java";
        String counter = "package " + generation.supportPackage() + "; public final class OrderBookCounters { private OrderBookCounters() {}"
                + " public static long entries() { return PermanentCache.fixtureEntries; } public static int size() { return PermanentCache.fixtureSize(); }"
                + " public static int grows() { return PermanentCache.fixtureGrows; }"
                + " public static int capacity() { return PermanentCache.fixtureCapacity(); }}";
        Path base = Path.of("workspace/java-bridge/evidence/p4/allocations").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        String llvmText = new LlvmEmitter().emit(admission.program()); Path llvm = directory.resolve("program.ll"); Files.writeString(llvm, llvmText);
        check(llvmText.lines().anyMatch(line -> line.startsWith("define ") && line.contains("OrderBook.match\"")
                && line.contains(" alwaysinline")), "actual matching loop lost library inlining");
        Files.writeString(directory.resolve("scope.txt"), "Actual unchanged engine. Test-only counters on facade construction and cache-entry construction.\n"
                + "Native allocation accessor does not instrument scalar calls. One facade plus one weak Entry per miss; bucket growth is warmed separately.\n"
                + "generation=" + generation.identity() + "\nllvm=" + digest(llvmText) + "\nadapters=" + digest(adapters.source())
                + "\ncompiler=" + producer.compilerIdentity() + "\nruntime=" + producer.runtimeIdentity() + "\ncache=" + digest(cache)
                + "\npermanent=" + admission.lifetime().references().keySet() + "\n");
        var discovery = LlvmToolchain.discover(null); check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow(); Path javaHome = Path.of(System.getProperty("java.home"));
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            Path folder = directory.resolve(level.toString());
            var build = generation.nativeBuild(BridgeGeneratedJarTests.target(), Map.of("fixture", "OrderBook-allocation-counters", "llvm", digest(llvmText),
                    "adapters", digest(adapters.source()), "cache", digest(cache), "counter", digest(NATIVE_COUNTER), "optimization", level.toString()));
            String original = adapters.source() + BridgeBootstrapSources.generate(generation, build, java.declarations(), adapters);
            String anchor = "(*env)->NewObject(env, iw_permanent_types[index], iw_permanent_constructors[index], bits, (jobject)NULL)";
            check(original.contains(anchor), "facade counter anchor changed");
            String nativeSource = NATIVE_COUNTER + original.replace(anchor,
                    "fixture_object(env, iw_permanent_types[index], iw_permanent_constructors[index], bits)");
            Path jar = BridgeGeneratedJarTests.build(folder, llvm, toolchain, level, generation, build, java.declarations(), nativeSource,
                    Map.of(cachePath, cache, counterPath, counter));
            Files.writeString(folder.resolve("adapter.sha256"), digest(nativeSource) + "\n");
            Path consumer = folder.resolve("OrderBookAllocationConsumer.java"), paired = folder.resolve("OrderBookConsumer.java");
            Files.writeString(consumer, CONSUMER.replace("@COUNTERS@", generation.supportPackage() + ".OrderBookCounters"));
            Files.writeString(paired, BridgeOrderBookProducerTests.CONSUMER);
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                    "-cp", jar.toString(), consumer.toString(), paired.toString()), "consumer-javac");
            String result = BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-Xmx64m", "-XX:-DoEscapeAnalysis",
                    "-cp", jar + System.getProperty("path.separator") + folder, "OrderBookAllocationConsumer"), "consumer");
            check(result.endsWith("orderbook-allocation-ok\n") && !result.contains("WARNING") && !result.contains("FATAL"), result);
            BridgeEntryTests.run(folder, List.of(toolchain.clang().resolveSibling("llvm-objdump").toString(), "--disassemble",
                    folder.resolve(BridgeGeneratedJarTests.imageName()).toString()), "disassembly");
        }
        System.out.println("OrderBook allocation evidence: " + directory);
    }

    private static String digest(String source) { return BridgeGeneration.bytesDigest(source.getBytes(StandardCharsets.UTF_8)); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final String NATIVE_COUNTER = """
            #include <jni.h>
            #include "ironwood_runtime.h"
            static jlong fixture_facades;
            static jobject fixture_object(JNIEnv *env, jclass type, jmethodID constructor, jlong address) {
                jobject result = (*env)->NewObject(env, type, constructor, address, (jobject)NULL);
                if (result != NULL) fixture_facades++;
                return result;
            }
            JNIEXPORT jlong JNICALL Java_OrderBookAllocationConsumer_metric(JNIEnv *env, jclass type, jint kind) {
                (void)env; (void)type;
                return kind == 0 ? (jlong)ironwood_allocation_count() : fixture_facades;
            }
            """;

    private static final String CONSUMER = """
            import org.ironwood.orderbook.Order;
            import org.ironwood.orderbook.OrderBook;
            import org.ironwood.orderbook.Order.Side;
            import @COUNTERS@;
            import java.lang.management.ManagementFactory;
            import java.lang.ref.ReferenceQueue;
            import java.lang.ref.WeakReference;
            public final class OrderBookAllocationConsumer {
                private static native long metric(int kind);
                private static final com.sun.management.ThreadMXBean COUNTER = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                private static final long THREAD = Thread.currentThread().threadId();
                private static long bytes() { return COUNTER.getThreadAllocatedBytes(THREAD); }
                private static long sum;
                private static long missBytes;
                public static void main(String[] args) throws Exception {
                    check(COUNTER.isThreadAllocatedMemorySupported()); COUNTER.setThreadAllocatedMemoryEnabled(true);
                    OrderBook book = new OrderBook(8, 4);
                    Order[] identities = new Order[8];
                    for (int i = 0; i < identities.length; i++) identities[i] = book.createLimit(i + 1, Side.BUY, 100, 100);
                    for (Order value : identities) value.cancel();
                    check(book.hasFullPoolCapacity());
                    OrderBook scalarBook = new OrderBook(1, 1);
                    Order scalar = scalarBook.createLimit(1, Side.BUY, 100, 100);
                    for (int i = 0; i < 50000; i++) { scalar(scalarBook, scalar); cycle(book, identities, 6L * i + 1); bytes(); }
                    long nativeBefore = metric(0), facadeBefore = metric(1), entriesBefore = OrderBookCounters.entries(), bytesBefore = bytes();
                    for (int i = 0; i < 100000; i++) scalar(scalarBook, scalar);
                    long bytesAfter = bytes(), nativeAfter = metric(0), facadeAfter = metric(1), entriesAfter = OrderBookCounters.entries();
                    report("scalar", nativeBefore, nativeAfter, bytesBefore, bytesAfter, facadeBefore, facadeAfter, entriesBefore, entriesAfter);
                    zero(nativeBefore, nativeAfter, bytesBefore, bytesAfter, facadeBefore, facadeAfter, entriesBefore, entriesAfter);
                    nativeBefore = metric(0); facadeBefore = metric(1); entriesBefore = OrderBookCounters.entries(); bytesBefore = bytes();
                    for (int i = 0; i < 100000; i++) cycle(book, identities, 6L * i + 1);
                    bytesAfter = bytes(); nativeAfter = metric(0); facadeAfter = metric(1); entriesAfter = OrderBookCounters.entries();
                    report("hits", nativeBefore, nativeAfter, bytesBefore, bytesAfter, facadeBefore, facadeAfter, entriesBefore, entriesAfter);
                    zero(nativeBefore, nativeAfter, bytesBefore, bytesAfter, facadeBefore, facadeAfter, entriesBefore, entriesAfter);
                    check(book.isEmpty() && book.hasFullPoolCapacity() && book.getMatchCount() == 450000 && book.getMatchedVolume() == 37500000);
                    OrderBook single = new OrderBook(1, 1);
                    // Force growth outside measured misses, regardless of initial table capacity.
                    OrderBook[] sizing = new OrderBook[OrderBookCounters.capacity()];
                    for (int index = 0; index < sizing.length; index++) sizing[index] = new OrderBook(1, 1);
                    int grows = OrderBookCounters.grows(); check(grows > 0);
                    int retained = 12 + sizing.length;
                    check(OrderBookCounters.size() == retained);
                    var queue = new ReferenceQueue<Order>();
                    for (int incarnation = 0; incarnation < 8; incarnation++) {
                        WeakReference<Order> weak = miss(single, queue, incarnation);
                        boolean observed = false;
                        for (int attempt = 0; attempt < 100; attempt++) {
                            System.gc(); Thread.sleep(10);
                            if (weak.get() == null && queue.poll() == weak) { observed = true; break; }
                        }
                        check(observed);
                        // Retained books/orders plus at most one recreated order; no accumulating weak entries.
                        check(OrderBookCounters.size() <= retained + 1 && OrderBookCounters.grows() == grows);
                        System.out.println("collected=" + incarnation + " entries=" + OrderBookCounters.size());
                    }
                    java.lang.ref.Reference.reachabilityFence(identities);
                    java.lang.ref.Reference.reachabilityFence(scalar);
                    java.lang.ref.Reference.reachabilityFence(sizing);
                    java.lang.ref.Reference.reachabilityFence(book);
                    java.lang.ref.Reference.reachabilityFence(single);
                    scalar.cancel();
                    System.out.println("orderbook-allocation-ok");
                }
                private static void scalar(OrderBook book, Order order) {
                    order.reduceTo(100);
                    sum += order.getId() + order.getPrice() + order.getTotalSize() + order.getExecutedSize() + order.getOpenSize()
                            + book.getMatchCount() + book.getMatchedVolume() + book.getRestingOrderCount();
                    check(order.isResting() && !order.isTerminal());
                }
                private static void identity(Order value, Order[] identities) {
                    for (Order identity : identities) if (value == identity) return;
                    throw new AssertionError("pool identity changed");
                }
                private static void cycle(OrderBook book, Order[] identities, long next) {
                    Order bid = book.createLimit(next++, Side.BUY, 100, 10000000000L); identity(bid, identities);
                    identity(book.createLimit(next++, Side.BUY, 100, 9900000000L), identities);
                    Order ask = book.createLimit(next++, Side.SELL, 100, 10200000000L); identity(ask, identities);
                    identity(book.createLimit(next++, Side.SELL, 100, 10300000000L), identities);
                    bid.reduceTo(50); ask.cancel();
                    book.createMarket(next++, Side.SELL, 150); book.createMarket(next, Side.BUY, 100);
                }
                private static WeakReference<Order> miss(OrderBook book, ReferenceQueue<Order> queue, int incarnation) {
                    long n = metric(0), f = metric(1), e = OrderBookCounters.entries(), b = bytes();
                    Order order = book.createLimit(1, Side.BUY, 100, 100);
                    long a = bytes(), nn = metric(0), ff = metric(1), ee = OrderBookCounters.entries();
                    report("miss-" + incarnation, n, nn, b, a, f, ff, e, ee);
                    check(nn == n && ff == f + 1 && ee == e + 1 && a > b);
                    if (incarnation == 0) missBytes = a - b;
                    else check(a - b == missBytes);
                    order.cancel();
                    // Keep the facade reachable for the entire repeated storage reuse loop.
                    n = metric(0); f = metric(1); e = OrderBookCounters.entries(); b = bytes();
                    for (int i = 0; i < 10000; i++) {
                        Order again = book.createLimit(i + 1, Side.BUY, 100, 100); check(again == order); again.cancel();
                    }
                    a = bytes(); nn = metric(0); ff = metric(1); ee = OrderBookCounters.entries();
                    zero(n, nn, b, a, f, ff, e, ee);
                    return new WeakReference<>(order, queue);
                }
                private static void zero(long n, long nn, long b, long bb, long f, long ff, long e, long ee) {
                    check(n == nn && b == bb && f == ff && e == ee);
                }
                private static void report(String label, long n, long nn, long b, long bb, long f, long ff, long e, long ee) {
                    System.out.println(label + " native=" + n + ":" + nn + " bytes=" + b + ":" + bb
                            + " facades=" + f + ":" + ff + " cache=" + e + ":" + ee);
                }
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
            }
            """;
}
