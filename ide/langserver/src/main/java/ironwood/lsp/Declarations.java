// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.lsp;

import ironwood.compiler.ast.ClassDeclaration;
import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.ast.ConstructorDeclaration;
import ironwood.compiler.ast.EnumConstant;
import ironwood.compiler.ast.FieldDeclaration;
import ironwood.compiler.ast.InterfaceDeclaration;
import ironwood.compiler.ast.InterfaceMethodDeclaration;
import ironwood.compiler.ast.MethodDeclaration;
import ironwood.compiler.ast.TypeDeclaration;
import ironwood.compiler.lexer.DocumentationComment;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Flattens a compilation unit into the named things a reader can point at.
 *
 * <p>Hover and go-to-definition both need the same question answered: what is
 * declared here, what is it called, and how should it be shown. Producing one
 * flat list keeps that answer in a single place rather than repeating an AST
 * walk per feature.
 */
public final class Declarations {

    /** What kind of thing was declared, which decides how it is rendered. */
    public enum Kind {
        CLASS, ENUM, INTERFACE, METHOD, CONSTRUCTOR, DESTRUCTOR, FIELD, ENUM_CONSTANT
    }

    /**
     * One declared name.
     *
     * @param name        the declared simple name
     * @param nameSpan    the span of the name itself, used to test the cursor
     * @param span        the whole declaration, used to find its documentation
     * @param signature   source-shaped text shown in hover
     * @param enclosing   the type this is declared in, or empty for a top-level type
     */
    public record Declared(String name, Kind kind, SourceSpan nameSpan, SourceSpan span,
                           String signature, Optional<String> enclosing) {

        public boolean isType() {
            return kind == Kind.CLASS || kind == Kind.ENUM || kind == Kind.INTERFACE;
        }
    }

    private Declarations() {
    }

    public static List<Declared> of(CompilationUnit unit) {
        List<Declared> declared = new ArrayList<>();
        for (TypeDeclaration declaration : unit.declarations()) {
            collect(declaration, declared);
        }
        return declared;
    }

    private static void collect(TypeDeclaration declaration, List<Declared> declared) {
        if (declaration instanceof ClassDeclaration type) {
            collectClass(type, declared);
        } else if (declaration instanceof InterfaceDeclaration type) {
            collectInterface(type, declared);
        }
    }

    private static void collectClass(ClassDeclaration type, List<Declared> declared) {
        Kind kind = type.enumType() ? Kind.ENUM : Kind.CLASS;
        StringBuilder signature = new StringBuilder();
        appendAccess(signature, type.accessModifier());
        if (type.isStatic()) {
            signature.append("static ");
        }
        if (type.isAbstract()) {
            signature.append("abstract ");
        }
        if (type.isFinal()) {
            signature.append("final ");
        }
        signature.append(kind == Kind.ENUM ? "enum " : "class ").append(type.name());
        type.superclass().ifPresent(parent -> signature.append(" extends ").append(parent.name()));
        if (!type.implementedInterfaces().isEmpty()) {
            signature.append(" implements ");
            signature.append(String.join(", ", type.implementedInterfaces().stream()
                    .map(reference -> reference.name()).toList()));
        }
        declared.add(new Declared(type.name(), kind, type.nameSpan(), type.span(),
                signature.toString(), Optional.empty()));

        for (EnumConstant constant : type.enumConstants()) {
            declared.add(new Declared(constant.name(), Kind.ENUM_CONSTANT, constant.nameSpan(),
                    constant.span(), type.name() + "." + constant.name(),
                    Optional.of(type.name())));
        }
        for (FieldDeclaration field : type.fields()) {
            declared.add(field(field, type.name()));
        }
        for (ConstructorDeclaration constructor : type.constructors()) {
            StringBuilder text = new StringBuilder();
            appendAccess(text, constructor.accessModifier());
            text.append(constructor.name())
                    .append(TypeNames.renderParameters(constructor.parameters()));
            declared.add(new Declared(constructor.name(), Kind.CONSTRUCTOR,
                    constructor.nameSpan(), constructor.span(), text.toString(),
                    Optional.of(type.name())));
        }
        type.destructor().ifPresent(destructor -> declared.add(new Declared("destructor",
                Kind.DESTRUCTOR, destructor.keywordSpan(), destructor.span(),
                "destructor", Optional.of(type.name()))));
        for (MethodDeclaration method : type.methods()) {
            declared.add(method(method, type.name()));
        }
        for (TypeDeclaration member : type.memberTypes()) {
            collect(member, declared);
        }
    }

