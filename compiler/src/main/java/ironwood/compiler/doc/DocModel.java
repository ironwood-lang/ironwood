// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.doc;

import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.ast.ClassDeclaration;
import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.ast.FieldDeclaration;
import ironwood.compiler.ast.InterfaceDeclaration;
import ironwood.compiler.ast.Parameter;
import ironwood.compiler.ast.TypeDeclaration;
import ironwood.compiler.ast.TypeName;
import ironwood.compiler.ast.TypeParameter;
import ironwood.compiler.ast.TypeReference;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.lexer.Lexer;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;
import ironwood.compiler.parser.Parser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The documentation model uses parsed declarations, never method-body text matching. */
final class DocModel {
    record Member(String section, String name, String reference, String signature,
                  List<String> parameters, List<String> typeParameters, boolean returnsValue,
                  DocComment comment, SourceSpan span) {
        String anchor() {
            StringBuilder result = new StringBuilder("member-");
            reference.codePoints().forEach(c -> {
                if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_') result.appendCodePoint(c);
                else result.append('-').append(Integer.toHexString(c)).append('-');
            });
            return result.toString();
        }
    }

    record Type(CompilationUnit unit, TypeDeclaration declaration, String localName,
                String qualifiedName, String kind, String signature, DocComment comment,
                List<Member> members) {
        Path path() {
            String directory = unit.packageName().replace('.', '/');
            return Path.of(directory, localName + ".md");
        }
    }

    private final int visibility;
    private final List<Diagnostic> diagnostics;

    DocModel(int visibility, List<Diagnostic> diagnostics) {
        this.visibility = visibility;
        this.diagnostics = diagnostics;
    }

    List<Type> parse(SourceFile source) {
        Lexer lexer = new Lexer(source, true);
        var lexed = lexer.lex();
        diagnostics.addAll(lexed.diagnostics());
        if (!lexed.diagnostics().isEmpty()) return List.of();
        var parsed = new Parser(source, lexed.tokens()).parse();
        diagnostics.addAll(parsed.diagnostics());
        if (!parsed.diagnostics().isEmpty() || parsed.unit().isEmpty()) return List.of();
        // Comments belong to the next token. Last doc comment wins; ordinary comments
        // and whitespace are trivia. Strings and text blocks never enter this list.
        Map<Integer, String> comments = new HashMap<>();
        int tokenIndex = 0;
        for (var comment : lexer.documentationComments()) {
            while (lexed.tokens().get(tokenIndex).span().start().offset() < comment.span().end().offset()) {
                tokenIndex++;
            }
            comments.put(lexed.tokens().get(tokenIndex).span().start().offset(), comment.text());
        }
        List<Type> result = new ArrayList<>();
        for (var declaration : parsed.unit().orElseThrow().declarations()) {
            collect(parsed.unit().orElseThrow(), declaration, "", comments, result);
        }
        return List.copyOf(result);
    }

    private void collect(CompilationUnit unit, TypeDeclaration declaration, String owner,
                         Map<Integer, String> comments, List<Type> result) {
        if (!visible(declaration.accessModifier())) return;
        String localName = owner + declaration.name();
        String qualifiedName = unit.packageName().isEmpty() ? localName : unit.packageName() + "." + localName;
        List<Member> members = new ArrayList<>();
        String kind;
        String signature = access(declaration.accessModifier()) + (declaration.isStatic() ? "static " : "");
        if (declaration instanceof ClassDeclaration type) {
            kind = type.enumType() ? "enum" : "class";
            signature += (type.isAbstract() ? "abstract " : "")
                    + (type.isFinal() && !type.enumType() ? "final " : "") + kind + " " + type.name()
                    + typeParameters(type.typeParameters());
            if (type.superclass().isPresent()) signature += " extends " + type.superclass().orElseThrow().displayName();
            signature += references(" implements ", type.implementedInterfaces());
            for (var field : type.fields()) field(unit, field, comments, members);
            for (var constant : type.enumConstants()) {
                members.add(new Member("Enum constants", constant.name(), constant.name(), constant.name(),
                        List.of(), List.of(), false, comment(unit, constant.span(), comments), constant.span()));
            }
            for (var constructor : type.constructors()) {
                if (!visible(constructor.accessModifier())) continue;
                callable(unit, "Constructors", constructor.name(), access(constructor.accessModifier())
                        + genericPrefix(constructor.typeParameters()) + constructor.name(), constructor.parameters(),
                        constructor.typeParameters(), constructor.thrownTypes(), false, constructor.span(), comments, members);
            }
            for (var method : type.methods()) {
                if (!visible(method.accessModifier())) continue;
                String prefix = (method.hasOverrideDirective() ? "@Override\n" : "")
                        + (method.hasTestDirective() ? "@Test\n" : "")
                        + access(method.accessModifier()) + (method.isStatic() ? "static " : "")
                        + (method.isAbstract() ? "abstract " : "") + (method.isFinal() ? "final " : "")
                        + genericPrefix(method.typeParameters()) + method.returnType().displayName() + " " + method.name();
                callable(unit, "Methods", method.name(), prefix, method.parameters(), method.typeParameters(),
                        method.thrownTypes(), !method.returnType().displayName().equals("void"), method.span(), comments, members);
            }
        } else {
            InterfaceDeclaration type = (InterfaceDeclaration) declaration;
            kind = "interface";
            signature += kind + " " + type.name() + typeParameters(type.typeParameters())
                    + references(" extends ", type.extendedInterfaces());
            for (var field : type.fields()) field(unit, field, comments, members);
            for (var method : type.methods()) {
                if (!visible(method.accessModifier())) continue;
                String prefix = access(method.accessModifier()) + (method.isStatic() ? "static " : "")
                        + (method.isDefault() ? "default " : "") + genericPrefix(method.typeParameters())
                        + method.returnType().displayName() + " " + method.name();
                callable(unit, "Methods", method.name(), prefix, method.parameters(), method.typeParameters(),
                        method.thrownTypes(), !method.returnType().displayName().equals("void"), method.span(), comments, members);
            }
        }
        members.sort(Comparator.comparingInt(member -> member.span().start().offset()));
        DocComment comment = comment(unit, declaration.span(), comments);
        validate(unit, declaration.span(), comment, List.of(),
                declaration.typeParameters().stream().map(TypeParameter::name).toList(), false);
        result.add(new Type(unit, declaration, localName, qualifiedName, kind, signature, comment, List.copyOf(members)));
        for (var nested : declaration.memberTypes()) collect(unit, nested, localName + ".", comments, result);
    }

