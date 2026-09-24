// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.util.List;

final class OwnedArrayExplanationTests {
    private static final String DISTINCT = "each creation-array entry must receive a distinct fresh object exactly once";

    private OwnedArrayExplanationTests() {}

    static void firstPass() {
        rejected(source("items[0] = input;", ""), DISTINCT,
                "items[0] = input;", "no proved fresh object origin", null);
        accepted(source("Item value = new Item(); items[0] = value;", ""));

        rejected(source("Item value = new Item(); items[0] = value; items[1] = value;", ""),
                DISTINCT, "items[1] = value;", "store repeats an object",
                "items[0] = value;");
        accepted(source("Item first = new Item(); Item second = new Item(); items[0] = first; items[1] = second;", ""));

        accepted(source("for (int i = 0; i < 2; i++) { Item value = new Item(); items[i] = value; }", ""));

        rejected(source("System.arraycopy(items, 0, items, 1, 1);", ""),
                "creation-array copying must preserve each element once in fresh replacement storage",
                "System.arraycopy(items, 0, items, 1, 1)",
                "does not prove a single full transfer", null);
        accepted(source("Item value = new Item(); items[0] = value;", ""));

        rejected(source("observe(items);", "static void observe(Item[] values) {}"),
                "creation-array storage cannot be passed to an arbitrary call",
                "observe(items)", "receives the creation-array storage", null);
        accepted(source("Item value = new Item(); items[0] = value;", "static void observe(Item[] values) {}"));

        rejected(source("Item value = new Item(); items[0] = value;", "Item indirect() { Item value = items[0]; return value; }"),
                "creation-array elements require a direct dependent-borrow getter or destructor loop",
                "Item value = items[0];", "element load is outside a direct dependent-borrow getter", null);
        accepted(source("Item value = new Item(); items[0] = value;", "Item direct() { return this.items[0]; }"));

        rejected(source("Item value = new Item(); items[0] = value;", "int otherLength(Owner other) { return other.items.length; }"),
                "creation-array storage must remain private to its owner",
                "other.items", "receiver other than the owning object", null);
        accepted(source("Item value = new Item(); items[0] = value;", "int ownLength() { return items.length; }"));
    }

    private static String source(String body, String member) {
        return """
                class Item {}
                class Owner {
                    private Item[] items = new Item[2];
                    Owner(Item input) { %s }
                    %s
                    destructor {
                        for (int i = 0; i < this.items.length; i++) { free this.items[i]; }
                        free items;
                    }
                }
                """.formatted(body, member);
    }

    private static void rejected(String text, String reason, String site,
                                 String detail, String earlier) {
        SourceFile source = SourceFile.of("OwnedEvidence.iron", text);
        CompilationArtifact off = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .analyze(List.of(source));
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(source));
        require(!off.valid() && !on.valid() && off.program().isEmpty()
                        && on.program().isEmpty() && off.llvmIr().isEmpty()
                        && on.llvmIr().isEmpty() && off.diagnostics().size() == 1
                        && on.diagnostics().size() == 1,
                "owned-element rejection or artifact changed: " + on.diagnostics());
        var prior = off.diagnostics().getFirst();
        var explained = on.diagnostics().getFirst();
        require(explained.message().equals(prior.message())
                        && explained.message().equals("cannot prove owned elements of 'items' safe: " + reason)
                        && explained.source().path().equals(prior.source().path())
                        && explained.span().equals(prior.span())
                        && prior.notes().isEmpty()
                        && explained.notes().size() == (earlier == null ? 2 : 3)
                        && explained.notes().getFirst().message().contains(detail)
                        && explained.notes().getFirst().source().path().equals(source.path())
                        && within(text, site, explained.notes().getFirst().span().start().offset()),
                "owned-element first-pass note lost its operation for " + site
                        + " at " + text.indexOf(site) + ": " + explained);
        int cleanupIndex = earlier == null ? 1 : 2;
        require(explained.notes().get(cleanupIndex).message().contains("recognized destructor cleanup")
                        && explained.notes().get(cleanupIndex).source().path().equals(source.path())
                        && within(text, "free this.items[i];",
                                explained.notes().get(cleanupIndex).span().start().offset()),
                "owned-element explanation lost its recognized cleanup: " + explained);
        if (earlier != null) {
            require(explained.notes().get(1).message().contains("first recorded here")
                            && explained.notes().get(1).source().path().equals(source.path())
                            && within(text, earlier, explained.notes().get(1).span().start().offset()),
                    "repeated object lost its first store: " + explained);
        }
    }

    private static void accepted(String text) {
        SourceFile source = SourceFile.of("OwnedEvidence.iron", text);
        CompilationArtifact off = new CompilerPipeline(UnfreedMode.OFF, false, null)
                .analyze(List.of(source));
        CompilationArtifact on = new CompilerPipeline(UnfreedMode.OFF, true, null)
                .analyze(List.of(source));
        require(off.valid() && on.valid() && off.diagnostics().isEmpty()
                        && on.diagnostics().isEmpty() && off.program().equals(on.program()),
                "safe creation-array control changed: " + on.diagnostics());
    }

    private static boolean within(String text, String operation, int offset) {
        int start = text.indexOf(operation);
        return start >= 0 && offset >= start && offset < start + operation.length();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
