// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

public record ImportDeclaration(String name, boolean wildcard, boolean staticImport,
                                SourceSpan nameSpan, SourceSpan span) {
    public String importedSimpleName() {
        int separator = name.lastIndexOf('.');
        return separator < 0 ? name : name.substring(separator + 1);
    }

    public String ownerName() {
        if (!staticImport || wildcard) {
            return name;
        }
        int separator = name.lastIndexOf('.');
        return separator < 0 ? "" : name.substring(0, separator);
    }

    public String importedMemberName() {
        return staticImport && !wildcard ? importedSimpleName() : "";
    }
}
