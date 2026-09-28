// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.BridgeObjectAdmission;
import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeApiFacts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Concrete Java projections. Each generation route requires its matching complete native adapters. */
public final class BridgePermanentJavaSources {
    private static final String HEADER = "// SPDX-License-Identifier: MIT OR Apache-2.0\n\n";
    private BridgePermanentJavaSources() {}

    public record Facade(String binaryName, String addressField, String typeNameField, String stateField, String stateType) {
        public Facade(String binaryName, String addressField, String typeNameField) { this(binaryName, addressField, typeNameField, "", ""); }
        public boolean rooted() { return !stateField.isEmpty(); }
        public String constructorDescriptor() { return "(J" + (rooted() ? "L" + stateType.replace('.', '/') + ";" : "") + "Ljava/lang/Void;)V"; }
    }

    private record RootContext(String stateType, List<BridgeJavaSources.RootDestruction> destructions) {}

    public record EnumFacade(String binaryName, String tokenField, List<BridgeEnumConstants.Constant> constants) {
        public EnumFacade { constants = List.copyOf(constants); }
    }

    public record Sources(BridgeJavaSources declarations, List<Facade> facades, List<EnumFacade> enums) {
        public Sources { facades = List.copyOf(facades); enums = List.copyOf(enums); }
        public Sources(BridgeJavaSources declarations, List<Facade> facades) { this(declarations, facades, List.of()); }
    }

    public static Sources generate(CompilationArtifact artifact, BridgeObjectAdmission admission, BridgeGeneration generation) {
        if (!generation.matchesObjects(artifact, admission)) {
            throw new IllegalArgumentException("permanent Java facades require matching final object admission and generation");
        }
        if (admission.roots().isPresent() || admission.surface().types().stream().anyMatch(type -> type.kind() == BridgeApiFacts.Kind.INTERFACE)) {
            throw new IllegalArgumentException("permanent Java declarations do not yet project roots");
        }
        return generateAdmitted(artifact, admission, generation, null);
    }

    public static Sources generateRoots(CompilationArtifact artifact, BridgeObjectAdmission admission, BridgeGeneration generation) {
        if (!generation.matchesObjects(artifact, admission) || admission.roots().isEmpty()) {
            throw new IllegalArgumentException("root Java facades require matching final root admission and generation");
        }
        return generateAdmitted(artifact, admission, generation, new RootContext(generation.supportPackage() + ".RootState", new ArrayList<>()));
    }

