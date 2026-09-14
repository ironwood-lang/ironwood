// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class FreshBulkResultTests {
    private FreshBulkResultTests() {}

    static void detachAndMutation() throws Exception {
        String source = Files.readString(Path.of("integration-tests/cases/fresh_bulk_results/Main.iron"));
        accept(source, "distinct elements from a fresh result");
        accept(source.replace("int total = 0;", "AddressValue kept = results[0]; results[0] = null; int total = 0;")
                .replace("total += item.value;", "if (item != null) total += item.value;")
                .replace("free results;", "free results; total += kept.value; free kept;"),
                "detached element survives array reclamation");
        reject(source.replace("results[i] = null;", ""), "missing detachment");
        reject(source.replace("free item;", ""), "missing detached-element cleanup under unfreed=error");
        CompilationArtifact optional = new CompilerPipeline(UnfreedMode.OFF)
                .compile(SourceFile.of("test/Main.iron", source.replace("free item;", "")));
        if (!optional.successful()) throw new AssertionError("missing-free policy must remain optional: " + optional.diagnostics());
        reject(source.replace("results[i] = null;", "results[1 - i] = null;"), "detachment of a different slot");
        reject(source.replace("free item;", "free item; total += item.value;"), "use after element free");
        reject(source.replace("class Main {", "class Main { static AddressValue[] saved;")
                .replace("return result;", "Main.saved = result; return result;"), "published result array");
        for (String mutation : List.of(
                "AddressValue shared = new AddressValue(5); results[0] = shared;",
                "results[0] = results[1];",
                "AddressValue[] alias = results; alias[0] = alias[1];")) {
            reject(source.replace("int total = 0;", mutation + " int total = 0;"),
                    "mutated result: " + mutation);
        }
        reject(source.replace("AddressValue[] result = new AddressValue[count];",
                        "AddressValue shared = new AddressValue(5); AddressValue[] result = new AddressValue[count];")
                .replace("result[i] = new AddressValue(i);", "result[i] = shared;"), "duplicate factory elements");
        reject(source.replace("class AddressValue {", "class AddressValue { static AddressValue saved;")
                .replace("this.value = value;", "this.value = value; AddressValue.saved = this;"),
                "constructor publishes an element");
        reject(source.replace("int total = 0;", "int total = 0; AddressValue saved = results[0];")
                .replace("free results;", "free results; total += saved.value;"), "undetached element alias");
    }

    private static CompilationArtifact compile(String source) {
        return new CompilerPipeline(UnfreedMode.ERROR).compile(SourceFile.of("test/Main.iron", source));
    }

    private static void accept(String source, String description) {
        CompilationArtifact artifact = compile(source);
        if (!artifact.successful()) throw new AssertionError(description + ": " + artifact.diagnostics());
    }

    private static void reject(String source, String description) {
        CompilationArtifact artifact = compile(source);
        if (artifact.successful() || artifact.diagnostics().stream().noneMatch(diagnostic ->
                diagnostic.isError() && diagnostic.message().contains("free"))) {
            throw new AssertionError(description + " did not reject unsafe reclamation: " + artifact.diagnostics());
        }
    }
}
