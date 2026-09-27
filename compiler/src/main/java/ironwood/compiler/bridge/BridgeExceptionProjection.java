// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeRetentionAnalyzer;
import ironwood.compiler.semantic.BridgeApiFacts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Resolved snapshot accessors, not permission to run a getter outside a typed handler. */
public final class BridgeExceptionProjection {
    private static final IrType STRING = IrType.reference("ironwood.lang.String");

    public record Property(String name, IrType type, Optional<BridgeCallableId> callable,
                           Optional<IrField> field, boolean ownedString) {
        public Property {
            if (callable.isPresent() == field.isPresent() || ownedString && !type.equals(STRING)) {
                throw new IllegalArgumentException("exception property needs exactly one resolved accessor");
            }
        }
    }

    public record Type(String nativeName, String javaName, int typeId, List<Property> properties) {
        public Type { properties = List.copyOf(properties); }
    }

    private final IrProgram program;
    private final List<Type> types;
    private final BridgeRootSet accessors;
    private final Map<String, BridgeApiFacts.Type> customTypes;

    private BridgeExceptionProjection(IrProgram program, List<Type> types, BridgeRootSet accessors,
            Map<String, BridgeApiFacts.Type> customTypes) {
        this.program = program;
        this.types = List.copyOf(types);
        this.accessors = accessors;
        this.customTypes = Map.copyOf(customTypes);
    }

    public List<Type> types() { return types; }
    public BridgeRootSet accessors() { return accessors; }
    public Map<String, BridgeApiFacts.Type> customTypes() { return customTypes; }
    public boolean matches(IrProgram candidate) { return program.equals(candidate); }

    public static BridgeProof<BridgeExceptionProjection> builtins(CompilationArtifact artifact,
            Collection<String> requested) {
        return project(artifact, requested, false);
    }

    /** Custom declarations are data snapshots; this proof alone does not enable their transport. */
    public static BridgeProof<BridgeExceptionProjection> snapshots(CompilationArtifact artifact,
            Collection<String> requested) {
        return project(artifact, requested, true);
    }