    private void field(CompilationUnit unit, FieldDeclaration field, Map<Integer, String> comments, List<Member> members) {
        if (!visible(field.accessModifier())) return;
        String signature = access(field.accessModifier()) + (field.isStatic() ? "static " : "")
                + (field.isFinal() ? "final " : "") + field.type().displayName() + " " + field.name();
        // Show literal/constant initializers exactly as written, preserving whitespace in strings.
        if (field.isStatic() && field.isFinal() && field.initializer().isPresent()) {
            var span = field.initializer().orElseThrow().span();
            signature += " = " + unit.source().content().substring(span.start().offset(), span.end().offset());
        }
        DocComment comment = comment(unit, field.span(), comments);
        validate(unit, field.span(), comment, List.of(), List.of(), false);
        members.add(new Member("Fields", field.name(), field.name(), signature + ";", List.of(), List.of(), false,
                comment, field.span()));
    }

    private void callable(CompilationUnit unit, String section, String name, String prefix, List<Parameter> parameters,
                          List<TypeParameter> typeParameters, List<TypeName> thrown, boolean returnsValue,
                          SourceSpan span, Map<Integer, String> comments, List<Member> members) {
        String reference = name + "(" + parameters.stream().map(p -> p.type().displayName())
                .collect(Collectors.joining(",")) + ")";
        String signature = prefix + "(" + parameters.stream().map(p -> (p.isFinal() ? "final " : "")
                + p.type().displayName() + " " + p.name()).collect(Collectors.joining(", ")) + ")";
        if (!thrown.isEmpty()) signature += " throws " + thrown.stream().map(TypeName::displayName)
                .collect(Collectors.joining(", "));
        List<String> names = parameters.stream().map(Parameter::name).toList();
        List<String> typeNames = typeParameters.stream().map(TypeParameter::name).toList();
        DocComment comment = comment(unit, span, comments);
        validate(unit, span, comment, names, typeNames, returnsValue);
        members.add(new Member(section, name, reference, signature, names, typeNames, returnsValue, comment, span));
    }

    private DocComment comment(CompilationUnit unit, SourceSpan span, Map<Integer, String> comments) {
        try {
            return DocComment.parse(comments.getOrDefault(span.start().offset(), "/** */"));
        } catch (IllegalArgumentException exception) {
            diagnostics.add(Diagnostic.error(unit.source(), span, exception.getMessage()));
            return DocComment.EMPTY;
        }
    }

    private void validate(CompilationUnit unit, SourceSpan span, DocComment comment, List<String> parameters,
                          List<String> typeParameters, boolean returnsValue) {
        try {
            comment.validate(parameters, typeParameters, returnsValue);
        } catch (IllegalArgumentException exception) {
            diagnostics.add(Diagnostic.error(unit.source(), span, exception.getMessage()));
        }
    }

    private boolean visible(AccessModifier access) {
        return switch (access) {
            case PUBLIC -> true;
            case PROTECTED -> visibility >= 1;
            case PACKAGE_PRIVATE -> visibility >= 2;
            case PRIVATE -> visibility >= 3;
        };
    }

    private static String access(AccessModifier access) {
        return switch (access) {
            case PUBLIC -> "public ";
            case PROTECTED -> "protected ";
            case PRIVATE -> "private ";
            case PACKAGE_PRIVATE -> "";
        };
    }

    private static String genericPrefix(List<TypeParameter> parameters) {
        return parameters.isEmpty() ? "" : typeParameters(parameters) + " ";
    }

    private static String typeParameters(List<TypeParameter> parameters) {
        if (parameters.isEmpty()) return "";
        return "<" + parameters.stream().map(p -> p.name() + (p.bounds().isEmpty() ? "" : " extends "
                + p.bounds().stream().map(TypeName::displayName).collect(Collectors.joining(" & "))))
                .collect(Collectors.joining(", ")) + ">";
    }

    private static String references(String prefix, List<TypeReference> references) {
        return references.isEmpty() ? "" : prefix + references.stream().map(TypeReference::displayName)
                .collect(Collectors.joining(", "));
    }
}