    private static Sources generateAdmitted(CompilationArtifact artifact, BridgeObjectAdmission admission, BridgeGeneration generation, RootContext roots) {
        var surface = admission.surface();
        var entries = admission.entries().primaryEntries().stream().collect(Collectors.toMap(
                entry -> entry.root().callable(), entry -> entry.function().linkageName()));
        String support = generation.supportPackage();
        var projection = admission.lifetime().exceptions().projection();
        var snapshots = projection.customTypes().isEmpty() ? null : new BridgeCustomSnapshotSources.Context(projection,
                BridgeCustomSnapshotLayout.create(artifact, projection), support + ".SnapshotData");
        if (surface.types().stream().anyMatch(type -> type.packageName().equals(support))) {
            throw new IllegalArgumentException("export package collides with the reserved artifact support namespace");
        }
        var occupied = surface.types().stream().flatMap(type -> type.callables().stream())
                .map(BridgeApiFacts.Callable::name).collect(Collectors.toCollection(HashSet::new));
        String ensure = unique(occupied, "$ironwood$ensure");
        String annotation = "@" + support + ".Identity(" + BridgeJavaSources.quote(generation.identity()) + ")\n";
        var sources = new TreeMap<String, String>();
        var bindings = new ArrayList<BridgeJavaSources.Binding>();
        var registrations = new ArrayList<BridgeJavaSources.FacadeRegistration>();
        var facades = new ArrayList<Facade>();
        var enums = new ArrayList<EnumFacade>();
        var types = new TreeSet<String>();
        for (var type : surface.types()) {
            types.add(type.binaryName());
            if (type.enclosingType().isPresent()) continue;
            var text = new StringBuilder(HEADER).append("package ").append(type.packageName()).append(";\n\n")
                    .append("import static ").append(support).append(".Support.").append(ensure).append(";\n\n");
            emit(text, type, artifact, admission, entries, bindings, registrations, facades, enums, snapshots, roots, support + ".PermanentCache", annotation, ensure, "");
            sources.put(type.binaryName().replace('.', '/') + ".java", text.toString());
        }
        if (!bindings.stream().map(BridgeJavaSources.Binding::entrySymbol).collect(Collectors.toSet()).equals(
                admission.entries().entries().stream().map(entry -> entry.function().linkageName()).collect(Collectors.toSet()))) {
            throw new IllegalArgumentException("object declarations do not cover every admitted source entry");
        }
        for (String name : surface.types().stream().map(BridgeApiFacts.Type::packageName).distinct().sorted().toList()) {
            String marker = name + "." + BridgeExportSurface.PACKAGE_MARKER;
            types.add(marker);
            sources.put(marker.replace('.', '/') + ".java", HEADER + "package " + name + ";\n\n" + annotation
                    + "public final class " + BridgeExportSurface.PACKAGE_MARKER + " {\n"
                    + "    private " + BridgeExportSurface.PACKAGE_MARKER + "() {}\n}\n");
        }
        types.add(support + ".Identity"); types.add(support + ".Support");
        sources.put(support.replace('.', '/') + "/Identity.java", HEADER + "package " + support + ";\n\n"
                + annotation + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                + "@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)\n"
                + "public @interface Identity { String value(); }\n");
        if (facades.stream().anyMatch(facade -> !facade.rooted())) {
            var cache = BridgeIdentityCacheSources.generate(artifact, admission, generation);
            sources.putAll(cache.sources()); types.addAll(cache.types());
        }
        var exceptions = BridgeExceptionSources.generate(artifact, generation, admission.lifetime().exceptions().projection());
        sources.putAll(exceptions.sources()); types.addAll(exceptions.types());
        if (roots != null) {
            var state = BridgeRootStateSources.generate(artifact, admission, generation);
            sources.putAll(state.sources()); types.addAll(state.types());
        }
        return new Sources(new BridgeJavaSources(sources, bindings, new ArrayList<>(types), ensure, registrations,
                roots == null ? List.of() : roots.destructions()), facades, enums);
    }

