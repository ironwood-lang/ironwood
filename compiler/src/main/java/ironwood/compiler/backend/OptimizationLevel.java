// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

public enum OptimizationLevel {
    O0("0"),
    O1("1"),
    O2("2"),
    O3("3");

    private final String number;

    OptimizationLevel(String number) {
        this.number = number;
    }

    public String optPassPipeline() {
        return "default<O" + number + ">";
    }

    /**
     * Extra {@code opt} options for the selected level. The closed-world module
     * is optimized as a whole, so {@code -O3} inlines more aggressively than
     * LLVM's C-oriented default and partially inlines early-return methods,
     * mirroring the call-heavy Java style the language encourages.
     */
    public java.util.List<String> optExtraArguments() {
        return this == O3
                ? java.util.List.of("-inline-threshold=1000", "-enable-partial-inlining")
                : java.util.List.of();
    }

    public String llcArgument() {
        return "-O=" + number;
    }

    public String clangArgument() {
        return "-O" + number;
    }
}
