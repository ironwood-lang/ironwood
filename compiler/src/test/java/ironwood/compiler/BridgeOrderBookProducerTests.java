// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.BridgeGeneration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipFile;

/** The same public-API consumer runs against the actual paired engines. */
final class BridgeOrderBookProducerTests {
    static final String NAME = "Java Bridge actual OrderBook producer matches paired Java behavior and pool recovery";
    private BridgeOrderBookProducerTests() {}

    static void producer() throws Exception {
        Path base = Path.of("workspace/java-bridge/evidence/p4/producer").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path root = Path.of("projects/OrderBook/src/main/ironwood").toAbsolutePath();
        Path classes = directory.resolve("engine-classes"), archive = directory.resolve("engine.ironjar");
        BridgeProducerTests.command(directory, "compile-engine", 0, new String[]{"--unfreed=off", "--source-path",
                root.toString(), "-d", classes.toString(), root.resolve("org/ironwood/orderbook/OrderBook.iron").toString()});
        try (var files = Files.list(classes.resolve("org/ironwood/orderbook"))) {
            check(files.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet())
                    .equals(Set.of("OrderBook.ironclass", "Order.ironclass", "Order$Side.ironclass", "Order$Type.ironclass",
                            "PriceLevel.ironclass")), "engine selection changed");
        }
        IronJar.create(archive, List.of(classes));
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path consumer = directory.resolve("OrderBookConsumer.java"); Files.writeString(consumer, CONSUMER);
        Path paired = directory.resolve("paired-classes");
        var command = new ArrayList<>(List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                "-d", paired.toString(), consumer.toString()));
        for (String name : List.of("OrderBook", "Order", "PriceLevel")) command.add(Path.of(
                "projects/OrderBook/java/src/main/java/org/ironwood/orderbook/" + name + ".java").toAbsolutePath().toString());
        BridgeEntryTests.run(directory, command, "paired-javac");
        String expected = BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/java").toString(), "-cp", paired.toString(),
                "OrderBookConsumer"), "paired-consumer");
        Properties reference = null;
        for (String variant : List.of("classes", "archive")) {
            Path folder = directory.resolve(variant); Files.createDirectories(folder);
            Path jar = folder.resolve("orderbook.jar");
            BridgeProducerTests.command(folder, "producer", 0, new String[]{"--java-bridge", "--export", "org.ironwood.orderbook",
                    "--unfreed=off", variant.equals("classes") ? "-O0" : "-O3", "-cp",
                    (variant.equals("classes") ? classes : archive).toString(), "-o", jar.toString()});
            var manifest = new Properties();
            try (var zip = new ZipFile(jar.toFile())) {
                try (var input = zip.getInputStream(zip.getEntry(BridgePackageManifest.PATH))) { manifest.load(input); }
                check(zip.stream().filter(entry -> entry.getName().startsWith("org/ironwood/orderbook/") && entry.getName().endsWith(".class"))
                        .map(entry -> entry.getName()).collect(java.util.stream.Collectors.toSet()).equals(Set.of(
                                "org/ironwood/orderbook/OrderBook.class", "org/ironwood/orderbook/Order.class",
                                "org/ironwood/orderbook/Order$Side.class", "org/ironwood/orderbook/Order$Type.class",
                                "org/ironwood/orderbook/_IronwoodBridgePackage.class")), "unexpected exported engine classes");
                for (var entry : zip.stream().toList()) {
                    if (entry.getName().equals(BridgePackageManifest.PATH)) continue;
                    try (var input = zip.getInputStream(entry)) {
                        check(BridgeGeneration.bytesDigest(input.readAllBytes()).equals(manifest.getProperty("content.sha256." + entry.getName())),
                                "unpaired OrderBook content: " + entry.getName());
                    }
                }
                check(manifest.stringPropertyNames().stream().noneMatch(key -> key.startsWith("java.root.destruction.")), "fabricated pool destruction");
            }
            if (reference == null) reference = manifest;
            else for (String key : List.of("generation", "api", "program")) {
                check(reference.getProperty(key).equals(manifest.getProperty(key)), "packaged engine identity differs: " + key);
            }
            Path compiled = folder.resolve("consumer-classes");
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror",
                    "-cp", jar.toString(), "-d", compiled.toString(), consumer.toString()), "consumer-javac");
            for (boolean checked : List.of(false, true)) {
                var run = new ArrayList<>(List.of(javaHome.resolve("bin/java").toString()));
                if (checked) run.addAll(List.of("-Xcheck:jni", "-XX:-DoEscapeAnalysis"));
                run.addAll(List.of("-cp", jar + System.getProperty("path.separator") + compiled, "OrderBookConsumer"));
                check(BridgeEntryTests.run(folder, run, "consumer-" + checked).equals(expected), "paired OrderBook observations differ");
            }
        }
        System.out.println("OrderBook producer evidence: " + directory);
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    static final String CONSUMER = """
            import org.ironwood.orderbook.Order;
            import org.ironwood.orderbook.OrderBook;
            import org.ironwood.orderbook.Order.Side;
            import org.ironwood.orderbook.Order.Type;
            public final class OrderBookConsumer {
                public static void main(String[] args) {
                    check(Side.BUY.index() == 0 && Side.SELL.index() == 1 && Side.BUY.invertedIndex() == 1);
                    check(Side.BUY.isOutside(99, 100) && Side.SELL.isOutside(101, 100));
                    check(Type.valueOf("LIMIT") == Type.LIMIT && Type.values().length == 2);
                    ordering(); capacity();
                    OrderBook book = new OrderBook(8, 4);
                    long next = 1;
                    for (int i = 0; i < 10000; i++) next = cycle(book, next);
                    check(book.isEmpty() && book.hasFullPoolCapacity());
                    check(book.getMatchCount() == 30000 && book.getMatchedVolume() == 2500000);
                    check(book.getLastExecutedPrice() == 10300000000L && book.getLastMakerOrderId() == next - 3);
                    System.out.println("orderbook-paired-ok " + book.getMatchCount() + " " + book.getMatchedVolume()
                            + " " + book.getLastExecutedPrice() + " " + book.getLastMakerOrderId());
                    warmedAllocation();
                }
                private static void warmedAllocation() {
                    var counter = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
                    check(counter.isThreadAllocatedMemorySupported()); counter.setThreadAllocatedMemoryEnabled(true);
                    long thread = Thread.currentThread().threadId();
                    OrderBook book = new OrderBook(8, 4);
                    Order[] held = new Order[8];
                    for (int i = 0; i < held.length; i++) held[i] = book.createLimit(i + 1, Side.BUY, 100, 100);
                    for (Order order : held) order.cancel();
                    OrderBook scalarBook = new OrderBook(1, 1);
                    Order scalar = scalarBook.createLimit(1, Side.BUY, 100, 100);
                    for (int i = 0; i < 50000; i++) {
                        cycle(book, 6L * i + 1); scalar.reduceTo(100); scalar.getOpenSize(); counter.getThreadAllocatedBytes(thread);
                    }
                    long before = counter.getThreadAllocatedBytes(thread);
                    for (int i = 0; i < 100000; i++) {
                        cycle(book, 6L * i + 1); scalar.reduceTo(100); check(scalar.getOpenSize() == 100);
                    }
                    long after = counter.getThreadAllocatedBytes(thread);
                    check(after == before && book.isEmpty() && book.hasFullPoolCapacity());
                    java.lang.ref.Reference.reachabilityFence(held); scalar.cancel();
                    System.out.println("orderbook-production-warm-bytes=" + (after - before));
                }
                static long cycle(OrderBook book, long next) {
                    Order bestBid = book.createLimit(next++, Side.BUY, 100, 10000000000L);
                    book.createLimit(next++, Side.BUY, 100, 9900000000L);
                    Order bestAsk = book.createLimit(next++, Side.SELL, 100, 10200000000L);
                    book.createLimit(next++, Side.SELL, 100, 10300000000L);
                    bestBid.reduceTo(50); bestAsk.cancel();
                    book.createMarket(next++, Side.SELL, 150); book.createMarket(next++, Side.BUY, 100);
                    return next;
                }
                private static void ordering() {
                    OrderBook book = new OrderBook(8, 4);
                    Order low = book.createLimit(1, Side.BUY, 40, 99);
                    Order first = book.createLimit(2, Side.BUY, 50, 100);
                    Order second = book.createLimit(3, Side.BUY, 60, 100);
                    check(book.getBestPrice(Side.BUY) == 100 && book.getBestSize(Side.BUY) == 110);
                    check(book.getLevelCount(Side.BUY) == 2 && book.getRestingOrderCount() == 3);
                    check(first.getId() == 2 && first.getSide() == Side.BUY && first.getPrice() == 100 && first.getType() == Type.LIMIT);
                    book.createMarket(4, Side.SELL, 20);
                    check(first.getExecutedSize() == 20 && first.getOpenSize() == 30 && first.getTotalSize() == 50);
                    check(first.isResting() && !first.isTerminal() && book.getLastMakerOrderId() == 2);
                    first.reduceTo(40); check(first.getOpenSize() == 20 && book.getBestSize(Side.BUY) == 80);
                    // The first handle is no longer used after its terminal fill.
                    book.createMarket(5, Side.SELL, 30);
                    check(second.getExecutedSize() == 10 && second.getOpenSize() == 50 && book.getLastMakerOrderId() == 3);
                    second.cancel(); check(book.getBestPrice(Side.BUY) == 99 && book.getBestSize(Side.BUY) == 40);
                    low.reduceTo(15); check(low.getTotalSize() == 15 && book.getBestSize(Side.BUY) == 15);
                    // This fully matching limit is already reset into the pool on return.
                    Order released = book.createLimit(6, Side.SELL, 15, 99);
                    check(released != null && book.isEmpty() && book.hasFullPoolCapacity());
                    check(book.getMatchCount() == 4 && book.getMatchedVolume() == 65 && book.getLastMakerOrderId() == 1);
                }
                private static void capacity() {
                    OrderBook orders = new OrderBook(1, 1);
                    Order held = orders.createLimit(1, Side.BUY, 1, 100);
                    try { orders.createLimit(2, Side.BUY, 1, 100); throw new AssertionError("capacity admitted"); }
                    catch (IllegalStateException expected) { check(expected.getClass() == IllegalStateException.class
                            && expected.getMessage().equals("order capacity exhausted")); }
                    held.cancel(); check(orders.hasFullPoolCapacity());
                    OrderBook levels = new OrderBook(3, 1);
                    levels.createLimit(1, Side.BUY, 1, 100);
                    try { levels.createLimit(2, Side.BUY, 1, 99); throw new AssertionError("level capacity admitted"); }
                    catch (IllegalStateException expected) { check(expected.getClass() == IllegalStateException.class
                            && expected.getMessage().equals("price-level capacity exhausted")); }
                    // Source behavior does not roll back this exhausted-level insertion.
                    check(levels.getRestingOrderCount() == 1 && levels.getLevelCount(Side.BUY) == 1);
                }
                static void check(boolean value) { if (!value) throw new AssertionError(); }
            }
            """;
}
