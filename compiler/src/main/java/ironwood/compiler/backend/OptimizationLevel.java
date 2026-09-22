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
        return optExtraArguments(null);
    }

    public java.util.List<String> optExtraArguments(Integer inlineThreshold) {
        if (inlineThreshold != null && inlineThreshold < 0) {
            throw new IllegalArgumentException("inline threshold must be nonnegative");
        }
        var arguments = new java.util.ArrayList<String>();
        if (inlineThreshold != null || this == O3) {
            arguments.add("-inline-threshold=" + (inlineThreshold == null ? 1000 : inlineThreshold));
        }
        if (this == O3) arguments.add("-enable-partial-inlining");
        return java.util.List.copyOf(arguments);
    }

    public String llcArgument() {
        return "-O=" + number;
    }

    public String clangArgument() {
        return "-O" + number;
    }
}
