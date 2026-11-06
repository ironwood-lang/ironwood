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
        accept(source, "fresh result");
        accept(source.replace("results[i] = null;", ""), "array retains its elements");
        for (String mutation : List.of("AddressValue shared = new AddressValue(5); results[0] = shared;",
                "results[0] = results[1];", "AddressValue[] alias = results; alias[0] = alias[1];")) {
            accept(source.replace("int total = 0;", mutation + " int total = 0;"), "shared array element");
        }
        accept(source.replace("class AddressValue {", "class AddressValue { static AddressValue saved;")
                .replace("this.value = value;", "this.value = value; AddressValue.saved = this;"), "published element");
    }

    private static CompilationArtifact compile(String source) {
        return new CompilerPipeline().compile(SourceFile.of("test/Main.iron", source));
    }

    private static void accept(String source, String description) {
        CompilationArtifact artifact = compile(source);
        if (!artifact.successful()) throw new AssertionError(description + ": " + artifact.diagnostics());
    }

}
