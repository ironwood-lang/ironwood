// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;
import ironwood.compiler.semantic.SemanticObserverBridge;

import java.util.ArrayList;
import java.util.List;

final class FreeSummaryEvidenceTests {
    private static final String CHAIN = """
            class Chain {

                static byte[] saved;

                static void first(byte[] value) {

                    second(value);
                }

                static void second(byte[] value) {

                    third(value);
                }

                static void third(byte[] value) {

                    saved = value;
                }

                static void example() {

                    byte[] data = new byte[16];
                    first(data);
                    free data;
                }
            }
            """;
    private static final String CYCLE = """
            class Cycle {

                static byte[] saved;

                static void ping(byte[] value, int count) {

                    if (count > 0) {
                        pong(value, count - 1);
                    }
                }

                static void pong(byte[] value, int count) {

                    if (count == 0) {
                        saved = value;
                    } else {
                        ping(value, count - 1);
                    }
                }

                static void safePing(byte[] value, int count) {

                    if (count > 0) {
                        safePong(value, count - 1);
                    }
                }

                static void safePong(byte[] value, int count) {

                    if (count > 0) {
                        safePing(value, count - 1);
                    }
                }

                static void example() {

                    byte[] data = new byte[16];
                    ping(data, 3);
                    free data;
                }

                static void safeExample() {

                    byte[] data = new byte[16];
                    safePing(data, 3);
                    free data;
                }
            }
            """;
    private static final String TEMPORARY_BORROW = """
            class Item {

                int value;
            }

            class Wrapper {

                private final Item item;

                Wrapper(Item item) {

                    this.item = item;
                }

                void touch() {

                    this.item.value++;
                }
            }

            class Case {

                static void use(Item item) {

                    Wrapper wrapper = new Wrapper(item);
                    defer free wrapper;
                    wrapper.touch();
                }

                static int check() {

                    Item item = new Item();
                    defer free item;
                    use(item);
                    return item.value;
                }
            }
            """;
    private static final String MISSING_OVERRIDE = """
            class Parent {
                void touch() {}
            }
            class Child extends Parent {
                void touch() {}
            }
            """;

    private FreeSummaryEvidenceTests() {}

    static void summaryBaselines() {
        rejectedCall("Chain", CHAIN, "first", 24, 14);
        accepted("Chain", CHAIN.replace("saved = value;", ""));
        rejectedCall("Cycle", CYCLE, "ping", 39, 14);
        // Keep the publishing helpers, but make both callers enter the safe cycle.
        accepted("Cycle", CYCLE.replace("ping(data, 3);", "safePing(data, 3);"));
        accepted("Cycle", CYCLE.replace("saved = value;", ""));

        accepted("Case", TEMPORARY_BORROW);
        CompilationArtifact earlyError = analyze("Case", TEMPORARY_BORROW,
                SourceFile.of("OverrideError.iron", MISSING_OVERRIDE));
        requireRejected(earlyError);
        require(earlyError.diagnostics().stream().anyMatch(d -> d.isError()
                        && d.message().contains("must be declared @Override")),
                "missing override error: " + earlyError.diagnostics());
        String reason = "cannot free 'item': allocation escapes through argument 1 of method 'use'";
        var secondary = earlyError.diagnostics().stream()
                .filter(d -> d.isError() && d.message().equals(reason)).toList();
        require(secondary.size() == 2, "expected two fallback cleanup rejections: " + earlyError.diagnostics());
        for (var error : secondary) {
            require(error.source().path().toString().equals("Case.iron")
                            && error.span().start().line() == 33 && error.span().start().column() == 20,
                    "fallback primary moved: " + error);
        }
        CompilationArtifact corrected = analyze("Case", TEMPORARY_BORROW,
                SourceFile.of("OverrideError.iron", MISSING_OVERRIDE.replace(
                        "class Child extends Parent {", "class Child extends Parent {\n    @Override")));
        requireAccepted(corrected);
        // Completed refinement must still reject real publication by the helper.
        String retaining = TEMPORARY_BORROW.replace("class Case {", "class Case {\n    static Item saved;")
                .replace("wrapper.touch();", "wrapper.touch();\n        saved = item;");
        CompilationArtifact unsafe = analyze("Case", retaining);
        requireRejected(unsafe);
        require(unsafe.diagnostics().stream().anyMatch(d -> d.isError() && d.message().equals(reason)),
                "retaining helper was not rejected: " + unsafe.diagnostics());
    }