    private static void emit(StringBuilder text, BridgeApiFacts.Type type, CompilationArtifact artifact, BridgeObjectAdmission admission,
            Map<BridgeCallableId, String> entries, List<BridgeJavaSources.Binding> bindings,
            List<BridgeJavaSources.FacadeRegistration> registrations, List<Facade> facades, List<EnumFacade> enums,
            BridgeCustomSnapshotSources.Context snapshots, RootContext roots,
            String permanentCache, String annotation, String ensure, String indent) {
        if (type.throwable()) {
            if (snapshots == null) throw new IllegalArgumentException("custom snapshot declaration has no complete projection");
            BridgeCustomSnapshotSources.emit(text, type, admission.surface(), snapshots, annotation, indent);
            nested(text, type, artifact, admission, entries, bindings, registrations, facades, enums, snapshots, roots, permanentCache, annotation, ensure, indent);
            text.append(indent).append("}\n"); return;
        }
        if (type.kind() == BridgeApiFacts.Kind.ENUM) {
            enums.add(BridgeEnumJavaSources.emit(text, type, artifact, admission, roots == null ? "" : roots.stateType(),
                    entries, bindings, annotation, ensure, indent));
            nested(text, type, artifact, admission, entries, bindings, registrations, facades, enums, snapshots, roots, permanentCache, annotation, ensure, indent);
            text.append(indent).append("}\n");
            return;
        }
        String simple = type.sourceName().substring(type.sourceName().lastIndexOf('.') + 1);
        var reference = IrType.reference(type.binaryName());
        boolean rooted = BridgeRootCalls.rooted(admission, reference);
        boolean facade = rooted || admission.lifetime().references().containsKey(reference);
        var occupied = type.callables().stream().map(BridgeApiFacts.Callable::name).collect(Collectors.toCollection(HashSet::new));
        type.fields().forEach(field -> occupied.add(field.name()));
        type.callables().forEach(method -> occupied.addAll(method.parameterNames()));
        String address = unique(occupied, "$ironwood$address"), typeName = unique(occupied, "$ironwood$type");
        String receiver = unique(occupied, "$ironwood$receiver"), registration = unique(occupied, "$ironwood$remember");
        String state = roots == null ? "" : unique(occupied, "$ironwood$state");
        text.append(indent).append(annotation).append(indent).append("public ")
                .append(type.enclosingType().isPresent() ? "static " : "").append("final class ").append(simple).append(" {\n")
                .append(indent).append("    static { ").append(ensure).append("(); }\n");
        if (facade) {
            facades.add(rooted ? new Facade(type.binaryName(), address, typeName, state, roots.stateType()) : new Facade(type.binaryName(), address, typeName));
            text.append(indent).append("    private final long ").append(address).append(";\n")
                    .append(indent).append("    private final java.lang.String ").append(typeName).append(" = ")
                    .append(BridgeJavaSources.quote(type.binaryName())).append(";\n");
            if (rooted) text.append(indent).append("    private final ").append(roots.stateType()).append(' ').append(state).append(";\n");
            text.append(indent).append("    private ").append(simple).append("(long value, ")
                    .append(rooted ? roots.stateType() + " owner, " : "").append("java.lang.Void marker) { this.")
                    .append(address).append(" = value;").append(rooted ? " this." + state + " = owner;" : "").append(" }\n");
            if (!rooted && type.callables().stream().anyMatch(method -> method.kind() == IrCallableKind.CONSTRUCTOR)) {
                registrations.add(new BridgeJavaSources.FacadeRegistration(type.binaryName(), registration));
                text.append(indent).append("    private static native void ").append(registration)
                        .append("(long address, ").append(type.sourceName()).append(" facade);\n");
            }
            if (rooted && admission.roots().orElseThrow().destruction().containsKey(reference)) {
                String destroy = unique(occupied, "$ironwood$destroy");
                roots.destructions().add(new BridgeJavaSources.RootDestruction(type.binaryName(), destroy, roots.stateType()));
                text.append(indent).append("    /** Reclaims an eligible owning root. Borrowed or retained instances are refused. */\n")
                        .append(indent).append("    public void free() {\n")
                        .append(indent).append("        if (this.").append(state).append(".prepareFree(this.").append(address).append(")) ")
                        .append(destroy).append("(this.").append(state).append(", this.").append(address).append(");\n")
                        .append(indent).append("    }\n")
                        .append(indent).append("    private static native void ").append(destroy).append('(').append(roots.stateType()).append(" state, long address);\n");
            }
            identity(text, type, address, typeName, occupied, indent);
        } else text.append(indent).append("    private ").append(simple).append("() {}\n");
        for (var field : type.fields()) {
            text.append(indent).append("    public static final ").append(javaType(field.type(), admission.surface()))
                    .append(' ').append(field.name()).append(" = ").append(BridgeJavaSources.literal(field.constant().orElseThrow())).append(";\n");
        }
        int next = 0;
        for (var method : type.callables()) {
            if (method.owner().equals("ironwood.lang.Object")) continue;
            boolean constructor = method.kind() == IrCallableKind.CONSTRUCTOR;
            if ((!method.isStatic() || constructor) && !facade) throw new IllegalArgumentException("instance member lacks facade lifetime proof");
            String nativeName = unique(occupied, "$ironwood$native$" + next++);
            var formals = new ArrayList<String>();
            for (int index = 0; index < method.parameters().size(); index++) {
                formals.add(javaType(method.parameters().get(index), admission.surface()) + " " + method.parameterNames().get(index));
            }
            var parameters = BridgeEnumArgumentSources.generate(text, artifact, admission, method, occupied, indent);
            var arguments = new ArrayList<>(parameters.arguments());
            var nativeFormals = new ArrayList<>(parameters.nativeFormals());
            String parameterDescriptors = parameters.descriptor();
            if (!method.isStatic() && !constructor) {
                nativeFormals.addFirst("long " + receiver); arguments.addFirst("this." + address);
                parameterDescriptors = "J" + parameterDescriptors;
            }
            var callable = method.target().orElseThrow();
            boolean reserve = BridgeRootCalls.reservation(admission, callable).isPresent();
            boolean receiverState = BridgeRootCalls.receiverState(admission, callable, !method.isStatic() && !constructor);
            if (reserve || receiverState) {
                nativeFormals.addFirst(roots.stateType() + " " + state);
                arguments.addFirst(constructor || receiverState ? "this." + state : "new " + roots.stateType() + "()");
                parameterDescriptors = "L" + roots.stateType().replace('.', '/') + ";" + parameterDescriptors;
            }
            String throwsClause = method.thrownTypes().isEmpty() ? "" : " throws " + method.thrownTypes().stream()
                    .map(thrown -> javaType(thrown, admission.surface())).collect(Collectors.joining(", "));
            String result = constructor ? "long" : javaType(method.result(), admission.surface());
            boolean addressResult = !constructor && admission.lifetime().references().containsKey(method.result())
                    && admission.surface().types().stream().anyMatch(candidate -> candidate.binaryName().equals(method.result().referenceName())
                        && candidate.kind() == BridgeApiFacts.Kind.CLASS && !candidate.throwable());
            String conversion = addressResult ? unique(occupied, "$ironwood$convert$" + next) : "";
            String nativeResult = addressResult ? "long" : result;
            var alternatives = BridgeFixedEnumSources.generate(text, admission, method, type.binaryName(), address, receiver,
                    nativeResult, conversion, throwsClause, occupied, bindings, indent);
            String invocation = nativeName + "(" + String.join(", ", arguments) + ")";
            if (!method.result().equals(IrType.VOID)) invocation = BridgeFixedEnumSources.select(alternatives, invocation);
            String ownership = BridgeRootCalls.documentation(admission, callable);
            if (!ownership.isEmpty()) text.append(indent).append("    /** ").append(ownership).append(" */\n");
            text.append(indent).append("    public ");
            if (constructor) text.append(simple);
            else text.append(method.isStatic() ? "static " : "").append(result).append(' ').append(method.name());
            text.append('(').append(String.join(", ", formals)).append(')').append(throwsClause).append(" {\n");
            if (rooted && constructor) text.append(indent).append("        this.").append(state).append(" = new ").append(roots.stateType()).append("();\n");
            else if (rooted && !method.isStatic()) text.append(indent).append("        this.").append(state).append(".checkLive();\n");
            if (method.result().equals(IrType.VOID)) BridgeFixedEnumSources.emitVoidBranches(text, alternatives, indent);
            if (addressResult) {
                String returned = unique(occupied, "$ironwood$returned"), cached = unique(occupied, "$ironwood$cached");
                text.append(indent).append("        long ").append(returned).append(" = ").append(invocation).append(";\n")
                        .append(indent).append("        if (").append(returned).append(" == 0) return null;\n")
                        .append(indent).append("        java.lang.Object ").append(cached).append(" = ")
                        .append(permanentCache).append(".lookup(")
                        .append(returned).append(");\n")
                        .append(indent).append("        return ").append(cached).append(" != null ? (").append(result).append(") ")
                        .append(cached).append(" : ").append(conversion).append('(').append(returned).append(");\n");
            } else text.append(indent).append("        ").append(constructor ? "this." + address + " = " : method.result().equals(IrType.VOID) ? "" : "return ")
                    .append(invocation).append(";\n");
            if (constructor) text.append(indent).append("        ").append(rooted ? "this." + state + ".remember" : registration)
                    .append("(this.").append(address).append(", this);\n");
            text.append(indent).append("    }\n").append(indent).append("    private static native ").append(nativeResult).append(' ').append(nativeName)
                    .append('(').append(String.join(", ", nativeFormals)).append(')').append(throwsClause).append(";\n");
            if (addressResult) text.append(indent).append("    private static native ").append(result).append(' ').append(conversion).append("(long address);\n");
            bindings.add(new BridgeJavaSources.Binding(type.binaryName(), nativeName, "(" + parameterDescriptors + ")"
                    + (constructor || addressResult ? "J" : BridgeJavaTypes.descriptor(method.result())), method, entries.get(method.target().orElseThrow()), conversion, parameters.tokens()));
        }
        nested(text, type, artifact, admission, entries, bindings, registrations, facades, enums, snapshots, roots, permanentCache, annotation, ensure, indent);
        text.append(indent).append("}\n");
    }

