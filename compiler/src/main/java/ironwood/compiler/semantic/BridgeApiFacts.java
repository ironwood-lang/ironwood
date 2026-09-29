// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.bridge.BridgeProof;
import ironwood.compiler.bridge.BridgeTypeParameter;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrDispatchSlot;
import ironwood.compiler.ir.IrOperand;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Resolved public API inventory. Visibility/signatures confer no lifetime permission. */
public final class BridgeApiFacts {
    public enum Kind { CLASS, INTERFACE, ENUM }

    /** The native target is a candidate; direct binding also requires exact source receiver matching. */
    public record Callable(String owner, String name, IrCallableKind kind, boolean isStatic,
                           boolean synthetic, BridgeCallableId.SourceSignature signature, IrType result, List<IrType> parameters,
                           List<String> parameterNames, List<IrType> thrownTypes,
                           Optional<BridgeCallableId> target, Optional<IrDispatchSlot> dispatchSlot,
                           SourceFile source, SourceSpan span) {
        public Callable {
            parameters = List.copyOf(parameters);
            parameterNames = List.copyOf(parameterNames);
            thrownTypes = List.copyOf(thrownTypes);
            if (!signature.declaringOwner().referenceName().equals(owner) || !signature.name().equals(name)
                    || signature.kind() != kind || signature.isStatic() != isStatic
                    || !signature.result().equals(result) || !signature.parameters().equals(parameters)
                    || !signature.thrownTypes().equals(thrownTypes)) {
                throw new IllegalArgumentException("bridge callable source identity does not match its member");
            }
        }
        public boolean generic() { return !signature.typeParameters().isEmpty(); }
    }

    public record Field(String owner, IrType declaringOwner, String name, IrType type, boolean isStatic, boolean isFinal,
                        Optional<IrOperand> constant, boolean ambiguous,
                        SourceFile source, SourceSpan span) {}

    /** Source declaration order is Java enum metadata, never a native conversion token. */
    public record EnumConstant(String name, IrType nativeType, SourceSpan span) {}

    public record Type(String binaryName, String sourceName, String packageName,
                       Optional<String> enclosingType, boolean accessible, boolean staticMember,
                       Kind kind, boolean abstractType, boolean finalType,
                       IrType exactType, List<BridgeTypeParameter> typeParameters,
                       boolean throwable, List<IrType> supertypes, List<Callable> callables,
                       List<Field> fields, List<EnumConstant> enumConstants, SourceFile source, SourceSpan span) {
        public Type {
            typeParameters = List.copyOf(typeParameters);
            supertypes = List.copyOf(supertypes);
            callables = List.copyOf(callables);
            fields = List.copyOf(fields);
            enumConstants = List.copyOf(enumConstants);
        }
        public boolean generic() { return !typeParameters.isEmpty(); }
    }

    private final IrProgram program;
    private final Map<String, Type> types;

    private BridgeApiFacts(IrProgram program, Map<String, Type> types) {
        this.program = program;
        this.types = Collections.unmodifiableMap(new TreeMap<>(types));
    }

    public Map<String, Type> types() { return types; }
    // Equal lowered programs can have different source-only generic bounds.
    // Reconstruction must project fresh facts from its own semantic analysis.
    public boolean matches(IrProgram candidate) { return program == candidate; }

    /** Signature resolution is metadata evidence only, never export or ownership admission. */
    public BridgeProof<ResolvedSignature> resolve(IrProgram candidate, BridgeCallableId.SourceSignature signature) {
        if (!matches(candidate)) return BridgeProof.unknown("source signature requires matching final semantic facts");
        var owner = types.get(signature.receiver().referenceName());
        if (owner == null) return BridgeProof.rejected("source signature receiver is absent");
        var matches = owner.callables().stream().filter(method -> method.signature().equals(signature)).toList();
        if (matches.size() != 1) return BridgeProof.rejected("source signature is missing, changed or ambiguous");
        var target = matches.getFirst().target().filter(id -> signature.isStatic()
                || id.parameters().getFirst().equals(signature.declaringOwner()));
        return BridgeProof.proved(new ResolvedSignature(program, signature, target),
                "exact source signature resolved; native admission and lifetime proofs remain separate");
    }

    public record ResolvedSignature(IrProgram program, BridgeCallableId.SourceSignature signature,
                                    Optional<BridgeCallableId> target) {
        /** Recheck bounds and source types as well as implementation and native identity. */
        public boolean matches(IrProgram candidate, BridgeApiFacts facts) {
            return program.equals(candidate) && facts.resolve(candidate, signature).contract()
                    .map(this::equals).orElse(false);
        }
    }