    static void directRawWitnesses() {
        for (String name : List.of("Chain", "Cycle")) {
            SourceFile source = SourceFile.of(name + ".iron",
                    name.equals("Chain") ? CHAIN : CYCLE);
            SemanticObserverBridge.Counts counts = new SemanticObserverBridge.Counts();
            CompilationArtifact enabled = new CompilerPipeline(UnfreedMode.OFF, true,
                    (mode, sources, explain) -> SemanticObserverBridge.create(
                            mode, sources, explain, counts, source.path())).analyze(List.of(source));
            SemanticObserverBridge.Counts disabledCounts = new SemanticObserverBridge.Counts();
            CompilationArtifact disabled = new CompilerPipeline(UnfreedMode.OFF, false,
                    (mode, sources, explain) -> SemanticObserverBridge.create(
                            mode, sources, explain, disabledCounts, source.path())).analyze(List.of(source));
            requireRejected(enabled);
            requireRejected(disabled);
            require(enabled.diagnostics().stream().filter(d -> d.isError()).map(d -> d.message()).toList()
                            .equals(disabled.diagnostics().stream().filter(d -> d.isError())
                                    .map(d -> d.message()).toList()),
                    "raw witness collection changed the rejection");
            var witnesses = counts.selectedSummaryWitnesses();
            String terminal = name.equals("Chain") ? "Chain.third" : "Cycle.pong";
            int line = name.equals("Chain") ? 17 : 15;
            require(witnesses.entrySet().stream().anyMatch(entry ->
                            entry.getKey().contains(terminal + "/RAW_ESCAPE/0/")
                                    && entry.getValue().contains(name + ".iron:" + line + ":")
                                    && entry.getValue().contains("static field '" + name + ".saved'")),
                    "missing final direct store witness: " + witnesses);
            String forwarder = name.equals("Chain") ? "Chain.first" : "Cycle.ping";
            String next = name.equals("Chain") ? "Chain.second" : "Cycle.pong";
            require(witnesses.entrySet().stream().anyMatch(entry ->
                            entry.getKey().contains(forwarder + "/RAW_ESCAPE/0/")
                                    && entry.getValue().contains("-> ironwood." + next)),
                    "missing immutable call dependency: " + witnesses);
            if (name.equals("Chain")) {
                require(witnesses.entrySet().stream().anyMatch(entry ->
                                entry.getKey().contains("Chain.second/RAW_ESCAPE/0/")
                                        && entry.getValue().contains("-> ironwood.Chain.third")),
                        "middle call dependency did not retain its cause: " + witnesses);
            }
            require(disabledCounts.selectedSummaryWitnesses().isEmpty()
                            && disabledCounts.summaryEvidencePresent() == 0,
                    "disabled analysis retained summary evidence");
        }
    }

    static void symbolicDirectWitnesses() {
        SourceFile chain = SourceFile.of("Chain.iron", CHAIN);
        SemanticObserverBridge.Counts chainCounts = new SemanticObserverBridge.Counts();
        CompilationArtifact rejected = new CompilerPipeline(UnfreedMode.OFF, true,
                (mode, sources, explain) -> SemanticObserverBridge.create(
                        mode, sources, explain, chainCounts, chain.path())).analyze(List.of(chain));
        requireRejected(rejected);
        require(chainCounts.selectedSummaryWitnesses().entrySet().stream().anyMatch(entry ->
                        entry.getKey().contains("Chain.third/NON_RETURN_ESCAPE/0/")
                                && entry.getValue().contains("Chain.iron:17:17")
                                && entry.getValue().contains("static field 'Chain.saved'")),
                "symbolic direct store was not retained as a distinct final fact");
        for (String hop : List.of("first", "second")) {
            String next = hop.equals("first") ? "second" : "third";
            require(chainCounts.selectedSummaryWitnesses().entrySet().stream().anyMatch(entry ->
                            entry.getKey().contains("Chain." + hop + "/NON_RETURN_ESCAPE/0/")
                                    && entry.getValue().contains("-> ironwood.Chain." + next)),
                    "symbolic call fact lacks its exact earlier dependency");
        }
        SourceFile cycle = SourceFile.of("Cycle.iron", CYCLE);
        SemanticObserverBridge.Counts cycleCounts = new SemanticObserverBridge.Counts();
        requireRejected(new CompilerPipeline(UnfreedMode.OFF, true,
                (mode, sources, explain) -> SemanticObserverBridge.create(
                        mode, sources, explain, cycleCounts, cycle.path())).analyze(List.of(cycle)));
        var cycleWitnesses = cycleCounts.selectedSummaryWitnesses();
        require(cycleWitnesses.entrySet().stream().anyMatch(entry ->
                        entry.getKey().contains("Cycle.pong/NON_RETURN_ESCAPE/0/")
                                && entry.getValue().contains("Cycle.iron:15:21")
                                && entry.getValue().contains("static field 'Cycle.saved'"))
                        && cycleWitnesses.entrySet().stream().anyMatch(entry ->
                        entry.getKey().contains("Cycle.ping/NON_RETURN_ESCAPE/0/")
                                && entry.getValue().contains("-> ironwood.Cycle.pong"))
                        && cycleWitnesses.keySet().stream().noneMatch(key ->
                        key.contains("Cycle.safePing/NON_RETURN_ESCAPE/")
                                || key.contains("Cycle.safePong/NON_RETURN_ESCAPE/")),
                "recursive source lost the direct store or gained a safe-cycle escape");

        SourceFile returns = SourceFile.of("Provenance.iron", """
                class Provenance {
                    static Object alias(Object value) { return value; }
                    static Object fresh() { return new Object(); }
                }
                """);
        SemanticObserverBridge.Counts counts = new SemanticObserverBridge.Counts();
        CompilationArtifact enabled = new CompilerPipeline(UnfreedMode.OFF, true,
                (mode, sources, explain) -> SemanticObserverBridge.create(
                        mode, sources, explain, counts, returns.path())).analyze(List.of(returns));
        CompilationArtifact disabled = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .analyze(List.of(returns));
        requireAccepted(enabled);
        requireAccepted(disabled);
        var witnesses = counts.selectedSummaryWitnesses();
        require(witnesses.keySet().stream().noneMatch(key ->
                        key.contains("StringBuilder.append$char/RAW_ESCAPE/-1/")),
                "audited borrowing left a raw receiver witness in the final map");
        require(witnesses.entrySet().stream().anyMatch(entry ->
                        entry.getKey().contains("Provenance.alias/RETURN_ALIAS/0/")
                                && entry.getValue().contains("Provenance.iron:2:")),
                "parameter return origin was not recorded");
        require(witnesses.entrySet().stream().anyMatch(entry ->
                        entry.getKey().contains("Provenance.fresh/FRESH_RETURN/-1/")
                                && entry.getValue().contains("Provenance.iron:3:")),
                "fresh return origin was not recorded");
    }

