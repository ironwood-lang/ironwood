// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Optional;

/** The declaration body attached to an anonymous class or interface implementation. */
public record AnonymousClassBody(List<FieldDeclaration> fields,
                                 List<InstanceInitialization> instanceInitializations,
                                 List<StaticInitialization> staticInitializations,
                                 Optional<DestructorDeclaration> destructor,
                                 List<MethodDeclaration> methods,
                                 List<TypeDeclaration> memberTypes,
                                 SourceSpan span) {
    public AnonymousClassBody {
        fields = List.copyOf(fields);
        instanceInitializations = List.copyOf(instanceInitializations);
        staticInitializations = List.copyOf(staticInitializations);
        destructor = destructor == null ? Optional.empty() : destructor;
        methods = List.copyOf(methods);
        memberTypes = List.copyOf(memberTypes);
    }
}
