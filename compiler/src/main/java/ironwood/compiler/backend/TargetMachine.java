// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import java.util.List;

public enum TargetMachine {
    DEFAULT(List.of(), List.of()),
    NATIVE(List.of("-mcpu=native"), List.of("-march=native"));

    private final List<String> llvmArguments;
    private final List<String> clangArguments;

    TargetMachine(List<String> llvmArguments, List<String> clangArguments) {
        this.llvmArguments = llvmArguments;
        this.clangArguments = clangArguments;
    }

    public List<String> llvmArguments() {
        return llvmArguments;
    }

    public List<String> clangArguments() {
        return clangArguments;
    }
}