    private static void collectInterface(InterfaceDeclaration type, List<Declared> declared) {
        StringBuilder signature = new StringBuilder();
        appendAccess(signature, type.accessModifier());
        signature.append("interface ").append(type.name());
        if (!type.extendedInterfaces().isEmpty()) {
            signature.append(" extends ");
            signature.append(String.join(", ", type.extendedInterfaces().stream()
                    .map(reference -> reference.name()).toList()));
        }
        declared.add(new Declared(type.name(), Kind.INTERFACE, type.nameSpan(), type.span(),
                signature.toString(), Optional.empty()));

        for (FieldDeclaration field : type.fields()) {
            declared.add(field(field, type.name()));
        }
        for (InterfaceMethodDeclaration method : type.methods()) {
            StringBuilder text = new StringBuilder();
            appendAccess(text, method.accessModifier());
            if (method.isStatic()) {
                text.append("static ");
            }
            if (method.isDefault()) {
                text.append("default ");
            }
            text.append(TypeNames.render(method.returnType())).append(' ').append(method.name())
                    .append(TypeNames.renderParameters(method.parameters()));
            appendThrows(text, method.thrownTypes());
            declared.add(new Declared(method.name(), Kind.METHOD, method.nameSpan(),
                    method.span(), text.toString(), Optional.of(type.name())));
        }
        for (TypeDeclaration member : type.memberTypes()) {
            collect(member, declared);
        }
    }

    private static Declared field(FieldDeclaration field, String enclosing) {
        StringBuilder text = new StringBuilder();
        appendAccess(text, field.accessModifier());
        if (field.isStatic()) {
            text.append("static ");
        }
        if (field.isFinal()) {
            text.append("final ");
        }
        text.append(TypeNames.render(field.type())).append(' ').append(field.name());
        return new Declared(field.name(), Kind.FIELD, field.nameSpan(), field.span(),
                text.toString(), Optional.of(enclosing));
    }

    private static Declared method(MethodDeclaration method, String enclosing) {
        StringBuilder text = new StringBuilder();
        // The override directive is part of what a reader wants to know about a
        // method, since Ironwood requires it on every declared override.
        if (method.hasOverrideDirective()) {
            text.append("@Override ");
        }
        if (method.hasTestDirective()) {
            text.append("@Test ");
        }
        appendAccess(text, method.accessModifier());
        if (method.isStatic()) {
            text.append("static ");
        }
        if (method.isAbstract()) {
            text.append("abstract ");
        }
        if (method.isFinal()) {
            text.append("final ");
        }
        text.append(TypeNames.render(method.returnType())).append(' ').append(method.name())
                .append(TypeNames.renderParameters(method.parameters()));
        appendThrows(text, method.thrownTypes());
        return new Declared(method.name(), Kind.METHOD, method.nameSpan(), method.span(),
                text.toString(), Optional.of(enclosing));
    }

    private static void appendAccess(StringBuilder text,
                                     ironwood.compiler.ast.AccessModifier access) {
        // Package-private has no keyword, so it contributes nothing.
        if (access != null && access != ironwood.compiler.ast.AccessModifier.PACKAGE_PRIVATE) {
            text.append(access.name().toLowerCase(Locale.ROOT)).append(' ');
        }
    }

    private static void appendThrows(StringBuilder text,
                                     List<ironwood.compiler.ast.TypeName> thrownTypes) {
        if (thrownTypes.isEmpty()) {
            return;
        }
        text.append(" throws ");
        text.append(String.join(", ", thrownTypes.stream().map(TypeNames::render).toList()));
    }

    /**
     * Finds the documentation comment written immediately above a declaration.
     *
     * <p>The lexer keeps documentation as trivia rather than attaching it to the
     * tree, so the association is made here by position: the closest comment
     * that ends before the declaration starts.
     */
    public static Optional<String> documentationFor(List<DocumentationComment> comments,
                                                    SourceSpan declaration) {
        if (declaration == null) {
            return Optional.empty();
        }
        DocumentationComment best = null;
        for (DocumentationComment comment : comments) {
            if (comment.span().end().offset() > declaration.start().offset()) {
                continue;
            }
            if (best == null || comment.span().end().offset() > best.span().end().offset()) {
                best = comment;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        // Only treat it as this declaration's documentation when nothing but
        // whitespace and modifiers could sit between them. One blank line is the
        // repository's own formatting rule, so allow a small gap and no more.
        int gap = declaration.start().line() - best.span().end().line();
        if (gap < 0 || gap > 2) {
            return Optional.empty();
        }
        return Optional.of(stripCommentMarkup(best.text()));
    }

    /** Turns a documentation comment's raw text into plain readable lines. */
    private static String stripCommentMarkup(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("/**")) {
                trimmed = trimmed.substring(3).strip();
            }
            if (trimmed.endsWith("*/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 2).strip();
            }
            if (trimmed.startsWith("*")) {
                trimmed = trimmed.substring(1).strip();
            }
            lines.add(trimmed);
        }
        while (!lines.isEmpty() && lines.getFirst().isEmpty()) {
            lines.removeFirst();
        }
        while (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        return String.join("\n", lines);
    }
}
