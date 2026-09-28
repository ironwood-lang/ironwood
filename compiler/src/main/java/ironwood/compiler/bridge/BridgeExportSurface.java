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

    public static Set<String> builtinThrowableNames() { return BUILTIN_THROWABLES; }

    /** Narrow scalar-result shape retained for foundation consumers without result transport. */
    public static Selection scalarPreview(CompilationArtifact artifact, List<String> exports) {
        return select(artifact, exports, Shape.SCALAR);
    }

    /** Static primitive/String signatures; typed-entry proofs still govern executable admission. */
    public static Selection valuePreview(CompilationArtifact artifact, List<String> exports) {
        return select(artifact, exports, Shape.VALUE);
    }

    /** P3 concrete signatures only; no lifetime permission or executable producer admission. */
    public static Selection concreteObjects(CompilationArtifact artifact, List<String> exports) {
        return select(artifact, exports, Shape.CONCRETE);
    }

    /** Concrete/enum/String and snapshot signatures; executable lifetime/transport admission is separate. */
    public static Selection objectValues(CompilationArtifact artifact, List<String> exports) {
        return select(artifact, exports, Shape.OBJECT_VALUE);
    }

    /** P5 signature inventory only; callback invocation and lifetime proofs remain mandatory. */
    public static Selection synchronousCallbacks(CompilationArtifact artifact, List<String> exports) {
        return select(artifact, exports, Shape.CALLBACK);
    }

    /** Stateful P5 inventory only; complete storage, slots, guards and adapters remain separate. */
    public static Selection ownedCallbacks(CompilationArtifact artifact, List<String> exports) {
        return select(artifact, exports, Shape.OWNED_CALLBACK);
    }

    private enum Shape { SCALAR, VALUE, CONCRETE, OBJECT_VALUE, CALLBACK, OWNED_CALLBACK }

    private static boolean objects(Shape shape) {
        return shape == Shape.CONCRETE || shape == Shape.OBJECT_VALUE || shape == Shape.OWNED_CALLBACK;
    }

    private static boolean callbacks(Shape shape) { return shape == Shape.CALLBACK || shape == Shape.OWNED_CALLBACK; }

    private static Selection select(CompilationArtifact artifact, List<String> exports, Shape shape) {
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
            if (callbacks(shape) && type.kind() == BridgeApiFacts.Kind.INTERFACE) {
                if (type.generic() || !type.fields().isEmpty() || !type.supertypes().isEmpty()
                        || type.enclosingType().isPresent()) {
                    error(diagnostics, type.source(), type.span(), "listener requires a top-level nongeneric interface without fields or inheritance");
                }
                for (var method : type.callables()) {
                    if (method.owner().equals("ironwood.lang.Object")) continue;
                    if (method.isStatic() || method.generic() || method.target().isPresent() || method.dispatchSlot().isEmpty()
                            || !scalar(method.result()) || method.parameters().stream().anyMatch(parameter -> !parameter.isPrimitive()
                            && !(shape == Shape.OWNED_CALLBACK && BridgeListenerProxies.ownerArgument(parameter, facts)))
                            || method.thrownTypes().stream().anyMatch(thrown -> !isBuiltinThrowable(thrown))) {
                        error(diagnostics, method.source(), method.span(), "listener requires abstract methods with admitted callback values");
                    }
                    for (var parameter : method.parameters()) closure(parameter, type.sourceName() + "." + method.name(),
                            method.source(), method.span(), facts, packages, diagnostics);
                }
                continue;
            }
            if (shape == Shape.OBJECT_VALUE && type.throwable()) {
                snapshot(type, artifact, packages, diagnostics);
                continue;
            }
            boolean enumType = shape == Shape.OBJECT_VALUE && type.kind() == BridgeApiFacts.Kind.ENUM;
            BridgeEnumConstants constants = null;
            if (enumType) {
                try { constants = BridgeEnumConstants.discover(artifact, Set.of(IrType.reference(type.binaryName()))); }
                catch (IllegalArgumentException failure) {
                    error(diagnostics, type.source(), type.span(), failure.getMessage());
                    continue;
                }
            }
            if (!enumType && (type.kind() != BridgeApiFacts.Kind.CLASS || type.abstractType()) || type.generic() || type.throwable()
                    || type.enclosingType().isPresent() && !type.staticMember()
                    || !enumType && objects(shape) && !type.finalType() && type.callables().stream()
                    .anyMatch(method -> !method.isStatic() && !method.owner().equals("ironwood.lang.Object"))) {
                error(diagnostics, type.source(), type.span(), "type '" + type.sourceName()
                        + (objects(shape) ? "' requires a final concrete Java Bridge facade"
                        : "' is outside the static scalar Java Bridge preview"));
            }
            for (var parent : type.supertypes()) {
                if (parent.equals(IrType.reference("ironwood.lang.Object"))) continue;
                if (enumType && parent.isNominalReference() && parent.referenceName().equals("ironwood.lang.Enum")
                        && parent.typeArguments().equals(List.of(IrType.reference(type.binaryName())))) continue;
                closure(parent, type.sourceName(), type.source(), type.span(), facts, packages, diagnostics);
                error(diagnostics, type.source(), type.span(), "type '" + type.sourceName()
                        + "' requires unsupported native inheritance or interface projection");
            }
            for (var field : type.fields()) {
                if (enumType && field.owner().equals(type.binaryName()) && field.isStatic() && field.isFinal()
                        && !field.ambiguous() && field.type().equals(IrType.reference(type.binaryName()))
                        && type.enumConstants().stream().anyMatch(constant -> constant.name().equals(field.name()))) continue;
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
                if (enumType && method.synthetic() && method.owner().equals(type.binaryName()) && method.isStatic()
                        && Set.of("values", "valueOf").contains(method.name())) continue;
                String member = type.sourceName() + "." + method.name() + "(" + method.parameters().stream()
                        .map(IrType::displayName).collect(java.util.stream.Collectors.joining(", ")) + ")";
                BridgeEnumDispatch dispatch = null;
                if (enumType && !method.isStatic() && method.kind() == IrCallableKind.METHOD) {
                    try { dispatch = BridgeEnumDispatch.prove(artifact, IrType.reference(type.binaryName()), method, constants); }
                    catch (IllegalArgumentException failure) {
                        error(diagnostics, method.source(), method.span(), "public member '" + member + "': " + failure.getMessage());
                        continue;
                    }
                    if (dispatch.javaOnly()) continue;
                }
                closure(method.result(), member, method.source(), method.span(), facts, packages, diagnostics);
                method.parameters().forEach(parameter -> closure(parameter, member, method.source(), method.span(),
                        facts, packages, diagnostics));
                for (var thrown : method.thrownTypes()) {
                    closure(thrown, member, method.source(), method.span(), facts, packages, diagnostics);
                    if (shape != Shape.OBJECT_VALUE && !isBuiltinThrowable(thrown)) error(diagnostics, method.source(), method.span(),
                            "public member '" + member + "' declares a custom exception requiring the object snapshot phase");
                }
                boolean callableShape = method.kind() == IrCallableKind.METHOD
                        && (method.isStatic() || objects(shape))
                        || objects(shape) && !enumType && method.kind() == IrCallableKind.CONSTRUCTOR;
                if (!callableShape || method.generic()
                        || !supported(method.result(), shape, false, facts) || method.parameters().stream()
                        .anyMatch(parameter -> !supported(parameter, shape, true, facts))) {
                    error(diagnostics, method.source(), method.span(), "public member '" + member
                            + (objects(shape) ? "' is outside the concrete-object Java Bridge signature surface"
                            : "' is outside the static " + (shape == Shape.VALUE ? "primitive/String-value"
                            : "scalar/copied-string-input") + " Java Bridge preview"));
                } else if (dispatch != null) {
                    dispatch.targets().stream().filter(target -> !target.javaIdentity()).map(BridgeEnumDispatch.Target::callable)
                            .forEach(requested::add);
                } else if (method.target().isEmpty()) {
                    error(diagnostics, method.source(), method.span(), "public member '" + member
                            + "' has no exact resolved native target");
                } else requested.add(method.target().orElseThrow());
            }
        }
        if (Diagnostic.hasErrors(diagnostics)) return new Selection(Optional.empty(), diagnostics);
        var roots = BridgeRootSet.resolve(artifact.program().orElseThrow(), requested);
        if (!roots.resolved() && !(shape == Shape.OBJECT_VALUE && requested.isEmpty() && roots.problems().isEmpty())) {
            diagnostics.add(Diagnostic.global("Java Bridge preview requires resolved native callable roots: " + roots.problems()));
            return new Selection(Optional.empty(), diagnostics);
        }
        return new Selection(Optional.of(new BridgeExportSurface(selected, roots)), diagnostics);
    }

    private static void snapshot(BridgeApiFacts.Type type, CompilationArtifact artifact, Set<String> packages,
                                 List<Diagnostic> diagnostics) {
        var inventory = BridgeCustomExceptionTypes.discover(artifact, List.of(type.binaryName()));
        if (inventory.status() != BridgeProof.Status.PROVED) {
            error(diagnostics, type.source(), type.span(), inventory.reason());
            return;
        }
        var facts = artifact.bridgeApiFacts().orElseThrow();
        for (String name : inventory.contract().orElseThrow().closure()) {
            closure(IrType.reference(name), type.sourceName(), type.source(), type.span(), facts, packages, diagnostics);
        }
        // Snapshot constructors are Java data construction, never native allocation entries.
        for (var method : type.callables()) {
            if (!BridgeCustomExceptionTypes.customMethod(method)) continue;
            closure(method.result(), type.sourceName() + "." + method.name(), method.source(), method.span(),
                    facts, packages, diagnostics);
            for (var thrown : method.thrownTypes()) {
                closure(thrown, type.sourceName() + "." + method.name(), method.source(), method.span(), facts, packages, diagnostics);
            }
        }
    }

    private static boolean supported(IrType type, Shape shape, boolean parameter, BridgeApiFacts facts) {
        if (scalar(type)) return true;
        if (callbacks(shape)) {
            if (type.equals(STRING)) return parameter;
            var listener = type.isNominalReference() ? facts.types().get(type.referenceName()) : null;
            return parameter && type.typeArguments().isEmpty() && listener != null
                    && (listener.kind() == BridgeApiFacts.Kind.INTERFACE || shape == Shape.OWNED_CALLBACK
                    && listener.kind() == BridgeApiFacts.Kind.CLASS && listener.finalType() && !listener.abstractType()
                    && !listener.throwable() && listener.enclosingType().isEmpty())
                    && listener.accessible() && !listener.generic();
        }
        if (type.equals(STRING)) return parameter || shape != Shape.SCALAR;
        if (!objects(shape) || !type.isNominalReference() || !type.typeArguments().isEmpty()) return false;
        var declaration = facts.types().get(type.referenceName());
        return declaration != null && (declaration.kind() == BridgeApiFacts.Kind.CLASS
                && declaration.finalType() && !declaration.abstractType()
                || shape == Shape.OBJECT_VALUE && declaration.kind() == BridgeApiFacts.Kind.ENUM) && !declaration.generic()
                && !declaration.throwable() && declaration.accessible()
                && (declaration.enclosingType().isEmpty() || declaration.staticMember());
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
