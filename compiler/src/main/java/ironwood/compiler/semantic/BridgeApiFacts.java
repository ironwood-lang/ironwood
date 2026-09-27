// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.bridge.BridgeCallableId;
import ironwood.compiler.ir.IrCallableKind;
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

    public record Callable(String owner, String name, IrCallableKind kind, boolean isStatic,
                           boolean synthetic, boolean generic, IrType result, List<IrType> parameters,
                           List<String> parameterNames, List<IrType> thrownTypes,
                           Optional<BridgeCallableId> target, SourceFile source, SourceSpan span) {
        public Callable {
            parameters = List.copyOf(parameters);
            parameterNames = List.copyOf(parameterNames);
            thrownTypes = List.copyOf(thrownTypes);
        }
    }

    public record Field(String owner, String name, IrType type, boolean isStatic, boolean isFinal,
                        Optional<IrOperand> constant, boolean ambiguous,
                        SourceFile source, SourceSpan span) {}

    /** Source declaration order is Java enum metadata, never a native conversion token. */
    public record EnumConstant(String name, IrType nativeType, SourceSpan span) {}

    public record Type(String binaryName, String sourceName, String packageName,
                       Optional<String> enclosingType, boolean accessible, boolean staticMember,
                       Kind kind, boolean abstractType, boolean finalType, boolean generic,
                       boolean throwable, List<IrType> supertypes, List<Callable> callables,
                       List<Field> fields, List<EnumConstant> enumConstants, SourceFile source, SourceSpan span) {
        public Type {
            supertypes = List.copyOf(supertypes);
            callables = List.copyOf(callables);
            fields = List.copyOf(fields);
            enumConstants = List.copyOf(enumConstants);
        }
    }

    private final IrProgram program;
    private final Map<String, Type> types;

    private BridgeApiFacts(IrProgram program, Map<String, Type> types) {
        this.program = program;
        this.types = Collections.unmodifiableMap(new TreeMap<>(types));
    }

    public Map<String, Type> types() { return types; }
    public boolean matches(IrProgram candidate) { return program.equals(candidate); }

    static BridgeApiFacts project(IrProgram program, Map<String, TypeSymbol> symbols,
                                  ClassHierarchy hierarchy) {
        var functions = program.functions().stream().collect(Collectors.toMap(
                function -> function.linkageName(), BridgeCallableId::of));
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
                    .forEach(method -> callables.add(callable(method, symbols, functions)));
            for (String name : methodNames) {
                hierarchy.lookupMethods(type.selfType(), name).stream()
                        .filter(method -> method.accessModifier() == AccessModifier.PUBLIC)
                        .forEach(method -> callables.add(callable(method, symbols, functions)));
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
                    fields.add(new Field(field.ownerClass(), name, field.type(), field.isStatic(), field.isFinal(),
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
                    type.isAbstract(), type.isFinal(), !type.declaration().typeParameters().isEmpty(),
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

    private static Callable callable(CallableSymbol method, Map<String, TypeSymbol> types,
                                     Map<String, BridgeCallableId> functions) {
        var target = Optional.ofNullable(functions.get(method.linkageName()));
        // Generic substitution may preserve linkage while changing its source view.
        // Never present that as an exact generated native signature.
        target = target.filter(id -> id.kind() == method.kind() && id.result().equals(method.returnType())
                && id.parameters().size() == method.parameterTypes().size() + (method.isStatic() ? 0 : 1)
                && id.parameters().subList(method.isStatic() ? 0 : 1, id.parameters().size())
                        .equals(method.parameterTypes()));
        return new Callable(method.ownerType(), method.sourceName(), method.kind(), method.isStatic(),
                method.isSynthetic(), !method.typeVariables().isEmpty(), method.returnType(), method.parameterTypes(),
                method.parameters().stream().map(parameter -> parameter.name()).toList(), method.thrownTypes(),
                target, types.get(method.ownerType()).source(), method.nameSpan());
    }
}
