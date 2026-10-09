// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** The M2.2 native ownership pilot: ownership and callback controls, J0 parity and failure safety. */
final class OwnershipPilotTests {
    private static final String PORT = "compiler/src/main/ironwood";
    private static final String ADAPTER = "scripts/self-hosting/native/KernelCapture.iron";
    private static final String CALLBACKS = "integration-tests/cases/compiler_ownership_callbacks.iron";
    private static final String FAILURE = "integration-tests/cases/compiler_ownership_failure.iron";
    private static final String M0 = "docs/self-hosting/m0/";
    private static final String HEAD = """
            import ironwood.compiler.port.*;
            import ironwood.compiler.semantic.*;
            import ironwood.compiler.source.*;
            import ironwood.ds.ArrayList;
            final class Helpers {
                static int read(SnapshotList<OwnershipSnapshot> versions) { return versions.size(); }
                static int loop(SourceFile source, SemanticAnalysisObserver observer) {
                    FunctionOwnership holder = new FunctionOwnership(source, null, null, observer);
                    defer free holder;
                    for (int index = 0; index < 4; index++) {
                        OwnershipSnapshot snapshot = holder.snapshotOwnership();
                        defer free snapshot;
                        holder.restoreOwnership(snapshot);
                    }
                    return 0;
                }
            }
            final class Counter implements SemanticAnalysisObserver {
                int calls;
                Counter() { }
                @Override public void analyzerCreated(long token, OperationVariants.AnalyzerKind kind,
                        OperationVariants.AnalyzerPhase phase) { this.calls++; }
                @Override public void analyzerRound(long token, OperationVariants.AnalyzerKind kind, int round) { this.calls++; }
                @Override public void evidenceSnapshot(boolean saved, boolean sharedEmpty, int associations) { this.calls++; }
            }
            class Main {
                public static int main(String[] args) {
                    SourceFile source = SourceFile.of("C.iron", "class C {}\\n");
                    FunctionOwnership analyzer = new FunctionOwnership(source, null, null, null);
                    AllocationInfo node = new AllocationInfo(0);
                    analyzer.allocations().add(node);
            """;

    private OwnershipPilotTests() { }

    /** Join owners and snapshots retire; frees of versions, payloads or services still observed are rejected. */
    static void ownership() throws Exception {
        Map<String, String> programs = new LinkedHashMap<>();
        // Two captures grow the owner; the join reads a lent version; the owner retires after release.
        programs.put("", """
                OwnershipPaths paths = new OwnershipPaths(1);
                analyzer.captureOwnership(paths);
                node.setState(OperationVariants.AllocationState.ESCAPED);
                analyzer.captureOwnership(paths, 0);
                analyzer.mergeOwnership(paths.version(0), paths, "conflict");
                int size = paths.size();
                analyzer.releaseOwnership(paths);
                free paths; free analyzer; free source;
                return size == 2 ? 42 : 1;""");
        // Versions copy the live fields: the analyzer may retire before its versions.
        programs.put("\u0000", """
                OwnershipPaths paths = new OwnershipPaths(2);
                analyzer.captureOwnership(paths);
                free analyzer;
                int states = paths.version(0).stateCount();
                free paths; free source;
                return states == 1 ? 42 : 1;""");
        programs.put("cannot free 'first': value is a borrowed helper owned by another object", """
                OwnershipPaths paths = new OwnershipPaths(2);
                analyzer.captureOwnership(paths);
                OwnershipSnapshot first = paths.version(0);
                free first; free paths; free analyzer; free source;
                return 0;""");
        programs.put("cannot use 'first' after its allocation was freed", """
                OwnershipPaths paths = new OwnershipPaths(2);
                analyzer.captureOwnership(paths);
                OwnershipSnapshot first = paths.version(0);
                free paths;
                analyzer.restoreOwnership(first);
                free analyzer; free source;
                return 0;""");
        programs.put("cannot free 'saved': value is a borrowed helper owned by another object", """
                OwnershipSnapshot snapshot = analyzer.snapshotOwnership();
                RejectedFreeEvidence.Saved saved = snapshot.savedEvidence();
                free saved; free snapshot; free analyzer; free source;
                return 0;""");
        programs.put("cannot free 'children': allocation escapes through argument 2 of method 'put'", """
                ArrayList<AllocationInfo> members = new ArrayList<AllocationInfo>(1);
                members.add(node);
                SnapshotList<AllocationInfo> children = new SnapshotList<AllocationInfo>(members);
                free members;
                analyzer.retainedBorrows().put(node, children);
                OwnershipSnapshot snapshot = analyzer.snapshotOwnership();
                analyzer.retainedBorrows().remove(node);
                free children; free snapshot; free analyzer; free source;
                return 0;""");
        programs.put("cannot free 'node': allocation escapes through argument 1 of method 'add'", """
                OwnershipSnapshot snapshot = analyzer.snapshotOwnership();
                free node; free snapshot; free analyzer; free source;
                return 0;""");
        // Retained limit, not a safety gap: versions lent through a list to an
        // analyzed method stay unfreeable, which is why joins take an owner.
        programs.put("cannot free 'one': argument to method 'read' can expose stored data-structure references", """
                OwnershipSnapshot one = analyzer.snapshotOwnership();
                OwnershipSnapshot two = analyzer.snapshotOwnership();
                ArrayList<OwnershipSnapshot> builder = new ArrayList<OwnershipSnapshot>(2);
                builder.add(one);
                builder.add(two);
                SnapshotList<OwnershipSnapshot> listed = new SnapshotList<OwnershipSnapshot>(builder);
                free builder;
                int size = Helpers.read(listed);
                free listed; free two; free one; free analyzer; free source;
                return size;""");
        check(programs, "ownership");
    }