    private static BridgeProof<BridgeExceptionProjection> project(CompilationArtifact artifact,
            Collection<String> requested, boolean customSnapshots) {
        if (requested.isEmpty()) return BridgeProof.rejected("exception projection requires at least one type");
        if (!artifact.valid() || artifact.bridgeConstructionFacts().isEmpty()) {
            return BridgeProof.unknown("exception projection requires successful bridge analysis");
        }
        var program = artifact.program().orElseThrow();
        var facts = artifact.bridgeConstructionFacts().orElseThrow();
        if (!facts.matches(program)) return BridgeProof.unknown("exception projection facts do not match the program");
        Map<String, BridgeApiFacts.Type> customTypes = Map.of();
        if (customSnapshots) {
            var inventory = BridgeCustomExceptionTypes.discover(artifact, requested);
            if (inventory.status() != BridgeProof.Status.PROVED) {
                return new BridgeProof<>(inventory.status(), Optional.empty(), inventory.reason());
            }
            customTypes = inventory.contract().orElseThrow().customTypes();
            requested = inventory.contract().orElseThrow().closure();
        }
        Map<String, IrClass> classes = new LinkedHashMap<>();
        program.classes().forEach(type -> classes.put(type.name(), type));
        Map<String, IrFunction> functions = new LinkedHashMap<>();
        program.functions().forEach(function -> functions.put(function.linkageName(), function));
        var throwable = classes.get("ironwood.lang.Throwable");
        if (throwable == null) return BridgeProof.unknown("missing native Throwable hierarchy");
        List<Type> projected = new ArrayList<>();
        Set<BridgeCallableId> accessors = new java.util.LinkedHashSet<>();
        Map<BridgeCallableId, Boolean> stringOwnership = new LinkedHashMap<>();
        for (String name : requested.stream().distinct().sorted().toList()) {
            var type = classes.get(name);
            if (!BridgeExportSurface.isBuiltinThrowable(IrType.reference(name)) && !customTypes.containsKey(name)) {
                return BridgeProof.rejected("custom or unmapped exception projection is unavailable: " + name);
            }
            if (type == null || !type.typeMembership().contains(throwable.typeId())) {
                return BridgeProof.unknown("exception type is absent from the resolved Throwable hierarchy: " + name);
            }
            var custom = customTypes.get(name);
            // Abstract catch classes need Java declarations, but have no native instances to extract.
            if (custom != null && custom.abstractType()) continue;
            List<Property> properties = new ArrayList<>();
            List<String> methods = new ArrayList<>(List.of("getMessage", "getCause", "getSecondaryExceptionCount", "getSecondaryException"));
            if (inherits(type, "ironwood.nio.file.FileSystemException", classes)) {
                methods.addAll(List.of("getFile", "getOtherFile", "getReason"));
            }
            if (inherits(type, "ironwood.nio.file.InvalidPathException", classes)) methods.addAll(List.of("getInput", "getReason", "getIndex"));
            if (inherits(type, "ironwood.time.format.DateTimeParseException", classes)) methods.addAll(List.of("getParsedString", "getErrorIndex"));
            if (custom != null) custom.callables().stream().filter(BridgeCustomExceptionTypes::customMethod)
                    .map(BridgeApiFacts.Callable::name).filter(method -> !methods.contains(method)).forEach(methods::add);
            for (String method : methods) {
                var parameters = method.equals("getSecondaryException") ? List.of(IrType.I32) : List.<IrType>of();
                var targets = type.dispatchEntries().stream().filter(entry -> entry.slot().methodName().equals(method)
                                && entry.slot().parameterTypes().equals(parameters))
                        .map(IrDispatchEntry::targetLinkageName).distinct().toList();
                if (targets.size() != 1 || !functions.containsKey(targets.getFirst())) {
                    return BridgeProof.unknown("exception getter has no unique resolved target: " + name + "." + method);
                }
                var target = functions.get(targets.getFirst());
                var id = BridgeCallableId.of(target);
                if (facts.isStatic(id) || !facts.borrowsThroughResult(id, 0)) {
                    return BridgeProof.rejected("exception getter receiver lifetime is not proved: " + id.linkage());
                }
                var result = target.returnType();
                boolean owned = false;
                if (result.equals(STRING)) {
                    if (!stringOwnership.containsKey(id)) {
                        var origin = facts.resultOrigins().get(id);
                        if (origin != null && origin.status() == BridgeProof.Status.PROVED
                                && origin.contract().orElseThrow().kind() == BridgeResultOriginContract.Kind.FRESH_ROOT) {
                            stringOwnership.put(id, true);
                        } else if (BridgeRetentionAnalyzer.borrowedStringResults(program,
                                BridgeRootSet.resolve(program, List.of(id))).contains(id)) {
                            stringOwnership.put(id, false);
                        } else return BridgeProof.unknown("exception String getter ownership is not proved: " + id.linkage());
                    }
                    owned = stringOwnership.get(id);
                } else if (!result.equals(IrType.I32) && !(custom != null && BridgeCustomExceptionTypes.copyable(result)) && !(result.isNominalReference()
                        && classes.containsKey(result.referenceName())
                        && classes.get(result.referenceName()).typeMembership().contains(throwable.typeId()))) {
                    return BridgeProof.rejected("unsupported exception getter result: " + id.linkage());
                }
                properties.add(new Property(method, result, Optional.of(id), Optional.empty(), owned));
                accessors.add(id);
            }
            if (inherits(type, "ironwood.io.InterruptedIOException", classes)) {
                var fields = type.fields().stream().filter(field -> field.name().equals("bytesTransferred")
                        && field.ownerClass().equals("ironwood.io.InterruptedIOException") && field.type().equals(IrType.I32)).toList();
                if (fields.size() != 1) return BridgeProof.unknown("interrupted-I/O transfer-count field is unresolved");
                properties.add(new Property("bytesTransferred", IrType.I32, Optional.empty(), Optional.of(fields.getFirst()), false));
            }
            projected.add(new Type(name, BridgeJavaTypes.binaryName(IrType.reference(name)), type.typeId(), properties));
        }
        return BridgeProof.proved(new BridgeExceptionProjection(program, projected, BridgeRootSet.resolve(program, List.copyOf(accessors)), customTypes),
                "exception hierarchy, exact getters and String ownership resolved from final semantic facts");
    }

    private static boolean inherits(IrClass type, String parent, Map<String, IrClass> classes) {
        var base = classes.get(parent);
        return base != null && type.typeMembership().contains(base.typeId());
    }
}
