// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class BridgeOrderBookNativeTests {
    private BridgeOrderBookNativeTests() {}

    static final String NAME = "Java Bridge actual OrderBook constructor failures preserve exposed controls";
    private static final Path ENGINE = Path.of("projects/OrderBook/src/main/ironwood");
    private static final Path BOOK = ENGINE.resolve("org/ironwood/orderbook/OrderBook.iron");

    static void failures() throws Exception {
        boolean recording = "1".equals(System.getenv("IRONWOOD_BRIDGE_ALLOCATION_EVENTS"));
        var loaded = new SourceSetLoader(List.of(ENGINE), List.of()).load(List.of(BOOK));
        check(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
        var sources = new ArrayList<>(loaded.sources());
        sources.add(SourceFile.of("test/BridgeSide.iron", """
                package org.ironwood.orderbook;
                final class BridgeSide { static Order.Side buy() { return Order.Side.BUY; } }
                """));
        var artifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(sources);
        check(artifact.valid(), artifact.diagnostics().toString());
        var program = artifact.program().orElseThrow();
        var names = Set.of("OrderBook", "createLimit", "cancel", "reduceTo", "getId", "getOpenSize", "getRestingOrderCount", "buy");
        var roots = BridgeRootSet.resolve(program, program.functions().stream()
                .filter(function -> function.ownerClass().startsWith("org.ironwood.orderbook.") && names.contains(function.sourceName()))
                .map(BridgeCallableId::of).toList());
        var module = BridgeEntryModule.permanentObjects(artifact, roots);
        check(module.permanent().orElseThrow().rollbacks().size() == 1, "actual constructor rollback not proved");
        var base = Path.of("workspace/java-bridge/evidence/p0b/orderbook-failure").toAbsolutePath();
        Files.createDirectories(base);
        var directory = Files.createTempDirectory(base, "run-");
        Files.writeString(directory.resolve("instrumentation.txt"), recording ? "copied-runtime fixed allocation event log\n" : "unmodified production runtime\n");
        Files.writeString(directory.resolve("proof.txt"), module.permanent().orElseThrow().toString());
        for (var source : sources) Files.writeString(directory.resolve(source.path().getFileName()), source.content());
        var llvm = directory.resolve("program.ll");
        Files.writeString(llvm, new LlvmEmitter().emit(module));
        var adapter = directory.resolve("adapter.c");
        Files.writeString(adapter, adapter(module, recording));
        var consumer = directory.resolve("BridgeOrderBookConsumer.java");
        Files.writeString(consumer, BridgeOrderBookFixtureSources.CONSUMER);
        var javaHome = Path.of(System.getProperty("java.home"));
        BridgeEntryTests.run(directory, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", consumer.toString()), "javac");
        var discovery = LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            var object = directory.resolve("adapter-" + level + ".o");
            BridgeEntryTests.run(directory, List.of(toolchain.clang().toString(), "-std=c11", "-Wall", "-Wextra", "-Werror",
                    "-fPIC", "-fvisibility=hidden", level.clangArgument(), "-I" + javaHome.resolve("include"),
                    "-I" + javaHome.resolve(mac ? "include/darwin" : "include/linux"), "-I" + Path.of("runtime/include").toAbsolutePath(),
                    "-c", adapter.toString(), "-o", object.toString()), "adapter-" + level);
            var image = directory.resolve("orderbook-" + level + (mac ? ".dylib" : ".so"));
            var linked = new NativeBackend().linkShared(toolchain, llvm, image, level, List.of(object));
            Files.writeString(directory.resolve("link-" + level + ".log"), linked.output());
            check(linked.success(), linked.output());
            Files.writeString(directory.resolve("sha256-" + level + ".txt"), HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(image))) + "  " + image.getFileName() + "\n");
            var command = List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", directory.toString(),
                    "BridgeOrderBookConsumer", image.toString(), "calibrate");
            var calibrated = BridgeEntryTests.run(directory, command, "calibration-" + level);
            check(calibrated.contains("created:12\n") && calibrated.contains("control-survived:allocation-free\n"), calibrated);
            long control = Long.parseLong(calibrated.lines().filter(line -> line.startsWith("control:")).findFirst().orElseThrow().split(":")[1]);
            if (recording) validateCalibration(events(calibrated));
            for (int successes : List.of(3, 7, 11)) {
                var failedCommand = new ArrayList<>(command); failedCommand.set(failedCommand.size() - 1, Integer.toString(successes));
                String name = "failure-" + successes + "-" + level;
                long budget = control + successes;
                Files.writeString(directory.resolve(name + ".command.txt"), String.join("\n", failedCommand)
                        + "\nIRONWOOD_ALLOCATION_LIMIT=" + budget + "\n");
                var builder = new ProcessBuilder(failedCommand).redirectErrorStream(true).redirectOutput(directory.resolve(name + ".log").toFile());
                builder.environment().put("IRONWOOD_ALLOCATION_LIMIT", Long.toString(budget));
                var child = builder.start();
                if (!child.waitFor(90, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("OrderBook failure child timeout"); }
                var output = Files.readString(directory.resolve(name + ".log"));
                check(child.exitValue() == 0 && output.contains("control-survived:allocation-free\n"), output);
                String statement = successes == 3 ? "this.orderPool[index] = new Order();"
                        : successes == 7 ? "this.priceLevelPool[index] = new PriceLevel();" : "this.levelCount = new int[2];";
                int line = Files.readAllLines(BOOK).indexOf("            " + statement) + 1;
                if (line == 0) line = Files.readAllLines(BOOK).indexOf("        " + statement) + 1;
                check(line > 0 && output.contains("OrderBook.iron:" + line + ")"), "failure site changed: " + output);
                if (recording) validateFailure(events(calibrated), events(output), successes);
            }
        }
        System.out.println("bridge OrderBook failure evidence: " + directory);
        if (!recording) recordedChild(directory, javaHome);
    }

    private record Event(int phase, int kind, String address, long size, String type) {}

    private static List<Event> events(String output) {
        return output.lines().filter(line -> line.startsWith("event:")).map(line -> {
            var fields = line.split(":", 6);
            return new Event(Integer.parseInt(fields[1]), Integer.parseInt(fields[2]), fields[3], Long.parseLong(fields[4]), fields[5]);
        }).toList();
    }

    private static List<Event> allocated(List<Event> events, int phase) {
        return events.stream().filter(event -> event.phase() == phase && event.kind() == 1).toList();
    }

    private static void validateCalibration(List<Event> events) {
        var second = allocated(events, 2);
        check(second.size() == 12 && second.get(0).type().equals("org.ironwood.orderbook.OrderBook")
                && second.subList(2, 5).stream().allMatch(event -> event.type().equals("org.ironwood.orderbook.Order"))
                && second.subList(6, 9).stream().allMatch(event -> event.type().equals("org.ironwood.orderbook.PriceLevel")),
                "actual constructor allocation sequence changed: " + second);
        check(events.stream().noneMatch(event -> event.kind() == 2 || event.phase() == 3), "successful control unexpectedly allocates/frees");
    }

    private static void validateFailure(List<Event> calibration, List<Event> failed, int successes) {
        var expected = allocated(calibration, 2);
        var actual = allocated(failed, 2);
        check(actual.size() == successes, "unexpected successful allocations: " + actual);
        for (int index = 0; index < successes; index++) {
            check(actual.get(index).type().equals(expected.get(index).type()) && actual.get(index).size() == expected.get(index).size(),
                    "allocation prefix differs from calibration");
        }
        var attempts = failed.stream().filter(event -> event.phase() == 2 && event.kind() == 0).toList();
        check(attempts.size() == successes + 1 && attempts.getLast().type().equals(expected.get(successes).type()),
                "failure did not reach the calibrated allocation site: " + attempts);
        var frees = failed.stream().filter(event -> event.kind() == 2).toList();
        check(frees.size() == (successes == 11 ? 2 : 1), "unexpected rollback cleanup: " + frees);
        var book = actual.getFirst();
        check(frees.getLast().address().equals(book.address()) && frees.getLast().type().equals(book.type()), "rollback did not release its fresh book");
        if (successes == 11) check(frees.getFirst().address().equals(actual.get(10).address())
                && frees.getFirst().type().equals(actual.get(10).type()), "rollback did not release only its fresh tail array");
        var control = allocated(failed, 1).stream().map(Event::address).collect(java.util.stream.Collectors.toSet());
        check(frees.stream().noneMatch(event -> control.contains(event.address())) && failed.stream().noneMatch(event -> event.phase() == 3),
                "existing storage changed during failed construction or allocation-free control");
    }

    private static String adapter(BridgeEntryModule module, boolean recording) {
        var text = new StringBuilder(BridgeCommitFixtureSources.HEADERS);
        text.append(recording ? "extern void bridge_test_phase(int);\nextern void bridge_test_events(void);\n#define PHASE(p) bridge_test_phase(p)\n#define EVENTS() bridge_test_events()\n"
                : "#define PHASE(p) ((void)(p))\n#define EVENTS() ((void)0)\n");
        for (var entry : module.entries()) {
            var id = entry.root().callable();
            if (id.name().equals("cancel") && id.owner().endsWith(".OrderBook")) continue;
            text.append("extern int32_t ").append(entry.function().linkageName()).append('(');
            for (var parameter : entry.function().parameters()) {
                var type = parameter.value().type();
                String c = type.isReference() ? "void *" : type.equals(IrType.I64) ? "int64_t" : type.equals(IrType.I32) ? "int32_t" : null;
                check(c != null, "unexpected actual engine ABI: " + type);
                text.append(c).append(", ");
            }
            text.setLength(text.length() - 2);
            text.append(");\n#define call_").append(id.name()).append(' ').append(entry.function().linkageName()).append('\n');
        }
        return text + BridgeOrderBookFixtureSources.ADAPTER;
    }

    private static void recordedChild(Path directory, Path javaHome) throws Exception {
        var home = directory.resolve("event-runtime");
        try (var paths = Files.walk(Path.of("runtime"))) {
            for (var path : paths.toList()) {
                var destination = home.resolve(path);
                if (Files.isDirectory(path)) Files.createDirectories(destination); else Files.copy(path, destination);
            }
        }
        var runtime = home.resolve("runtime/src/ironwood_runtime.c");
        var original = Files.readString(runtime);
        String start = "static void *try_allocate_object(size_t size, const void *object_type,\n                                 void *allocation_failure) {";
        String success = "    record_limited_allocation(allocation_failure);\n    return allocation;";
        String free = "    atomic_fetch_sub_explicit(&live_allocation_count, UINT64_C(1), memory_order_relaxed);\n    free(object);";
        for (String anchor : List.of(start, success, free)) check(original.contains(anchor) && original.indexOf(anchor) == original.lastIndexOf(anchor), "runtime event anchor changed");
        Files.writeString(runtime, original.replace(start, BridgeOrderBookFixtureSources.RECORDING + "\n" + start
                + "\n    bridge_test_record(0, NULL, size, object_type);")
                .replace(success, "    bridge_test_record(1, allocation, size, object_type);\n" + success)
                .replace(free, "    bridge_test_record(2, object, 0, *(const void **)object);\n" + free));
        var command = List.of(javaHome.resolve("bin/java").toString(), "-ea", "-cp", System.getProperty("java.class.path"),
                "ironwood.compiler.CompilerTests", "--test", NAME);
        Files.writeString(directory.resolve("event-child.command.txt"), String.join("\n", command)
                + "\nIRONWOOD_RUNTIME_HOME=" + home + "\nIRONWOOD_BRIDGE_ALLOCATION_EVENTS=1\n");
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve("event-child.log").toFile());
        builder.environment().put("IRONWOOD_RUNTIME_HOME", home.toString());
        builder.environment().put("IRONWOOD_BRIDGE_ALLOCATION_EVENTS", "1");
        var child = builder.start();
        if (!child.waitFor(90, TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("OrderBook event child timeout"); }
        check(child.exitValue() == 0, Files.readString(directory.resolve("event-child.log")));
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
