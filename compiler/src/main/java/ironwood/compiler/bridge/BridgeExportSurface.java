// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeApiFacts;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;

import javax.lang.model.SourceVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Complete signature selection; executable admission still requires the typed-entry proofs. */
public record BridgeExportSurface(List<BridgeApiFacts.Type> types, BridgeRootSet roots) {
    public static final String PACKAGE_MARKER = "_IronwoodBridgePackage";
    private static final IrType STRING = IrType.reference("ironwood.lang.String");
    private static final Set<String> BUILTIN_THROWABLES = Set.of(
            "ironwood.lang.Throwable", "ironwood.lang.Exception", "ironwood.lang.RuntimeException",
            "ironwood.lang.Error", "ironwood.lang.OutOfMemoryError", "ironwood.lang.NullPointerException",
            "ironwood.lang.ArithmeticException", "ironwood.lang.ClassCastException",
            "ironwood.lang.IllegalArgumentException", "ironwood.lang.IllegalStateException",
            "ironwood.lang.IndexOutOfBoundsException", "ironwood.lang.ArrayIndexOutOfBoundsException",
            "ironwood.lang.StringIndexOutOfBoundsException", "ironwood.lang.NegativeArraySizeException",
            "ironwood.lang.NumberFormatException", "ironwood.lang.UnsupportedOperationException",
            "ironwood.io.IOException", "ironwood.io.EOFException", "ironwood.io.FileNotFoundException",
            "ironwood.io.InterruptedIOException", "ironwood.io.UTFDataFormatException",
            "ironwood.io.UncheckedIOException", "ironwood.util.NoSuchElementException",
            "ironwood.net.SocketException", "ironwood.net.SocketTimeoutException", "ironwood.net.BindException",
            "ironwood.net.ConnectException", "ironwood.net.NoRouteToHostException", "ironwood.net.UnknownHostException",
            "ironwood.nio.InvalidMarkException", "ironwood.nio.BufferUnderflowException",
            "ironwood.nio.BufferOverflowException", "ironwood.nio.file.InvalidPathException",
            "ironwood.nio.file.FileSystemException", "ironwood.nio.file.FileAlreadyExistsException",
            "ironwood.nio.file.NoSuchFileException", "ironwood.nio.file.DirectoryNotEmptyException",
            "ironwood.nio.file.AccessDeniedException", "ironwood.nio.file.FileSystemLoopException",
            "ironwood.nio.file.ClosedDirectoryStreamException", "ironwood.nio.file.DirectoryIteratorException",
            "ironwood.time.DateTimeException", "ironwood.time.format.DateTimeParseException");

    public BridgeExportSurface {
        types = List.copyOf(types);
    }

    public record Selection(Optional<BridgeExportSurface> surface, List<Diagnostic> diagnostics) {
        public Selection {
            diagnostics = List.copyOf(diagnostics);
            if (surface.isPresent() && Diagnostic.hasErrors(diagnostics)) {
                throw new IllegalArgumentException("failed API selection cannot expose a partial surface");
            }
        }
    }

    public static boolean isBuiltinThrowable(IrType type) {
        return type.isNominalReference() && type.typeArguments().isEmpty()
                && BUILTIN_THROWABLES.contains(type.referenceName());
    }