    private static void nested(StringBuilder text, BridgeApiFacts.Type type, CompilationArtifact artifact, BridgeObjectAdmission admission,
            Map<BridgeCallableId, String> entries, List<BridgeJavaSources.Binding> bindings,
            List<BridgeJavaSources.FacadeRegistration> registrations, List<Facade> facades, List<EnumFacade> enums,
            BridgeCustomSnapshotSources.Context snapshots, RootContext roots,
            String permanentCache, String annotation, String ensure, String indent) {
        for (var nested : admission.surface().types()) {
            if (nested.enclosingType().filter(type.binaryName()::equals).isPresent()) {
                emit(text, nested, artifact, admission, entries, bindings, registrations, facades, enums, snapshots, roots, permanentCache, annotation, ensure, indent + "    ");
            }
        }
    }

    static void identity(StringBuilder text, BridgeApiFacts.Type type, String address, String typeName, Set<String> occupied, String indent) {
        var inherited = type.callables().stream().filter(method -> method.owner().equals("ironwood.lang.Object"))
                .map(BridgeApiFacts.Callable::name).collect(Collectors.toSet());
        if (inherited.contains("equals")) text.append(indent).append("    @Override public boolean equals(java.lang.Object other) { return this == other; }\n");
        if (!inherited.contains("hashCode") && !inherited.contains("toString")) return;
        String hash = unique(occupied, "$ironwood$identityHash");
        text.append(indent).append("    private int ").append(hash).append("() {\n")
                .append(indent).append("        long mixed = this.").append(address).append(";\n")
                .append(indent).append("        mixed ^= mixed >>> 33; mixed *= 0xff51afd7ed558ccdL;\n")
                .append(indent).append("        mixed ^= mixed >>> 33; mixed *= 0xc4ceb9fe1a85ec53L;\n")
                .append(indent).append("        mixed ^= mixed >>> 33; return (int) (mixed ^ (mixed >>> 32));\n")
                .append(indent).append("    }\n");
        if (inherited.contains("hashCode")) text.append(indent).append("    @Override public int hashCode() { return ").append(hash).append("(); }\n");
        if (inherited.contains("toString")) {
            text.append(indent).append("    @Override public java.lang.String toString() {\n")
                    .append(indent).append("        int value = ").append(hash).append("();\n")
                    .append(indent).append("        char[] digits = new char[8]; int start = 8;\n")
                    .append(indent).append("        do { digits[--start] = \"0123456789abcdef\".charAt(value & 15); value >>>= 4; } while (value != 0);\n")
                    .append(indent).append("        return this.").append(typeName).append(" + \"@\" + new java.lang.String(digits, start, 8 - start);\n")
                    .append(indent).append("    }\n");
        }
    }

    static String unique(Set<String> occupied, String name) {
        while (!occupied.add(name)) name += "$";
        return name;
    }

    static String javaType(IrType type, BridgeExportSurface surface) {
        if (type.isNominalReference()) {
            var declared = surface.types().stream().filter(candidate -> candidate.binaryName().equals(type.referenceName())).findFirst();
            if (declared.isPresent()) return declared.orElseThrow().sourceName();
        }
        return BridgeJavaTypes.sourceName(type);
    }
}