    /** Callback counts match; services and captured state still observed cannot be freed. */
    static void callbacks() throws Exception {
        Map<String, String> programs = new LinkedHashMap<>();
        // Field-retained: the observer, its holder and the loop share one frame.
        programs.put("", """
                Counter counter = new Counter();
                FunctionOwnership observed = new FunctionOwnership(source, null, null, counter);
                for (int index = 0; index < 4; index++) {
                    OwnershipSnapshot snapshot = observed.snapshotOwnership();
                    defer free snapshot;
                    observed.restoreOwnership(snapshot);
                }
                free observed;
                int calls = counter.calls;
                free counter; free analyzer; free source;
                return calls == 8 ? 42 : 1;""");
        programs.put("cannot free 'counter': allocation is still borrowed by a live wrapper", """
                Counter counter = new Counter();
                FunctionOwnership observed = new FunctionOwnership(source, null, null, counter);
                free counter;
                OwnershipSnapshot snapshot = observed.snapshotOwnership();
                free snapshot; free observed; free analyzer; free source;
                return 0;""");
        programs.put("cannot free 'rounds': allocation is retained as captured variable 'rounds' by 'Main$1'", """
                int[] rounds = new int[1];
                SemanticAnalysisObserver capturing = new SemanticAnalysisObserver() {
                    @Override public void analyzerCreated(long token, OperationVariants.AnalyzerKind kind,
                            OperationVariants.AnalyzerPhase phase) { }
                    @Override public void analyzerRound(long token, OperationVariants.AnalyzerKind kind, int round) { }
                    @Override public void evidenceSnapshot(boolean saved, boolean sharedEmpty, int associations) {
                        rounds[0]++;
                    }
                };
                FunctionOwnership observed = new FunctionOwnership(source, null, null, capturing);
                OwnershipSnapshot snapshot = observed.snapshotOwnership();
                free snapshot; free observed; free capturing; free rounds; free analyzer; free source;
                return 0;""");
        // Retained D253 limit, not a safety gap: a helper frame that loops over
        // a holder leaves the service it was given conservatively escaping.
        programs.put("cannot free 'counter': allocation escapes through argument 2 of method 'loop'", """
                Counter counter = new Counter();
                Helpers.loop(source, counter);
                free counter; free analyzer; free source;
                return 0;""");
        check(programs, "callback");
        Path root = Files.createTempDirectory("ironwood-ownership-callbacks-");
        try {
            Path executable = build(root, CALLBACKS, Map.of());
            String output = execute(List.of(executable.toString()), Map.of(), 42);
            String expected = """
                    immediate.named.callback=2
                    immediate.named.captured=0
                    immediate.named.calls=0
                    immediate.capturing.callback=1
                    immediate.capturing.captured=1
                    immediate.capturing.calls=0
                    retained.named.callback=2
                    retained.named.captured=0
                    retained.named.calls=0
                    """;
            if (!output.equals(expected)) throw new AssertionError("callback counts: " + output);
        } finally {
            delete(root);
        }
    }

