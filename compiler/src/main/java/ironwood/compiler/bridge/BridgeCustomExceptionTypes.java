// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeApiFacts;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Signature inventory for custom throwable snapshots; constructor declarations are not native entry roots. */
final class BridgeCustomExceptionTypes {
    record Inventory(Set<String> closure, Map<String, BridgeApiFacts.Type> customTypes) {
        Inventory { closure = Set.copyOf(closure); customTypes = Map.copyOf(customTypes); }
    }

    private BridgeCustomExceptionTypes() {}

    static BridgeProof<Inventory> discover(CompilationArtifact artifact, Collection<String> requested) {
        var program = artifact.program().orElseThrow();
        if (artifact.bridgeApiFacts().isEmpty() || !artifact.bridgeApiFacts().orElseThrow().matches(program)) {
            return BridgeProof.unknown("custom exception projection requires matching final API facts");
        }
        var api = artifact.bridgeApiFacts().orElseThrow();
        Set<String> closure = new LinkedHashSet<>();
        Map<String, BridgeApiFacts.Type> custom = new LinkedHashMap<>();
        var pending = new ArrayDeque<>(requested);
        while (!pending.isEmpty()) {
            var name = pending.removeFirst();
            if (!closure.add(name) || BridgeExportSurface.isBuiltinThrowable(IrType.reference(name))) continue;
            var type = api.types().get(name);
            if (type == null || !type.throwable() || !type.accessible() || type.generic()
                    || type.kind() != BridgeApiFacts.Kind.CLASS || type.enclosingType().isPresent() && !type.staticMember()) {
                return BridgeProof.rejected("custom exception requires an accessible non-generic snapshot class: " + name);
            }
            var nativeType = program.classes().stream().filter(value -> value.name().equals(name)).findFirst();
            if (nativeType.isEmpty() || nativeType.orElseThrow().superclass().isEmpty() || !nativeType.orElseThrow().interfaces().isEmpty()) {
                return BridgeProof.rejected("custom exception requires one resolved catch superclass and no interfaces: " + name);
            }
            if (nativeType.orElseThrow().superclass().orElseThrow().equals("ironwood.nio.file.DirectoryIteratorException")) {
                return BridgeProof.rejected("custom snapshot cannot extend final Java exception java.nio.file.DirectoryIteratorException: " + name);
            }
            for (var field : type.fields()) {
                if (BridgeExportSurface.isBuiltinThrowable(IrType.reference(field.owner()))) continue;
                if (field.ambiguous() || !field.isStatic() || !field.isFinal() || field.constant().isEmpty() || !copyable(field.type())) {
                    return BridgeProof.rejected("custom exception public field is not a copyable constant: " + name + "." + field.name());
                }
            }
            for (var method : type.callables()) {
                if (!customMethod(method)) continue;
                boolean secondary = method.name().equals("getSecondaryException") && method.parameters().equals(List.of(IrType.I32));
                boolean cause = method.name().equals("getCause") && method.parameters().isEmpty();
                boolean throwableResult = method.result().isNominalReference()
                        && api.types().containsKey(method.result().referenceName()) && api.types().get(method.result().referenceName()).throwable();
                if (method.isStatic() || method.generic() || (!secondary && !method.parameters().isEmpty())
                        || method.name().equals("getSecondaryException") && !secondary
                        || !(copyable(method.result()) || (secondary || cause) && throwableResult)) {
                    return BridgeProof.rejected("custom exception member requires a copyable snapshot getter: " + name + "." + method.name());
                }
                if ((secondary || cause) && throwableResult) pending.addLast(method.result().referenceName());
            }
            custom.put(name, type);
            pending.addLast(nativeType.orElseThrow().superclass().orElseThrow());
        }
        return BridgeProof.proved(new Inventory(closure, custom), "custom snapshot declarations and catch hierarchy are complete");
    }

    static boolean customMethod(BridgeApiFacts.Callable method) {
        return method.kind() == IrCallableKind.METHOD && !method.owner().equals("ironwood.lang.Object")
                && !BridgeExportSurface.isBuiltinThrowable(IrType.reference(method.owner()));
    }

    static boolean copyable(IrType type) {
        return type.isPrimitive() && !type.equals(IrType.VOID) || type.equals(IrType.reference("ironwood.lang.String"));
    }
}
