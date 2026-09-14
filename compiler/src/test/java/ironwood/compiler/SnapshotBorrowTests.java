// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class SnapshotBorrowTests {
    private SnapshotBorrowTests() {}

    static void rootsAndCursors() throws Exception {
        String source = Files.readString(Path.of("integration-tests/cases/snapshot_borrows/Main.iron"));
        CompilationArtifact safe = compile(source);
        if (!safe.successful()) throw new AssertionError("snapshot navigation: " + safe.diagnostics());
        for (String operation : List.of("free child;", "free snapshot; child.index();")) {
            reject(source.replace("int value = child.parent().index();",
                    operation + " int value = 0;"), "child borrow: " + operation);
        }
        reject(source.replace("free snapshot;", "free snapshot; retained.index();"), "retained view after root free");
        reject(source.replace("class Main {", "class Main { static View saved;")
                .replace("free snapshot;", "Main.saved = child; free snapshot;"), "escaped navigated view");
        reject(source.replace("View parent() {", "Snapshot revealOwner() { return this.owner; }\n View parent() {"),
                "hidden owner exposure");
        reject(source.replace("free cursor;", "free snapshot; free cursor;"), "root freed before live cursor");
        reject(source.replace("free enumeration;", "free enumeration; first.index();"), "enumeration view after root free");
        reject(source.replace("free nested;", "free enumeration; free nested;"), "enumeration root before cursor");
        reject(source.replace("free entries;", "free snapshot; free entries;"), "snapshot retained by result list");
        reject(source.replace("free entries;", "free listed; free entries;"), "list element remains a borrow");
        reject(source.replace("free snapshot;", "free snapshot; listed.index();"), "list element after snapshot free");
        String insertion = source.replace("entries.add(child);",
                "View extra = new View(snapshot, 0); entries.add(extra);")
                .replace("free entries;", "free entries; free extra;");
        CompilationArtifact inserted = compile(insertion);
        if (!inserted.successful()) throw new AssertionError("caller-added list entry: " + inserted.diagnostics());
        reject(insertion.replace("free entries; free extra;", "free extra; free entries;"), "caller-added entry before list");
        reject(source.replace("class Main {", "class Main { static ArrayList<View> saved;")
                .replace("return result;", "Main.saved = result; return result;"), "published borrowed-entry list");
        reject(source.replace("class Main {", "class Main { static Cursor saved;")
                .replace("free cursor;", "Main.saved = cursor; free cursor;"), "published cursor reclamation");
        reject(source.replace("class Cursor {", "class Cursor { static Snapshot saved;")
                .replace("this.snapshot = snapshot;", "this.snapshot = snapshot; Cursor.saved = snapshot;"),
                "cursor constructor publishes backing root");
        reject(source.replace("class Cursor {", "class Cursor { static Cursor saved;")
                .replace("this.snapshot = snapshot;", "this.snapshot = snapshot; Cursor.saved = this;"),
                "cursor constructor publishes result");
        reject(source.replace("return this.owner.cursor();",
                "Cursor result = this.owner.cursor(); Main.publish(result); return result;")
                .replace("class Main {", "class Main { static Cursor saved; static void publish(Cursor value) { saved = value; }"),
                "factory publishes returned cursor through helper");
    }

    private static CompilationArtifact compile(String source) {
        return new CompilerPipeline(UnfreedMode.ERROR).compile(SourceFile.of("test/Main.iron", source));
    }

    private static void reject(String source, String description) {
        CompilationArtifact artifact = compile(source);
        if (artifact.successful() || artifact.diagnostics().stream().noneMatch(diagnostic ->
                diagnostic.isError() && diagnostic.message().contains("free"))) {
            throw new AssertionError(description + " did not reject unsafe reclamation: " + artifact.diagnostics());
        }
    }
}