    static BridgeApiFacts project(IrProgram program, Map<String, TypeSymbol> symbols,
                                  ClassHierarchy hierarchy) {
        var functions = program.functions().stream().collect(Collectors.toMap(
                function -> function.linkageName(), BridgeCallableId::of));
        var slots = program.dispatchSlots().stream().collect(Collectors.toMap(IrDispatchSlot::key, slot -> slot));
        var result = new TreeMap<String, Type>();
        for (var type : symbols.values()) {
            var methodNames = new TreeSet<String>();
            var fieldNames = new TreeSet<String>();
            for (var supertype : hierarchy.exactSupertypes(type.selfType())) {
                var owner = symbols.get(supertype.referenceName());
                if (owner == null) continue;
                owner.declaredMethods().values().forEach(method -> methodNames.add(method.sourceName()));
                fieldNames.addAll(owner.declaredFields().keySet());
            }
            var callables = new ArrayList<Callable>();
            type.constructors().stream().filter(method -> method.accessModifier() == AccessModifier.PUBLIC)
                    .forEach(method -> callables.add(callable(type, method, symbols, hierarchy, functions, slots)));
            for (String name : methodNames) {
                hierarchy.lookupMethods(type.selfType(), name).stream()
                        .filter(method -> method.accessModifier() == AccessModifier.PUBLIC)
                        .forEach(method -> callables.add(callable(type, method, symbols, hierarchy, functions, slots)));
            }
            callables.sort(Comparator.comparing(Callable::name)
                    .thenComparing(callable -> callable.parameters().toString())
                    .thenComparing(Callable::owner));
            var fields = new ArrayList<Field>();
            for (String name : fieldNames) {
                var resolution = hierarchy.resolveField(type.selfType(), name);
                for (var field : resolution.candidates()) {
                    if (field.accessModifier() != AccessModifier.PUBLIC) continue;
                    var constant = field.staticField() != null && field.staticField().compileTimeConstant()
                            ? Optional.of(field.staticField().initialValue()) : Optional.<IrOperand>empty();
                    fields.add(new Field(field.ownerClass(), ownerView(type, field.ownerClass(), hierarchy), name, field.type(), field.isStatic(), field.isFinal(),
                            constant, resolution.ambiguous(), symbols.get(field.ownerClass()).source(),
                            field.declaration().nameSpan()));
                }
            }
            var parents = new ArrayList<IrType>();
            type.superclassType().ifPresent(parents::add);
            parents.addAll(type.directInterfaceTypes());
            result.put(type.name(), new Type(type.name(), type.sourceName(), type.packageName(),
                    Optional.ofNullable(type.enclosingBinaryName()), accessible(type), type.isStaticMember(),
                    type.isEnum() ? Kind.ENUM : type.isInterface() ? Kind.INTERFACE : Kind.CLASS,
                    type.isAbstract(), type.isFinal(), type.selfType(), parameters(type.declaredTypeParameters()),
                    hierarchy.isSubtype(type.name(), "ironwood.lang.Throwable"), parents, callables, fields,
                    type.enumConstants().stream().map(constant -> new EnumConstant(constant.constant().name(),
                            constant.concreteType().selfType(), constant.constant().nameSpan())).toList(),
                    type.source(), type.declaration().nameSpan()));
        }
        return new BridgeApiFacts(program, result);
    }

    private static boolean accessible(TypeSymbol type) {
        if (type.isLexicallyScoped() || type.declaration().accessModifier() != AccessModifier.PUBLIC) return false;
        return type.enclosingType().map(BridgeApiFacts::accessible).orElse(true);
    }

    private static Callable callable(TypeSymbol receiver, CallableSymbol method, Map<String, TypeSymbol> types,
                                     ClassHierarchy hierarchy,
                                     Map<String, BridgeCallableId> functions, Map<String, IrDispatchSlot> slots) {
        IrType owner = ownerView(receiver, method.ownerType(), hierarchy);
        var signature = new BridgeCallableId.SourceSignature(receiver.selfType(), owner,
                parameters(receiver.typeParameters()), parameters(types.get(method.ownerType()).typeParameters()),
                method.sourceName(), method.kind(), method.isStatic(), parameters(method.typeVariables()),
                method.parameterTypes(), method.returnType(), method.thrownTypes());
        var target = Optional.ofNullable(functions.get(method.linkageName()));
        // Generic substitution may preserve linkage while changing its source view.
        // Retain a candidate for specialized dispatch protocols. resolve() also
        // checks the receiver before presenting it as an exact direct binding.
        target = target.filter(id -> id.kind() == method.kind() && id.result().equals(method.returnType())
                && id.parameters().size() == method.parameterTypes().size() + (method.isStatic() ? 0 : 1)
                && id.parameters().subList(method.isStatic() ? 0 : 1, id.parameters().size())
                        .equals(method.parameterTypes()));
        return new Callable(method.ownerType(), method.sourceName(), method.kind(), method.isStatic(),
                method.isSynthetic(), signature, method.returnType(), method.parameterTypes(),
                method.parameters().stream().map(parameter -> parameter.name()).toList(), method.thrownTypes(),
                target, method.isStatic() || method.kind() != IrCallableKind.METHOD ? Optional.empty()
                        : Optional.ofNullable(slots.get(method.dispatchKey())),
                types.get(method.ownerType()).source(), method.nameSpan());
    }

    private static List<BridgeTypeParameter> parameters(List<TypeVariableSymbol> variables) {
        return variables.stream().map(variable -> new BridgeTypeParameter(variable.id(), variable.displayName(),
                variable.upperBounds(), variable.firstBoundErasure(), variable.permitsPrimitive())).toList();
    }

    private static IrType ownerView(TypeSymbol receiver, String owner, ClassHierarchy hierarchy) {
        return hierarchy.exactSupertypes(receiver.selfType()).stream()
                .filter(type -> type.referenceName().equals(owner)).findFirst().orElseThrow();
    }
}
