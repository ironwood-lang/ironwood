// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;
import ironwood.compiler.source.SourceFile;

import java.util.List;
import java.util.Optional;

public record CompilationUnit(SourceFile source, Optional<PackageDeclaration> packageDeclaration,
                              List<ImportDeclaration> imports,
                              List<TypeDeclaration> declarations, SourceSpan span) {
    public CompilationUnit {
        packageDeclaration = packageDeclaration == null ? Optional.empty() : packageDeclaration;
        imports = List.copyOf(imports);
        declarations = List.copyOf(declarations);
    }

    public TypeDeclaration declaration() {
        return declarations.getFirst();
    }

    public String packageName() {
        return packageDeclaration.map(PackageDeclaration::name).orElse("");
    }
}
