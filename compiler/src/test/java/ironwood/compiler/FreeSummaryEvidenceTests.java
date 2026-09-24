// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.DiagnosticFormatter;
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

    static void isolatedExhaustion() {
        SourceFile source = SourceFile.of("Limit.iron", """
                class Limit {
                    static Object saved;
                    static void keep(Object value) { saved = value; }
                    static void forward(Object value) { keep(value); }
                    static void example() {
                        Object value = new Object();
                        forward(value);
                        free value;
                    }
                }
                """);
        CompilationArtifact baseline = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .analyze(List.of(source));
        requireRejected(baseline);
        SemanticObserverBridge.Counts complete = new SemanticObserverBridge.Counts();
        requireRejected(new CompilerPipeline(UnfreedMode.OFF, true,
                (mode, sources, explain) -> SemanticObserverBridge.create(
                        mode, sources, explain, complete, source.path())).analyze(List.of(source)));
        for (int[] limits : List.of(new int[] { 4, 64, 1_048_576 },
                new int[] { 2_048, 3, 1_048_576 },
                new int[] { 2_048, 64, 1 })) {
            SemanticObserverBridge.Counts counts = new SemanticObserverBridge.Counts();
            CompilationArtifact limited = new CompilerPipeline(UnfreedMode.OFF, true,
                    (mode, sources, explain) -> SemanticObserverBridge.createWithSummaryLimits(
                            mode, sources, explain, counts, source.path(),
                            limits[0], limits[1], limits[2])).analyze(List.of(source));
            requireRejected(limited);
            require(limited.diagnostics().stream().filter(d -> d.isError())
                            .map(d -> d.source().path() + ":" + d.span() + ":" + d.message()).toList()
                            .equals(baseline.diagnostics().stream().filter(d -> d.isError())
                                    .map(d -> d.source().path() + ":" + d.span() + ":" + d.message())
                                    .toList()),
                    "summary evidence exhaustion changed mandatory safety: " + limits[0]
                            + "/" + limits[1] + "/" + limits[2]);
            require(counts.summaryEvidenceRetired(), "exhausted summaries were not retired");
            require(counts.projections().equals(complete.projections())
                            && counts.entered() == complete.entered()
                            && counts.outcomes() == complete.outcomes()
                            && counts.stable() == complete.stable()
                            && counts.fieldComparisons() == complete.fieldComparisons(),
                    "summary evidence exhaustion changed selected proofs or convergence");
            if (limits[2] == 1) {
                require(counts.summaryInvocationStopped(),
                        "aggregate stop did not report its distinct reason");
            } else {
                require(counts.summaryMethodTruncated() && !counts.summaryInvocationStopped(),
                        "method or fact cap did not remain separate from the aggregate stop");
            }
        }
    }

    static void renderedCallChains() {
        for (String name : List.of("Chain", "Cycle")) {
            SourceFile source = SourceFile.of(name + ".iron",
                    name.equals("Chain") ? CHAIN : CYCLE);
            CompilationArtifact enabled = new CompilerPipeline(UnfreedMode.OFF, true, null)
                    .analyze(List.of(source));
            CompilationArtifact disabled = new CompilerPipeline(UnfreedMode.OFF, false, null)
                    .analyze(List.of(source));
            requireRejected(enabled);
            requireRejected(disabled);
            var errors = enabled.diagnostics().stream().filter(d -> d.isError()).toList();
            require(errors.stream().map(d -> d.message() + "/" + d.source().path()
                            + "/" + d.span()).toList().equals(disabled.diagnostics().stream()
                            .filter(d -> d.isError()).map(d -> d.message() + "/"
                                    + d.source().path() + "/" + d.span()).toList()),
                    "call notes changed selected primary diagnostics");
            var error = errors.stream().filter(d -> d.message().startsWith("cannot free 'data':"))
                    .findFirst().orElseThrow();
            var notes = error.notes();
            int expected = name.equals("Chain") ? 4 : 3;
            require(notes.size() == expected, "wrong final call chain: " + notes);
            require(notes.getFirst().message().contains("this call passes the allocation as argument 1")
                            && notes.getFirst().span().start().line()
                            == (name.equals("Chain") ? 23 : 38),
                    "call application lost its selected operand: " + notes);
            require(notes.getLast().message().contains("static field '" + name + ".saved'")
                            && notes.getLast().source().path().equals(source.path())
                            && notes.getLast().span().start().line()
                            == (name.equals("Chain") ? 17 : 15),
                    "chain did not terminate at the final store: " + notes);
            for (var note : notes) {
                require(note.source() != null && note.source().path().equals(source.path()),
                        "chain note lost source identity: " + notes);
            }
        }
        SourceFile limitedSource = SourceFile.of("Chain.iron", CHAIN);
        SemanticObserverBridge.Counts limitedCounts = new SemanticObserverBridge.Counts();
        CompilationArtifact limited = new CompilerPipeline(UnfreedMode.OFF, true,
                (mode, sources, explain) -> SemanticObserverBridge.createWithSummaryLimits(
                        mode, sources, explain, limitedCounts, limitedSource.path(),
                        2_048, 3, 1_048_576)).analyze(List.of(limitedSource));
        requireRejected(limited);
        var boundary = limited.diagnostics().stream().filter(d -> d.message().startsWith(
                "cannot free 'data':")).findFirst().orElseThrow().notes();
        require(limitedCounts.summaryMethodTruncated()
                        && !limitedCounts.summaryInvocationStopped()
                        && boundary.size() == 2
                        && boundary.getLast().message().contains("summary evidence limit"),
                "exhausted callee evidence invented a source chain: " + boundary);
        SourceFile safe = SourceFile.of("Case.iron", TEMPORARY_BORROW);
        requireAccepted(new CompilerPipeline(UnfreedMode.OFF, true, null).analyze(List.of(safe)));
        CompilationArtifact skipped = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(safe, SourceFile.of("OverrideError.iron", MISSING_OVERRIDE)));
        var secondary = skipped.diagnostics().stream().filter(d -> d.message().equals(
                "cannot free 'item': allocation escapes through argument 1 of method 'use'"))
                .toList();
        require(secondary.size() == 2 && secondary.stream().allMatch(d -> d.notes().size() == 1
                        && d.notes().getFirst().message().contains("analysis was limited")),
                "skipped refinement exposed a discarded temporary-borrow chain");
        String publishing = TEMPORARY_BORROW
                .replace("class Case {", "class Case {\n    static Item saved;")
                .replace("wrapper.touch();", "wrapper.touch();\n        saved = item;");
        CompilationArtifact published = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(SourceFile.of("Case.iron", publishing)));
        var actual = published.diagnostics().stream().filter(d -> d.message().startsWith(
                "cannot free 'item': allocation escapes through argument 1"))
                .findFirst().orElseThrow();
        require(actual.notes().size() >= 2 && actual.notes().size() <= 8
                        && actual.notes().stream().anyMatch(note -> note.message()
                        .contains("Case.saved")),
                "real helper publication lacked its final supported store: " + actual.notes());
    }

    static void boundedAndStableCallNotes() {
        StringBuilder sourceText = new StringBuilder("class LongChain {\n"
                + "    static Object saved;\n");
        for (int index = 0; index < 5; index++) {
            sourceText.append("    static void hop").append(index)
                    .append("(Object value) { hop").append(index + 1)
                    .append("(value); }\n");
        }
        sourceText.append("    static void hop5(Object value) { saved = value; }\n"
                + "    static void example() {\n"
                + "        Object value = new Object();\n"
                + "        hop0(value);\n"
                + "        free value;\n"
                + "    }\n"
                + "}\n");
        SourceFile plain = SourceFile.of("LongChain.iron", sourceText.toString());
        CompilationArtifact base = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(plain));
        requireRejected(base);
        var original = base.diagnostics().stream().filter(d -> d.message().startsWith(
                "cannot free 'value':")).findFirst().orElseThrow().notes();
        require(original.size() == 6 && original.size() <= 8
                        && original.getLast().message().contains("omitted after four summary hops"),
                "long chain exceeded its output limit: " + original);

        SourceFile user = SourceFile.of("Chain.iron", CHAIN);
        StringBuilder extraText = new StringBuilder("package extra;\n"
                + "public final class Unrelated {\n"
                + "    public static Object saved;\n");
        for (int index = 0; index < 120; index++) {
            extraText.append("    public static void keep").append(index)
                    .append("(Object value) { saved = value; }\n");
        }
        extraText.append("}\n");
        SourceFile extra = SourceFile.of("extra/Unrelated.iron", extraText.toString());
        SourceFile companion = SourceFile.of("Bridge.iron", """
                import extra.Unrelated;
                class Bridge {
                    static void use() {
                        Object value = new Object();
                        Unrelated.keep0(value);
                    }
                }
                """);
        SemanticObserverBridge.Counts baselineCounts = new SemanticObserverBridge.Counts();
        CompilationArtifact baseline = new CompilerPipeline(UnfreedMode.OFF, true,
                (mode, sources, explain) -> SemanticObserverBridge.create(
                        mode, sources, explain, baselineCounts, user.path()))
                .analyze(List.of(user));
        var baselineError = baseline.diagnostics().stream().filter(d -> d.message().startsWith(
                "cannot free 'data':")).findFirst().orElseThrow();
        for (List<SourceFile> sources : List.of(List.of(extra, companion, user),
                List.of(user, extra, companion))) {
            SemanticObserverBridge.Counts counts = new SemanticObserverBridge.Counts();
            CompilationArtifact noisy = new CompilerPipeline(UnfreedMode.OFF, true,
                    (mode, paths, explain) -> SemanticObserverBridge.create(
                            mode, paths, explain, counts, user.path())).analyze(sources);
            CompilationArtifact disabled = new CompilerPipeline(UnfreedMode.OFF, false, null)
                    .analyze(sources);
            var error = noisy.diagnostics().stream().filter(d -> d.message().startsWith(
                    "cannot free 'data':")).findFirst().orElseThrow();
            requireRejected(noisy);
            requireRejected(disabled);
            require(error.message().equals(baselineError.message())
                            && error.source().path().equals(baselineError.source().path())
                            && error.span().equals(baselineError.span())
                            && error.notes().equals(baselineError.notes())
                            && new DiagnosticFormatter().format(error).equals(
                            new DiagnosticFormatter().format(baselineError))
                            && disabled.diagnostics().stream().filter(d -> d.isError())
                            .map(d -> d.message() + "/" + d.span()).toList()
                            .equals(noisy.diagnostics().stream().filter(d -> d.isError())
                                    .map(d -> d.message() + "/" + d.span()).toList()),
                    "unrelated imported witnesses changed the selected user chain");
            require(counts.selectedSummaryWitnesses().keySet().stream()
                            .filter(key -> key.contains("Unrelated.keep")
                                    && key.contains("/NON_RETURN_ESCAPE/0/"))
                            .count() >= 120
                            && !counts.summaryInvocationStopped(),
                    "companion import did not load and retain its independent summary facts");
            for (String kind : List.of("ESCAPE", "SYMBOLIC_RETURN")) {
                var originalFacts = baselineCounts.projections().get(kind);
                var noisyFacts = counts.projections().get(kind);
                for (String key : originalFacts.keySet()) {
                    if (key.contains("ironwood.Chain.")) {
                        require(originalFacts.get(key).equals(noisyFacts.get(key)),
                                "import changed a final user summary: " + key);
                    }
                }
            }
            SourceFile acceptedUser = SourceFile.of("Chain.iron",
                    CHAIN.replace("saved = value;", ""));
            List<SourceFile> acceptedSources = sources.stream()
                    .map(source -> source == user ? acceptedUser : source).toList();
            CompilationArtifact acceptedOn = new CompilerPipeline(UnfreedMode.OFF, true, null)
                    .analyze(acceptedSources);
            CompilationArtifact acceptedOff = new CompilerPipeline(UnfreedMode.OFF, false, null)
                    .analyze(acceptedSources);
            requireAccepted(acceptedOn);
            requireAccepted(acceptedOff);
            require(acceptedOn.llvmIr().equals(acceptedOff.llvmIr()),
                    "unrelated import changed accepted enabled output");
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
