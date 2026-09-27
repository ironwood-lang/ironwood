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

/** Concrete permanent Java facades. The separate native generator must implement every declared binding. */
public final class BridgePermanentJavaSources {
    private static final String HEADER = "// SPDX-License-Identifier: MIT OR Apache-2.0\n\n";
    private BridgePermanentJavaSources() {}

    public record Facade(String binaryName, String addressField, String typeNameField) {
        public String constructorDescriptor() { return "(JLjava/lang/Void;)V"; }
    }

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
        var surface = admission.surface();
        var entries = admission.entries().entries().stream().collect(Collectors.toMap(
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
            emit(text, type, artifact, admission, entries, bindings, registrations, facades, enums, snapshots, annotation, ensure, "");
            sources.put(type.binaryName().replace('.', '/') + ".java", text.toString());
        }
        if (!bindings.stream().map(BridgeJavaSources.Binding::entrySymbol).collect(Collectors.toSet()).equals(admission.entries().entrySymbols())) {
            throw new IllegalArgumentException("permanent declarations do not cover every admitted entry");
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
        if (!facades.isEmpty()) {
            var cache = BridgeIdentityCacheSources.generate(artifact, admission, generation);
            sources.putAll(cache.sources()); types.addAll(cache.types());
        }
        var exceptions = BridgeExceptionSources.generate(artifact, generation, admission.lifetime().exceptions().projection());
        sources.putAll(exceptions.sources()); types.addAll(exceptions.types());
        return new Sources(new BridgeJavaSources(sources, bindings, new ArrayList<>(types), ensure, registrations), facades, enums);
    }

    private static void emit(StringBuilder text, BridgeApiFacts.Type type, CompilationArtifact artifact, BridgeObjectAdmission admission,
            Map<BridgeCallableId, String> entries, List<BridgeJavaSources.Binding> bindings,
            List<BridgeJavaSources.FacadeRegistration> registrations, List<Facade> facades, List<EnumFacade> enums,
            BridgeCustomSnapshotSources.Context snapshots,
            String annotation, String ensure, String indent) {
        if (type.throwable()) {
            if (snapshots == null) throw new IllegalArgumentException("custom snapshot declaration has no complete projection");
            BridgeCustomSnapshotSources.emit(text, type, admission.surface(), snapshots, annotation, indent);
            nested(text, type, artifact, admission, entries, bindings, registrations, facades, enums, snapshots, annotation, ensure, indent);
            text.append(indent).append("}\n"); return;
        }
        if (type.kind() == BridgeApiFacts.Kind.ENUM) {
            enums.add(BridgeEnumJavaSources.emit(text, type, artifact, admission.surface(), entries, bindings, annotation, ensure, indent));
            nested(text, type, artifact, admission, entries, bindings, registrations, facades, enums, snapshots, annotation, ensure, indent);
            text.append(indent).append("}\n");
            return;
        }
        String simple = type.sourceName().substring(type.sourceName().lastIndexOf('.') + 1);
        boolean facade = admission.lifetime().references().containsKey(IrType.reference(type.binaryName()));
        var occupied = type.callables().stream().map(BridgeApiFacts.Callable::name).collect(Collectors.toCollection(HashSet::new));
        type.fields().forEach(field -> occupied.add(field.name()));
        type.callables().forEach(method -> occupied.addAll(method.parameterNames()));
        String address = unique(occupied, "$ironwood$address"), typeName = unique(occupied, "$ironwood$type");
        String receiver = unique(occupied, "$ironwood$receiver"), registration = unique(occupied, "$ironwood$remember");
        text.append(indent).append(annotation).append(indent).append("public ")
                .append(type.enclosingType().isPresent() ? "static " : "").append("final class ").append(simple).append(" {\n")
                .append(indent).append("    static { ").append(ensure).append("(); }\n");
        if (facade) {
            facades.add(new Facade(type.binaryName(), address, typeName));
            text.append(indent).append("    private final long ").append(address).append(";\n")
                    .append(indent).append("    private final java.lang.String ").append(typeName).append(" = ")
                    .append(BridgeJavaSources.quote(type.binaryName())).append(";\n")
                    .append(indent).append("    private ").append(simple).append("(long value, java.lang.Void marker) { this.")
                    .append(address).append(" = value; }\n");
            if (type.callables().stream().anyMatch(method -> method.kind() == IrCallableKind.CONSTRUCTOR)) {
                registrations.add(new BridgeJavaSources.FacadeRegistration(type.binaryName(), registration));
                text.append(indent).append("    private static native void ").append(registration)
                        .append("(long address, ").append(type.sourceName()).append(" facade);\n");
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
            if ((!method.isStatic() || constructor) && !facade) throw new IllegalArgumentException("instance member lacks permanent facade proof");
            String nativeName = unique(occupied, "$ironwood$native$" + next++);
            var formals = new ArrayList<String>();
            for (int index = 0; index < method.parameters().size(); index++) {
                formals.add(javaType(method.parameters().get(index), admission.surface()) + " " + method.parameterNames().get(index));
            }
            var arguments = new ArrayList<>(method.parameterNames());
            var nativeFormals = new ArrayList<>(formals);
            String parameterDescriptors = method.parameters().stream().map(BridgeJavaTypes::descriptor).collect(Collectors.joining());
            if (!method.isStatic() && !constructor) {
                nativeFormals.addFirst("long " + receiver); arguments.addFirst("this." + address);
                parameterDescriptors = "J" + parameterDescriptors;
            }
            String throwsClause = method.thrownTypes().isEmpty() ? "" : " throws " + method.thrownTypes().stream()
                    .map(thrown -> javaType(thrown, admission.surface())).collect(Collectors.joining(", "));
            String result = constructor ? "long" : javaType(method.result(), admission.surface());
            text.append(indent).append("    public ");
            if (constructor) text.append(simple);
            else text.append(method.isStatic() ? "static " : "").append(result).append(' ').append(method.name());
            text.append('(').append(String.join(", ", formals)).append(')').append(throwsClause).append(" {\n")
                    .append(indent).append("        ").append(constructor ? "this." + address + " = " : method.result().equals(IrType.VOID) ? "" : "return ")
                    .append(nativeName).append('(').append(String.join(", ", arguments)).append(");\n");
            if (constructor) text.append(indent).append("        ").append(registration).append("(this.").append(address).append(", this);\n");
            text.append(indent).append("    }\n").append(indent).append("    private static native ").append(result).append(' ').append(nativeName)
                    .append('(').append(String.join(", ", nativeFormals)).append(')').append(throwsClause).append(";\n");
            bindings.add(new BridgeJavaSources.Binding(type.binaryName(), nativeName, "(" + parameterDescriptors + ")"
                    + (constructor ? "J" : BridgeJavaTypes.descriptor(method.result())), method, entries.get(method.target().orElseThrow())));
        }
        nested(text, type, artifact, admission, entries, bindings, registrations, facades, enums, snapshots, annotation, ensure, indent);
        text.append(indent).append("}\n");
    }

    private static void nested(StringBuilder text, BridgeApiFacts.Type type, CompilationArtifact artifact, BridgeObjectAdmission admission,
            Map<BridgeCallableId, String> entries, List<BridgeJavaSources.Binding> bindings,
            List<BridgeJavaSources.FacadeRegistration> registrations, List<Facade> facades, List<EnumFacade> enums,
            BridgeCustomSnapshotSources.Context snapshots,
            String annotation, String ensure, String indent) {
        for (var nested : admission.surface().types()) {
            if (nested.enclosingType().filter(type.binaryName()::equals).isPresent()) {
                emit(text, nested, artifact, admission, entries, bindings, registrations, facades, enums, snapshots, annotation, ensure, indent + "    ");
            }
        }
    }

    private static void identity(StringBuilder text, BridgeApiFacts.Type type, String address, String typeName, Set<String> occupied, String indent) {
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
