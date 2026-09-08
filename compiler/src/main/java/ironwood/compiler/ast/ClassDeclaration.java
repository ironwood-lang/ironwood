// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Optional;

public record ClassDeclaration(
        AccessModifier accessModifier,
        boolean isStatic,
        boolean isAbstract,
        boolean isFinal,
        String name,
        SourceSpan nameSpan,
        List<TypeParameter> typeParameters,
        Optional<TypeReference> superclass,
        List<TypeReference> implementedInterfaces,
        List<FieldDeclaration> fields,
        List<InstanceInitialization> instanceInitializations,
        List<StaticInitialization> staticInitializations,
        List<ConstructorDeclaration> constructors,
        Optional<DestructorDeclaration> destructor,
        List<MethodDeclaration> methods,
        List<TypeDeclaration> memberTypes,
        boolean enumType,
        List<EnumConstant> enumConstants,
        SourceSpan span
) implements TypeDeclaration {
    public ClassDeclaration {
        typeParameters = List.copyOf(typeParameters);
        superclass = superclass == null ? Optional.empty() : superclass;
        implementedInterfaces = List.copyOf(implementedInterfaces);
        fields = List.copyOf(fields);
        instanceInitializations = List.copyOf(instanceInitializations);
        staticInitializations = List.copyOf(staticInitializations);
        constructors = List.copyOf(constructors);
        destructor = destructor == null ? Optional.empty() : destructor;
        methods = List.copyOf(methods);
        memberTypes = List.copyOf(memberTypes);
        enumConstants = List.copyOf(enumConstants);
    }

    public ClassDeclaration(
            AccessModifier accessModifier,
            boolean isStatic,
            boolean isAbstract,
            boolean isFinal,
            String name,
            SourceSpan nameSpan,
            List<TypeParameter> typeParameters,
            Optional<TypeReference> superclass,
            List<TypeReference> implementedInterfaces,
            List<FieldDeclaration> fields,
            List<InstanceInitialization> instanceInitializations,
            List<StaticInitialization> staticInitializations,
            List<ConstructorDeclaration> constructors,
            Optional<DestructorDeclaration> destructor,
            List<MethodDeclaration> methods,
            List<TypeDeclaration> memberTypes,
            SourceSpan span
    ) {
        this(accessModifier, isStatic, isAbstract, isFinal, name, nameSpan, typeParameters,
                superclass, implementedInterfaces, fields, instanceInitializations,
                staticInitializations, constructors, destructor, methods, memberTypes,
                false, List.of(), span);
    }

    public ClassDeclaration(
            AccessModifier accessModifier,
            boolean isStatic,
            boolean isAbstract,
            boolean isFinal,
            String name,
            SourceSpan nameSpan,
            List<TypeParameter> typeParameters,
            Optional<TypeReference> superclass,
            List<TypeReference> implementedInterfaces,
            List<FieldDeclaration> fields,
            List<InstanceInitialization> instanceInitializations,
            List<StaticInitialization> staticInitializations,
            List<ConstructorDeclaration> constructors,
            List<MethodDeclaration> methods,
            List<TypeDeclaration> memberTypes,
            SourceSpan span
    ) {
        this(accessModifier, isStatic, isAbstract, isFinal, name, nameSpan, typeParameters,
                superclass, implementedInterfaces, fields, instanceInitializations,
                staticInitializations, constructors, Optional.empty(), methods, memberTypes,
                false, List.of(), span);
    }

    public ClassDeclaration(AccessModifier accessModifier, String name, SourceSpan nameSpan,
                            Optional<TypeReference> superclass,
                            List<TypeReference> implementedInterfaces,
                            List<FieldDeclaration> fields,
                            List<ConstructorDeclaration> constructors,
                            List<MethodDeclaration> methods, SourceSpan span) {
        this(accessModifier, false, false, false, name, nameSpan, List.of(), superclass,
                implementedInterfaces, fields,
                fields.stream().filter(field -> !field.isStatic()).map(field ->
                        (InstanceInitialization) field).toList(),
                fields.stream().filter(FieldDeclaration::isStatic).map(field ->
                        (StaticInitialization) field).toList(),
                constructors, Optional.empty(), methods, List.of(), false, List.of(), span);
    }
}
