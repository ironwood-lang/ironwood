// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

/** The declaration body attached to an anonymous class or interface implementation. */
public record AnonymousClassBody(List<FieldDeclaration> fields,
                                 List<InstanceInitialization> instanceInitializations,
                                 List<StaticInitialization> staticInitializations,
                                 List<MethodDeclaration> methods,
                                 List<TypeDeclaration> memberTypes,
                                 SourceSpan span) {
    public AnonymousClassBody {
        fields = List.copyOf(fields);
        instanceInitializations = List.copyOf(instanceInitializations);
        staticInitializations = List.copyOf(staticInitializations);
        methods = List.copyOf(methods);
        memberTypes = List.copyOf(memberTypes);
    }
}