    /** Native kernels equal the retained J0 results from classes and archive; only D262 payload survives retirement. */
    static void artifacts() throws Exception {
        Path root = Files.createTempDirectory("ironwood-ownership-pilot-");
        try {
            Path references = root.resolve("references");
            Files.createDirectories(references);
            extract(M0 + "ownership-resources.tar.gz", references, "ordered");
            extract(M0 + "kernel-resources.tar.gz", references, "ordered");
            extract(M0 + "loop-cycle-reference.tar.gz", references, "cycle-ordered");
            Path classes = root.resolve("classes");
            List<String> arguments = new ArrayList<>(portSourcePaths());
            arguments.addAll(List.of(ADAPTER, "--unfreed=warn", "-d", classes.toString()));
            run(arguments, Map.of());
            Path archive = root.resolve("kernels.ironjar");
            ByteArrayOutputStream ignored = new ByteArrayOutputStream();
            if (IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()},
                    new PrintStream(ignored), new PrintStream(ignored)) != 0) {
                throw new AssertionError("kernel archive: " + ignored);
            }
            for (Path input : List.of(classes, archive)) {
                Path executable = root.resolve(input.getFileName() + "-capture");
                run(List.of("--link", "-cp", input.toString(), "--main-class", "KernelCapture", "--unfreed=warn",
                        "-O3", "-o", executable.toString()), Map.of());
                for (String[] configuration : configurations()) {
                    String label = configuration[5];
                    Path output = root.resolve(input.getFileName() + "-" + label);
                    Files.createDirectories(output);
                    execute(List.of(executable.toString(), configuration[1], configuration[2], configuration[3],
                            configuration[4], output.toString()), Map.of(), 0);
                    Path reference = references.resolve(configuration[0].equals("effect-cycle") ? "cycle-ordered" : "ordered")
                            .resolve(label + "-sample0-r0/result.txt");
                    if (!Files.readString(output.resolve("result.txt")).equals(Files.readString(reference))) {
                        throw new AssertionError("native kernel differs from J0: " + label);
                    }
                    Map<String, Long> metrics = metrics(output.resolve("metrics.txt"));
                    long retained = metrics.get("liveAfterRetirement") - metrics.get("liveAfterSetup");
                    if (retained != invocationLived(configuration, metrics)) {
                        throw new AssertionError("kernel temporaries outstanding: " + label + " retained " + retained);
                    }
                }
                Path inputs = root.resolve(input.getFileName() + "-inputs");
                Files.createDirectories(inputs);
                execute(List.of(executable.toString(), "inputs", "2", "1", "observer-off", inputs.toString()), Map.of(), 0);
                String expected = """
                        admitted chain 0
                        pilot input rejects this unique function names
                        pilot input rejects this callable kind
                        pilot input rejects this one entry block
                        pilot input rejects this call kind
                        pilot input rejects this foreign target
                        pilot input rejects this foreign result
                        pilot input rejects this value return
                        pilot input rejects this value number
                        admitted allocation
                        pilot input rejects this allocation state
                        """;
                if (!Files.readString(inputs.resolve("result.txt")).equals(expected)) {
                    throw new AssertionError("pilot input boundary: " + Files.readString(inputs.resolve("result.txt")));
                }
            }
        } finally {
            delete(root);
        }
    }

    /** Every allocation failure in snapshots, joins, evidence and effects unwinds through the deferred cleanup. */
    static void failures() throws Exception {
        Path root = Files.createTempDirectory("ironwood-ownership-failures-");
        try {
            Path executable = build(root, FAILURE, Map.of());
            int limit = 0;
            while (true) {
                int status = status(executable, Map.of("IRONWOOD_ALLOCATION_LIMIT", Integer.toString(limit)));
                if (status == 43) break;
                if (status != 42) throw new AssertionError("ownership allocation limit " + limit + ": exit " + status);
                limit++;
            }
            if (limit < 3000) throw new AssertionError("ownership sweep ended early at " + limit);
            if (status(executable, Map.of()) != 43) throw new AssertionError("ownership run without a limit failed");
        } finally {
            delete(root);
        }
    }

    // Accepted programs compile without diagnostics and rejected ones fail
    // with their message, under every --unfreed mode. All are compile-only.
    private static void check(Map<String, String> programs, String family) throws Exception {
        for (UnfreedMode mode : UnfreedMode.values()) {
            for (var program : programs.entrySet()) {
                List<SourceFile> sources = new ArrayList<>();
                for (String path : portSourcePaths()) sources.add(SourceFile.of("test/" + path, Files.readString(Path.of(path))));
                sources.add(SourceFile.of("test/Main.iron", HEAD + program.getValue() + "\n    }\n}\n"));
                CompilationArtifact artifact = new CompilerPipeline(mode).compile(sources);
                String messages = String.join("; ", artifact.diagnostics().stream().map(d -> d.message()).toList());
                boolean accepted = program.getKey().isEmpty() || program.getKey().equals("\u0000");
                if (accepted ? !artifact.successful() || !messages.isEmpty()
                        : artifact.successful() || !messages.contains(program.getKey())) {
                    throw new AssertionError(family + " control under " + mode + ": " + program.getKey() + ": " + messages);
                }
            }
        }
    }

    private static String[][] configurations() {
        List<String[]> result = new ArrayList<>();
        for (int size : new int[]{8, 32, 128}) {
            for (int shape = 1; shape <= 4; shape++) {
                for (int explain = 0; explain <= 1; explain++) {
                    result.add(new String[]{"ownership", "ownership", Integer.toString(size), Integer.toString(shape),
                            explain == 1 ? "explain-on" : "explain-off", "ownership-" + size + "-" + shape + "-observer" + explain});
                }
            }
            result.add(new String[]{"evidence", "evidence", Integer.toString(size), "1", "observer-off",
                    "evidence-" + size + "-1-observer0"});
        }
        for (String kind : List.of("effect", "effect-cycle")) {
            for (int[] shape : new int[][]{{8, 65}, {32, 65}, {128, 65}, {16, 8}, {16, 257}}) {
                for (int observed = 0; observed <= 1; observed++) {
                    result.add(new String[]{kind, kind, Integer.toString(shape[0]), Integer.toString(shape[1]),
                            observed == 1 ? "observer-on" : "observer-off",
                            kind + "-" + shape[0] + "-" + shape[1] + "-observer" + observed});
                }
            }
        }
        return result.toArray(String[][]::new);
    }

    // The D262 payload derived in OWNERSHIP.md: per ownership iteration seven
    // state versions, two child versions and, with recorded explanations, five
    // events; per evidence iteration one join and one event; plus result text.
    private static long invocationLived(String[] configuration, Map<String, Long> metrics) {
        if (configuration[0].equals("ownership")) {
            boolean events = configuration[4].equals("explain-on") && Integer.parseInt(configuration[3]) < 3;
            if (metrics.get("recordedEvents") != (events ? 64 * 5 : 0)) {
                throw new AssertionError("ownership kernel events: " + metrics.get("recordedEvents"));
            }
            return 64 * (7 + 2 * metrics.get("childVersionAllocations") + (events ? 5 : 0)) + 1;
        }
        return configuration[0].equals("evidence") ? 128 : 1;
    }

    private static Map<String, Long> metrics(Path path) throws Exception {
        Map<String, Long> result = new TreeMap<>();
        for (String line : Files.readAllLines(path)) {
            int split = line.indexOf('=');
            result.put(line.substring(0, split), Long.parseLong(line.substring(split + 1)));
        }
        return result;
    }

    private static Path build(Path root, String fixture, Map<String, Integer> warnings) throws Exception {
        Path classes = root.resolve("classes");
        List<String> arguments = new ArrayList<>(portSourcePaths());
        arguments.addAll(List.of(fixture, "--unfreed=warn", "-d", classes.toString()));
        run(arguments, warnings);
        Path executable = root.resolve("program");
        run(List.of("--link", "-cp", classes.toString(), "--main-class", "Main", "--unfreed=warn", "-O3",
                "-o", executable.toString()), warnings);
        return executable;
    }

    private static void extract(String archive, Path destination, String member) throws Exception {
        Process process = new ProcessBuilder("tar", "-xzf", archive, "-C", destination.toString(), member)
                .redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new AssertionError("reference extraction " + archive + ": " + new String(output, StandardCharsets.UTF_8));
        }
    }

    private static List<String> portSourcePaths() throws Exception {
        try (Stream<Path> files = Files.walk(Path.of(PORT))) {
            return files.filter(path -> path.toString().endsWith(".iron")).map(Path::toString).sorted().toList();
        }
    }

    private static void run(List<String> arguments, Map<String, Integer> warnings) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            int exit = Main.run(new ArrayList<>(arguments).toArray(String[]::new), stream, stream);
            String text = output.toString(StandardCharsets.UTF_8);
            Map<String, Integer> found = new TreeMap<>();
            text.lines().filter(line -> line.startsWith("warning") || line.startsWith("error"))
                    .forEach(line -> found.merge(line, 1, Integer::sum));
            if (exit != 0 || !found.equals(new TreeMap<>(warnings))) {
                throw new AssertionError("ownership compiler run " + arguments + ": exit " + exit + ": " + text);
            }
        }
    }

    private static String execute(List<String> command, Map<String, String> environment, int expected) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("ownership program timed out");
        }
        String text = new String(output, StandardCharsets.UTF_8);
        if (process.exitValue() != expected) throw new AssertionError("ownership program exit " + process.exitValue() + ": " + text);
        return text;
    }

    private static int status(Path executable, Map<String, String> environment) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(executable.toString()).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        process.getInputStream().readAllBytes();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("ownership failure program timed out");
        }
        return process.exitValue();
    }

    private static void delete(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
