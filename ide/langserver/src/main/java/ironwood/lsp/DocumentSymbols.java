// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.lsp;

import ironwood.compiler.ast.ClassDeclaration;
import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.ast.ConstructorDeclaration;
import ironwood.compiler.ast.DestructorDeclaration;
import ironwood.compiler.ast.EnumConstant;
import ironwood.compiler.ast.FieldDeclaration;
import ironwood.compiler.ast.InterfaceDeclaration;
import ironwood.compiler.ast.InterfaceMethodDeclaration;
import ironwood.compiler.ast.MethodDeclaration;
import ironwood.compiler.ast.TypeDeclaration;

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.SymbolKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the Outline view's tree from a parsed compilation unit.
 *
 * <p>The tree is built from the syntax tree alone, so it works while a file
 * still has semantic errors. That matters because the outline is most useful
 * exactly when the code is unfinished.
 */
public final class DocumentSymbols {

    private DocumentSymbols() {
    }

    public static List<DocumentSymbol> of(CompilationUnit unit) {
        List<DocumentSymbol> symbols = new ArrayList<>();
        for (TypeDeclaration declaration : unit.declarations()) {
            symbols.add(ofType(declaration));
        }
        return symbols;
    }

    private static DocumentSymbol ofType(TypeDeclaration declaration) {
        if (declaration instanceof ClassDeclaration type) {
            return ofClass(type);
        }
        if (declaration instanceof InterfaceDeclaration type) {
            return ofInterface(type);
        }
        throw new IllegalStateException("unhandled type declaration: " + declaration);
    }

    private static DocumentSymbol ofClass(ClassDeclaration type) {
        List<DocumentSymbol> children = new ArrayList<>();

        for (EnumConstant constant : type.enumConstants()) {
            children.add(symbol(constant.name(), SymbolKind.EnumMember, null,
                    Ranges.of(constant.span()), Ranges.of(constant.nameSpan())));
        }
        for (FieldDeclaration field : type.fields()) {
            children.add(ofField(field));
        }
        for (ConstructorDeclaration constructor : type.constructors()) {
            children.add(symbol(constructor.name(), SymbolKind.Constructor,
                    TypeNames.renderParameterTypes(constructor.parameters()),
                    Ranges.of(constructor.span()), Ranges.of(constructor.nameSpan())));
        }
        // A destructor has no name of its own, so it is listed by its keyword,
        // which is also what the reader is looking for in the outline.
        type.destructor().ifPresent(destructor -> children.add(ofDestructor(destructor)));
        for (MethodDeclaration method : type.methods()) {
            children.add(ofMethod(method));
        }
        for (TypeDeclaration member : type.memberTypes()) {
            children.add(ofType(member));
        }

        DocumentSymbol symbol = symbol(type.name(),
                type.enumType() ? SymbolKind.Enum : SymbolKind.Class,
                null, Ranges.of(type.span()), Ranges.of(type.nameSpan()));
        symbol.setChildren(children);
        return symbol;
    }

    private static DocumentSymbol ofInterface(InterfaceDeclaration type) {
        List<DocumentSymbol> children = new ArrayList<>();
        for (FieldDeclaration field : type.fields()) {
            children.add(ofField(field));
        }
        for (InterfaceMethodDeclaration method : type.methods()) {
            children.add(ofInterfaceMethod(method));
        }
        for (TypeDeclaration member : type.memberTypes()) {
            children.add(ofType(member));
        }

        DocumentSymbol symbol = symbol(type.name(), SymbolKind.Interface, null,
                Ranges.of(type.span()), Ranges.of(type.nameSpan()));
        symbol.setChildren(children);
        return symbol;
    }

    private static DocumentSymbol ofField(FieldDeclaration field) {
        // A static final field is a constant, and showing it as one lets the
        // outline's filtering and icons distinguish it from mutable state.
        SymbolKind kind = field.isStatic() && field.isFinal()
                ? SymbolKind.Constant : SymbolKind.Field;
        return symbol(field.name(), kind, TypeNames.render(field.type()),
                Ranges.of(field.span()), Ranges.of(field.nameSpan()));
    }

    private static DocumentSymbol ofMethod(MethodDeclaration method) {
        String detail = TypeNames.renderParameterTypes(method.parameters())
                + " : " + TypeNames.render(method.returnType());
        return symbol(method.name(), SymbolKind.Method, detail,
                Ranges.of(method.span()), Ranges.of(method.nameSpan()));
    }

    // Interface methods are their own declaration shape rather than a wrapped
    // method declaration, so they get their own conversion.
    private static DocumentSymbol ofInterfaceMethod(InterfaceMethodDeclaration method) {
        String detail = TypeNames.renderParameterTypes(method.parameters())
                + " : " + TypeNames.render(method.returnType());
        return symbol(method.name(), SymbolKind.Method, detail,
                Ranges.of(method.span()), Ranges.of(method.nameSpan()));
    }

    private static DocumentSymbol ofDestructor(DestructorDeclaration destructor) {
        return symbol("destructor", SymbolKind.Method, null,
                Ranges.of(destructor.span()), Ranges.of(destructor.keywordSpan()));
    }

    private static DocumentSymbol symbol(String name, SymbolKind kind, String detail,
                                         org.eclipse.lsp4j.Range range,
                                         org.eclipse.lsp4j.Range selection) {
        DocumentSymbol symbol = new DocumentSymbol();
        symbol.setName(name);
        symbol.setKind(kind);
        symbol.setDetail(detail);
        symbol.setRange(range);
        // The protocol requires the selection range to sit inside the full
        // range. A name span always does, but falling back keeps a malformed
        // span from being rejected outright by the client.
        symbol.setSelectionRange(containedIn(range, selection) ? selection : range);
        return symbol;
    }

    private static boolean containedIn(org.eclipse.lsp4j.Range range,
                                       org.eclipse.lsp4j.Range candidate) {
        return comparePositions(candidate.getStart(), range.getStart()) >= 0
                && comparePositions(candidate.getEnd(), range.getEnd()) <= 0;
    }

    private static int comparePositions(org.eclipse.lsp4j.Position left,
                                        org.eclipse.lsp4j.Position right) {
        if (left.getLine() != right.getLine()) {
            return Integer.compare(left.getLine(), right.getLine());
        }
        return Integer.compare(left.getCharacter(), right.getCharacter());
    }
}