    /** Current scalar preview shape. This does not enable JNI, snapshots or string-result transport. */
    public static Selection scalarPreview(CompilationArtifact artifact, List<String> exports) {
        if (!artifact.valid() || artifact.bridgeApiFacts().isEmpty()
                || !artifact.bridgeApiFacts().orElseThrow().matches(artifact.program().orElseThrow())) {
            return new Selection(Optional.empty(), List.of(Diagnostic.global(
                    "Java Bridge API selection requires matching final semantic facts")));
        }
        var diagnostics = new ArrayList<Diagnostic>();
        var packages = new TreeSet<String>();
        for (String name : exports) {
            if (name == null || !SourceVersion.isName(name, SourceVersion.RELEASE_21)) {
                diagnostics.add(Diagnostic.global("invalid Java Bridge export package: '" + name + "'"));
            } else packages.add(name);
        }
        var facts = artifact.bridgeApiFacts().orElseThrow();
        var selected = facts.types().values().stream()
                .filter(type -> packages.contains(type.packageName()) && type.accessible()).toList();
        for (String name : packages) {
            var marker = facts.types().get(name + "." + PACKAGE_MARKER);
            if (marker != null) error(diagnostics, marker.source(), marker.span(),
                    "type '" + marker.binaryName() + "' collides with the reserved Java Bridge package marker");
            if (selected.stream().noneMatch(type -> type.packageName().equals(name))) {
                diagnostics.add(Diagnostic.global("Java Bridge export package '" + name + "' has no accessible public types"));
            }
        }
        if (packages.isEmpty()) diagnostics.add(Diagnostic.global("Java Bridge requires an exact export package"));
        var requested = new ArrayList<BridgeCallableId>();
        for (var type : selected) {
            if (type.kind() != BridgeApiFacts.Kind.CLASS || type.abstractType() || type.generic() || type.throwable()
                    || type.enclosingType().isPresent() && !type.staticMember()) {
                error(diagnostics, type.source(), type.span(), "type '" + type.sourceName()
                        + "' is outside the static scalar Java Bridge preview");
            }
            for (var parent : type.supertypes()) {
                if (parent.equals(IrType.reference("ironwood.lang.Object"))) continue;
                closure(parent, type.sourceName(), type.source(), type.span(), facts, packages, diagnostics);
                error(diagnostics, type.source(), type.span(), "type '" + type.sourceName()
                        + "' requires unsupported native inheritance or interface projection");
            }
            for (var field : type.fields()) {
                String member = type.sourceName() + "." + field.name();
                closure(field.type(), member, field.source(), field.span(), facts, packages, diagnostics);
                if (field.ambiguous() || !field.isStatic() || !field.isFinal() || field.constant().isEmpty()
                        || !(scalar(field.type()) || field.type().equals(STRING))) {
                    error(diagnostics, field.source(), field.span(), "public field '" + member
                            + "' requires a supported compile-time constant; mutable or runtime-initialized fields cannot be projected");
                }
            }
            for (var method : type.callables()) {
                if (method.owner().equals("ironwood.lang.Object") && method.kind() == IrCallableKind.METHOD) continue;
                String member = type.sourceName() + "." + method.name() + "(" + method.parameters().stream()
                        .map(IrType::displayName).collect(java.util.stream.Collectors.joining(", ")) + ")";
                closure(method.result(), member, method.source(), method.span(), facts, packages, diagnostics);
                method.parameters().forEach(parameter -> closure(parameter, member, method.source(), method.span(),
                        facts, packages, diagnostics));
                for (var thrown : method.thrownTypes()) {
                    closure(thrown, member, method.source(), method.span(), facts, packages, diagnostics);
                    if (!isBuiltinThrowable(thrown)) error(diagnostics, method.source(), method.span(),
                            "public member '" + member + "' declares a custom exception requiring the object snapshot phase");
                }
                if (method.kind() != IrCallableKind.METHOD || !method.isStatic() || method.generic()
                        || !scalar(method.result()) || method.parameters().stream()
                        .anyMatch(parameter -> !scalar(parameter) && !parameter.equals(STRING))) {
                    error(diagnostics, method.source(), method.span(), "public member '" + member
                            + "' is outside the static scalar/copied-string-input Java Bridge preview");
                } else if (method.target().isEmpty()) {
                    error(diagnostics, method.source(), method.span(), "public member '" + member
                            + "' has no exact resolved native target");
                } else requested.add(method.target().orElseThrow());
            }
        }
        if (Diagnostic.hasErrors(diagnostics)) return new Selection(Optional.empty(), diagnostics);
        var roots = BridgeRootSet.resolve(artifact.program().orElseThrow(), requested);
        if (!roots.resolved()) {
            diagnostics.add(Diagnostic.global("Java Bridge preview requires resolved native callable roots: " + roots.problems()));
            return new Selection(Optional.empty(), diagnostics);
        }
        return new Selection(Optional.of(new BridgeExportSurface(selected, roots)), diagnostics);
    }

    private static boolean scalar(IrType type) {
        return !type.isReference() && BridgeAbi.carrierFor(type).isPresent();
    }

    private static void closure(IrType type, String member, SourceFile source, SourceSpan span,
                                BridgeApiFacts facts, Set<String> packages, List<Diagnostic> diagnostics) {
        if (type.isArray()) {
            closure(type.elementType(), member, source, span, facts, packages, diagnostics);
        } else if (type.isNominalReference()) {
            type.typeArguments().forEach(argument -> closure(argument, member, source, span, facts, packages, diagnostics));
            if (type.equals(STRING) || isBuiltinThrowable(type)) return;
            var required = facts.types().get(type.referenceName());
            if (required == null || !required.accessible()) {
                error(diagnostics, source, span, "public member '" + member + "' requires inaccessible signature type '"
                        + type.displayName() + "'");
            } else if (!packages.contains(required.packageName())) {
                error(diagnostics, source, span, "public member '" + member + "' requires type '"
                        + required.sourceName() + "'; add --export " + required.packageName());
            }
        } else if (type.isTypeParameter()) {
            closure(type.typeParameterErasure(), member, source, span, facts, packages, diagnostics);
        } else if (type.isWildcard() && type.wildcardBound() != null) {
            closure(type.wildcardBound(), member, source, span, facts, packages, diagnostics);
        }
    }

    private static void error(List<Diagnostic> diagnostics, SourceFile source, SourceSpan span, String message) {
        var diagnostic = Diagnostic.error(source, span, message);
        if (!diagnostics.contains(diagnostic)) diagnostics.add(diagnostic);
    }
}