    static void discoveryOrder() {
        SourceFile source = SourceFile.of("Order.iron", """
                class Order {
                    static Object a;
                    static Object b;
                    static void keep(Object first, Object second) {
                        b = second;
                        a = first;
                    }
                    static void join(Object first, Object second, boolean choice) {
                        Object selected = choice ? first : second;
                        a = selected;
                    }
                }
                """);
        SemanticObserverBridge.Counts counts = new SemanticObserverBridge.Counts();
        requireAccepted(new CompilerPipeline(UnfreedMode.OFF, true,
                (mode, sources, explain) -> SemanticObserverBridge.create(
                        mode, sources, explain, counts, source.path())).analyze(List.of(source)));
        var witnesses = counts.selectedSummaryWitnesses();
        for (String effect : List.of("RAW_ESCAPE", "NON_RETURN_ESCAPE")) {
            require(ordinal(witnesses, "Order.keep/" + effect + "/1/")
                            < ordinal(witnesses, "Order.keep/" + effect + "/0/"),
                    "source discovery order was replaced by parameter order for " + effect);
            require(ordinal(witnesses, "Order.join/" + effect + "/0/")
                            < ordinal(witnesses, "Order.join/" + effect + "/1/"),
                    "one merged event did not use stable parameter-role order for " + effect);
        }
    }

    private static long ordinal(java.util.Map<String, String> witnesses, String keyPart) {
        String value = witnesses.entrySet().stream()
                .filter(entry -> entry.getKey().contains(keyPart))
                .map(java.util.Map.Entry::getValue).findFirst()
                .orElseThrow(() -> new AssertionError("missing witness " + keyPart));
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("ordinal=(\\d+)")
                .matcher(value);
        if (!matcher.find()) throw new AssertionError("missing ordinal in " + value);
        return Long.parseLong(matcher.group(1));
    }

    private static void rejectedCall(String name, String source, String callee, int line, int column) {
        CompilationArtifact artifact = analyze(name, source);
        requireRejected(artifact);
        var errors = artifact.diagnostics().stream().filter(d -> d.isError()).toList();
        require(errors.size() == 1, name + " unexpected diagnostics: " + artifact.diagnostics());
        var error = errors.getFirst();
        require(error.message().equals("cannot free 'data': allocation escapes through argument 1 of method '"
                        + callee + "'"), name + " wrong primary: " + error);
        require(error.source().path().toString().equals(name + ".iron")
                        && error.span().start().line() == line && error.span().start().column() == column,
                name + " primary moved: " + error);
    }

    private static void accepted(String name, String source) {
        requireAccepted(analyze(name, source));
    }

    private static void requireAccepted(CompilationArtifact artifact) {
        require(artifact.valid() && artifact.diagnostics().isEmpty(),
                "safe control rejected: " + artifact.diagnostics());
    }

    private static void requireRejected(CompilationArtifact artifact) {
        require(!artifact.valid() && artifact.program().isEmpty() && artifact.llvmIr().isEmpty(),
                "invalid source produced a program: " + artifact.diagnostics());
    }

    private static CompilationArtifact analyze(String name, String source, SourceFile... additional) {
        List<SourceFile> sources = new ArrayList<>();
        sources.add(SourceFile.of(name + ".iron", source));
        sources.addAll(List.of(additional));
        return new CompilerPipeline(UnfreedMode.OFF).analyze(sources);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
