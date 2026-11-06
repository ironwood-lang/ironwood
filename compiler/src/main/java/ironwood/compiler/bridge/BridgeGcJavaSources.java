// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.bridge;

import ironwood.compiler.BridgeObjectAdmission;
import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeApiFacts;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/** GC0's handle projection, independent of ordinary source lifetime member names. */
final class BridgeGcJavaSources {
    private static final String HEADER = "// SPDX-License-Identifier: MIT OR Apache-2.0\n";
    private BridgeGcJavaSources() {}

    static String tag(String binaryName) { return HexFormat.of().formatHex(binaryName.getBytes(StandardCharsets.UTF_8)); }
    static String registrationTag(BridgeApiFacts.Callable method) {
        String json = "{\"kind\":" + BridgeJavaSources.quote(method.kind().name()) + ",\"name\":" + BridgeJavaSources.quote(method.name())
                + ",\"owner\":" + BridgeJavaSources.quote(method.owner()) + ",\"parameters\":["
                + method.parameters().stream().map(BridgeGcJavaSources::typeIdentity).map(BridgeJavaSources::quote).collect(Collectors.joining(","))
                + "],\"result\":" + BridgeJavaSources.quote(typeIdentity(method.result())) + "}";
        return tag(json);
    }
    private static String typeIdentity(IrType type) {
        if (type.isTypeParameter()) return type.referenceName();
        if (type.isArray()) return typeIdentity(type.elementType()) + "[]";
        if (type.isNominalReference()) return type.referenceName() + (type.typeArguments().isEmpty() ? "" : "<"
                + type.typeArguments().stream().map(BridgeGcJavaSources::typeIdentity).collect(Collectors.joining(",")) + ">");
        return type.displayName();
    }
    static String javaType(IrType type, BridgeExportSurface surface) { return BridgePermanentJavaSources.javaType(type, surface); }
    static boolean handle(BridgeObjectAdmission admission, IrType type) {
        return type.isTypeParameter() || BridgeRootCalls.rooted(admission, type);
    }

    static BridgePermanentJavaSources.Sources generate(CompilationArtifact artifact, BridgeObjectAdmission admission, BridgeGeneration generation) {
        if (!admission.managedHeap() || !generation.matchesObjects(artifact, admission)) throw new IllegalArgumentException("GC Java projection identity");
        String support = generation.supportPackage();
        var surface = admission.surface();
        String ensure = BridgePermanentJavaSources.unique(surface.types().stream().flatMap(t -> t.callables().stream())
                .map(BridgeApiFacts.Callable::name).collect(Collectors.toCollection(HashSet::new)), "$ironwood$ensure");
        if (surface.types().stream().anyMatch(t -> t.packageName().equals("ironwood.bridge.generated") || t.packageName().startsWith("ironwood.bridge.generated.")))
            throw new IllegalArgumentException("reserved generated bridge namespace");
        String annotation = "@" + support + ".Identity(" + BridgeJavaSources.quote(generation.identity()) + ")\n";
        var sources = new TreeMap<String, String>();
        var types = new TreeSet<String>();
        var bindings = new ArrayList<BridgeJavaSources.Binding>();
        var natives = new ArrayList<BridgeJavaSources.NativeDeclaration>();
        var facades = new ArrayList<BridgePermanentJavaSources.Facade>();
        var enums = new ArrayList<BridgePermanentJavaSources.EnumFacade>();
        var entries = admission.entries().primaryEntries().stream().collect(Collectors.toMap(e -> e.root().callable(), e -> e.function().linkageName()));
        var roots = new StringBuilder(HEADER).append("package ").append(support).append(";\n").append(annotation)
                .append("public final class Roots {\n    private Roots() {}\n    static { Support.").append(ensure).append("(); }\n");
        var listeners = new StringBuilder(HEADER).append("package ").append(support).append(";\n").append(annotation)
                .append("public final class Listeners {\n    private Listeners() {}\n    static { Support.").append(ensure).append("(); }\n")
                .append("    public static ListenerRegistration open() { return $open(); }\n")
                .append("    private static native ListenerRegistration $open();\n");
        var snapshots = admission.lifetime().exceptions().projection();
        var custom = snapshots.customTypes().isEmpty() ? null : new BridgeCustomSnapshotSources.Context(snapshots,
                BridgeCustomSnapshotLayout.create(artifact, snapshots), support + ".SnapshotData");
        var critical = BridgeCriticalSources.plan(admission, generation);
        for (var type : surface.types()) {
            types.add(type.binaryName());
            if (type.enclosingType().isPresent()) continue;
            var text = new StringBuilder(HEADER).append("package ").append(type.packageName()).append(";\n")
                    .append("import static ").append(support).append(".Support.").append(ensure).append(";\n");
            emit(text, roots, listeners, type, artifact, admission, generation, annotation, entries, bindings, natives, facades, enums, custom, critical, ensure, "");
            sources.put(type.binaryName().replace('.', '/') + ".java", text.toString());
        }
        roots.append("}\n");
        put(sources, types, support + ".Roots", roots.toString());
        if (admission.entries().gcCallbacks().isPresent()) {
            put(sources, types, support + ".Listeners", listeners.append("}\n").toString());
            put(sources, types, support + ".ListenerRegistration", HEADER + "package " + support + ";\n" + annotation + REGISTRATION);
            natives.add(new BridgeJavaSources.NativeDeclaration(support + ".Listeners", "$open", "()L" + support.replace('.', '/') + "/ListenerRegistration;"));
            natives.add(new BridgeJavaSources.NativeDeclaration(support + ".ListenerRegistration", "$close", "(J)V"));
        }
        put(sources, types, support + ".RootState", HEADER + "package " + support + ";\n" + annotation + STATE);
        put(sources, types, support + ".RootHandle", HEADER + "package " + support + ";\n" + annotation + HANDLE);
        natives.add(new BridgeJavaSources.NativeDeclaration(support + ".RootHandle", "$release", "(J)V"));
        put(sources, types, support + ".BridgeLifetimeException", HEADER + "package " + support + ";\n" + annotation
                + "final class BridgeLifetimeException extends IllegalStateException {\n"
                + "    private static final long serialVersionUID = 1L;\n"
                + "    BridgeLifetimeException(String message) { super(message); }\n}\n");
        put(sources, types, support + ".Identity", HEADER + "package " + support + ";\n" + annotation
                + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                + "@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)\npublic @interface Identity { String value(); }\n");
        for (String pkg : surface.types().stream().map(BridgeApiFacts.Type::packageName).distinct().toList()) {
            String marker = pkg + "." + BridgeExportSurface.PACKAGE_MARKER;
            put(sources, types, marker, HEADER + "package " + pkg + ";\n" + annotation
                    + "public final class " + BridgeExportSurface.PACKAGE_MARKER + " { private " + BridgeExportSurface.PACKAGE_MARKER + "() {} }\n");
        }
        var exceptions = BridgeExceptionSources.generate(artifact, generation, snapshots,
                admission.entries().gcCallbacks().map(BridgeGcCallbacks::carriers).orElse(null));
        sources.putAll(exceptions.sources()); types.addAll(exceptions.types());
        types.add(support + ".Support");
        if (critical != null && critical.count() > 0) {
            put(sources, types, BridgeCriticalSources.binaryName(generation), BridgeCriticalSources.javaSource(generation, ensure));
            natives.addAll(BridgeCriticalSources.declarations(generation));
        }
        if (!bindings.stream().map(BridgeJavaSources.Binding::entrySymbol).collect(Collectors.toSet()).equals(entries.values().stream().collect(Collectors.toSet())))
            throw new IllegalArgumentException("GC projection omitted a source entry");
        var declarations = new BridgeJavaSources(sources, bindings, new ArrayList<>(types), ensure, List.of(), List.of(), natives);
        if (admission.entries().gcCallbacks().isPresent()) declarations = BridgeCallbackDispatchSources.add(declarations,
                admission.entries().gcCallbacks().orElseThrow().listeners(), generation, surface);
        if (admission.batching().isPresent()) declarations = BridgeCallbackBatchSources.addDispatch(declarations,
                admission.batching().orElseThrow(), generation, surface);
        return new BridgePermanentJavaSources.Sources(declarations, facades, enums);
    }

    private static void put(Map<String, String> sources, Set<String> types, String name, String source) {
        if (!types.add(name) || sources.putIfAbsent(name.replace('.', '/') + ".java", source) != null)
            throw new IllegalArgumentException("generated GC support collision: " + name);
    }

    private static String variables(BridgeApiFacts.Type type, BridgeExportSurface surface) {
        return type.typeParameters().stream().map(v -> v.name() + (v.upperBounds().equals(List.of(IrType.reference("ironwood.lang.Object"))) ? ""
                : " extends " + v.upperBounds().stream().map(t -> javaType(t, surface)).collect(Collectors.joining(" & ")))).collect(Collectors.joining(", "));
    }

    private static void emit(StringBuilder text, StringBuilder roots, StringBuilder listeners, BridgeApiFacts.Type type, CompilationArtifact artifact,
            BridgeObjectAdmission admission, BridgeGeneration generation, String annotation, Map<BridgeCallableId, String> entries,
            List<BridgeJavaSources.Binding> bindings, List<BridgeJavaSources.NativeDeclaration> natives,
            List<BridgePermanentJavaSources.Facade> facades, List<BridgePermanentJavaSources.EnumFacade> enums,
            BridgeCustomSnapshotSources.Context custom, BridgeCriticalSources.Plan critical, String ensure, String indent) {
        String support = generation.supportPackage();
        var surface = admission.surface();
        if (type.kind() == BridgeApiFacts.Kind.INTERFACE) {
            text.append(indent).append(annotation).append(indent).append("public interface ")
                    .append(type.sourceName().substring(type.sourceName().lastIndexOf('.') + 1)).append(" {\n");
            for (var method : type.callables()) {
                if (method.owner().equals("ironwood.lang.Object")) continue;
                var parameters = new ArrayList<String>();
                for (int i = 0; i < method.parameters().size(); i++) parameters.add(javaType(method.parameters().get(i), surface)
                        + " " + method.parameterNames().get(i));
                text.append(indent).append("    ").append(javaType(method.result(), surface)).append(' ').append(method.name())
                        .append('(').append(String.join(", ", parameters)).append(')');
                if (!method.thrownTypes().isEmpty()) text.append(" throws ").append(method.thrownTypes().stream()
                        .map(value -> javaType(value, surface)).collect(Collectors.joining(", ")));
                text.append(";\n");
            }
        } else if (type.throwable()) {
            if (custom == null) throw new IllegalArgumentException("missing custom snapshot layout");
            BridgeCustomSnapshotSources.emit(text, type, surface, custom, annotation, indent);
        } else if (type.kind() == BridgeApiFacts.Kind.ENUM) {
            enums.add(BridgeEnumJavaSources.emit(text, type, artifact, admission, support + ".RootState", entries, bindings, annotation,
                    ensure, indent));
        } else {
            String simple = type.sourceName().substring(type.sourceName().lastIndexOf('.') + 1);
            String variableNames = type.typeParameters().stream().map(BridgeTypeParameter::name).collect(Collectors.joining(", "));
            String facadeType = type.sourceName() + (type.generic() ? "<" + variableNames + ">" : "");
            String variables = variables(type, surface);
            var occupied = type.callables().stream().map(BridgeApiFacts.Callable::name).collect(Collectors.toCollection(HashSet::new));
            type.fields().forEach(f -> occupied.add(f.name()));
            type.callables().forEach(m -> occupied.addAll(m.parameterNames()));
            String address = BridgePermanentJavaSources.unique(occupied, "$ironwood$address");
            String state = BridgePermanentJavaSources.unique(occupied, "$ironwood$state");
            String typeName = BridgePermanentJavaSources.unique(occupied, "$ironwood$type");
            text.append(indent).append(annotation).append(indent).append("public ").append(type.enclosingType().isPresent() ? "static " : "")
                    .append("final class ").append(simple).append(type.generic() ? "<" + variables + ">" : "").append(" {\n")
                    .append(indent).append("    static { ").append(ensure).append("(); }\n")
                    .append(indent).append("    private final long ").append(address).append(";\n")
                    .append(indent).append("    private final ").append(support).append(".RootState ").append(state).append(";\n")
                    .append(indent).append("    private final java.lang.String ").append(typeName).append(" = ").append(BridgeJavaSources.quote(type.binaryName())).append(";\n")
                    .append(indent).append("    private ").append(simple).append("(long address, ").append(support)
                    .append(".RootState state, java.lang.Void marker) { this.").append(address).append(" = address; this.").append(state).append(" = state; }\n");
            facades.add(new BridgePermanentJavaSources.Facade(type.binaryName(), address, typeName, state, support + ".RootState"));
            BridgePermanentJavaSources.identity(text, type, address, typeName, occupied, indent);
            for (var field : type.fields()) text.append(indent).append("    public static final ").append(javaType(field.type(), surface))
                    .append(' ').append(field.name()).append(" = ").append(BridgeJavaSources.literal(field.constant().orElseThrow())).append(";\n");
            String retain = "$retain$" + facades.size();
            roots.append("    @SuppressWarnings(\"unchecked\")\n    public static ").append(type.generic() ? "<" + variables + "> " : "")
                    .append(support).append(".RootHandle<").append(facadeType).append("> retain(").append(facadeType).append(" value) {\n")
                    .append("        return value == null ? null : (").append(support).append(".RootHandle<").append(facadeType).append(">) ")
                    .append(retain).append("(value);\n    }\n    private static native RootHandle<?> ").append(retain).append('(')
                    .append(BridgePermanentJavaSources.javaErasedType(type.exactType(), surface)).append(" value);\n");
            natives.add(new BridgeJavaSources.NativeDeclaration(support + ".Roots", retain,
                    "(" + BridgeJavaTypes.descriptor(type.exactType()) + ")L" + support.replace('.', '/') + "/RootHandle;"));
            var transport = new BridgeCriticalSources.Emitter(critical, occupied);
            for (var method : type.callables()) {
                if (method.owner().equals("ironwood.lang.Object")) continue;
                boolean constructor = method.kind() == IrCallableKind.CONSTRUCTOR;
                boolean retaining = admission.entries().gcCallbacks().map(value -> value.retaining(method.target().orElseThrow())).orElse(false);
                boolean explicitReceiver = retaining && !constructor && !method.isStatic();
                var batch = admission.batching().map(value -> value.entries().get(method.target().orElseThrow())).orElse(null);
                boolean handle = constructor || handle(admission, method.result());
                StringBuilder owner = retaining ? listeners : constructor ? roots : text;
                String pad = constructor || retaining ? "" : indent;
                String registration = BridgePermanentJavaSources.unique(occupied, "$ironwood$registration");
                String receiver = BridgePermanentJavaSources.unique(occupied, "$ironwood$receiver");
                var formals = new ArrayList<String>();
                for (int i = 0; i < method.parameters().size(); i++) formals.add(javaType(method.parameters().get(i), surface) + " " + method.parameterNames().get(i));
                var parameters = BridgeEnumArgumentSources.generate(owner, artifact, admission, method, occupied, pad);
                var arguments = new ArrayList<>(parameters.arguments());
                var nativeFormals = new ArrayList<>(parameters.nativeFormals());
                String descriptor = parameters.descriptor();
                if (!constructor && !method.isStatic()) {
                    nativeFormals.addFirst(explicitReceiver ? BridgePermanentJavaSources.javaErasedType(type.exactType(), surface) + " " + receiver : "long receiver");
                    arguments.addFirst(explicitReceiver ? receiver : "this." + address);
                    descriptor = (explicitReceiver ? BridgeJavaTypes.descriptor(type.exactType()) : "J") + descriptor;
                }
                if (explicitReceiver) formals.addFirst(facadeType + " " + receiver);
                if (retaining) {
                    formals.addFirst(support + ".ListenerRegistration " + registration);
                    nativeFormals.addFirst("long " + registration);
                    arguments.addFirst(registration + ".requireOpen()"); descriptor = "J" + descriptor;
                }
                String batchName = BridgePermanentJavaSources.unique(occupied, "$ironwood$batch");
                if (batch != null) {
                    nativeFormals.add("java.nio.LongBuffer " + batchName);
                    arguments.add(batchName); descriptor += "Ljava/nio/LongBuffer;";
                }
                String nativeName = BridgePermanentJavaSources.unique(occupied, "$ironwood$native$" + bindings.size());
                String result = constructor ? support + ".RootHandle<" + facadeType + ">"
                        : handle ? support + ".RootHandle<" + javaType(method.result(), surface) + ">" : javaType(method.result(), surface);
                String nativeResult = handle ? support + ".RootHandle<?>" : BridgeGenericDomain.dependent(method.result())
                        ? BridgePermanentJavaSources.javaErasedType(method.result(), surface) : result;
                String throwsClause = method.thrownTypes().isEmpty() ? "" : " throws "
                        + method.thrownTypes().stream().map(t -> javaType(t, surface)).collect(Collectors.joining(", "));
                boolean plain = !retaining && !constructor && !handle && !method.result().isReference()
                        && method.parameters().stream().allMatch(t -> !t.isReference() || surface.types().stream()
                            .anyMatch(decl -> decl.kind() == BridgeApiFacts.Kind.ENUM && decl.binaryName().equals(t.referenceName())));
                var reserved = plain && batch == null && transport.admits(entries.get(method.target().orElseThrow())) ? transport.reserve() : null;
                if (handle || BridgeGenericDomain.dependent(method.result())) owner.append(pad).append("    @SuppressWarnings(\"unchecked\")\n");
                owner.append(pad).append("    public ").append(constructor || retaining || method.isStatic() ? "static " : "")
                        .append((constructor || retaining) && type.generic() ? "<" + variables + "> " : "").append(result).append(' ')
                        .append(retaining ? (constructor ? "construct_" : "install_") + registrationTag(method)
                                : constructor ? "construct_" + tag(type.binaryName()) : method.name()).append('(').append(String.join(", ", formals))
                        .append(')').append(throwsClause).append(" {\n");
                if (explicitReceiver) owner.append(pad).append("        if (").append(receiver)
                        .append(" == null) throw new java.lang.NullPointerException(\"receiver\");\n");
                else if (!constructor && !method.isStatic()) owner.append(pad).append("        this.").append(state).append(".checkLive();\n");
                if (batch != null) owner.append(pad).append("        java.nio.LongBuffer ").append(batchName).append(" = ")
                        .append(method.parameterNames().get(batch.countInput() - 1)).append(" >= ").append(BridgeCallbackBatching.MINIMUM_COUNT)
                        .append(" ? this.").append(state).append(".beginBatch() : null;\n")
                        .append(pad).append("        try {\n");
                owner.append(pad).append("        ").append(method.result().equals(IrType.VOID) && !constructor ? "" : "return ")
                        .append(handle || BridgeGenericDomain.dependent(method.result()) ? "(" + result + ") " : "")
                        .append(reserved == null ? nativeName : reserved.call()).append('(').append(String.join(", ", arguments)).append(");\n");
                if (batch != null) owner.append(pad).append("        } finally { if (")
                        .append(method.parameterNames().get(batch.countInput() - 1)).append(" >= ").append(BridgeCallbackBatching.MINIMUM_COUNT)
                        .append(") this.").append(state).append(".endBatch(); }\n");
                owner.append(pad).append("    }\n").append(pad).append("    private static native ").append(nativeResult).append(' ').append(nativeName)
                        .append('(').append(String.join(", ", nativeFormals)).append(')').append(throwsClause).append(";\n");
                if (reserved != null) transport.emit(owner, reserved, nativeName, nativeFormals, nativeResult,
                        BridgeCriticalSources.Carrier.of(false, method.result()), throwsClause, pad);
                bindings.add(new BridgeJavaSources.Binding(retaining ? support + ".Listeners" : constructor ? support + ".Roots" : type.binaryName(), nativeName,
                        "(" + descriptor + ")" + (handle ? "L" + support.replace('.', '/') + "/RootHandle;" : BridgeJavaTypes.descriptor(method.result())),
                        method, entries.get(method.target().orElseThrow()), "", parameters.tokens(), Map.of(), reserved == null ? -1 : reserved.index()));
            }
        }
        for (var nested : surface.types()) if (nested.enclosingType().filter(type.binaryName()::equals).isPresent())
            emit(text, roots, listeners, nested, artifact, admission, generation, annotation, entries, bindings, natives, facades, enums, custom, critical, ensure, indent + "    ");
        text.append(indent).append("}\n");
    }

    private static final String STATE = """
            public final class RootState {
                private int status;
                private RootState() {}
                public void checkLive() {
                    if (status != 0) throw new BridgeLifetimeException("native facade lifetime is closed");
                }
                private int batchDepth;
                private static final boolean BATCH_DISABLED = java.lang.Boolean.getBoolean("ironwood.bridge.batch.disable");
                private java.nio.LongBuffer[] batchBuffers;
                public java.nio.LongBuffer beginBatch() {
                    int depth = batchDepth++;
                    try {
                        if (BATCH_DISABLED) return null;
                        if (batchBuffers == null) batchBuffers = new java.nio.LongBuffer[4];
                        if (depth >= batchBuffers.length) batchBuffers = java.util.Arrays.copyOf(batchBuffers, depth + 1);
                        java.nio.LongBuffer buffer = batchBuffers[depth];
                        if (buffer == null) {
                            buffer = java.nio.ByteBuffer.allocateDirect(32768).order(java.nio.ByteOrder.nativeOrder()).asLongBuffer();
                            batchBuffers[depth] = buffer;
                        }
                        return buffer;
                    } catch (java.lang.OutOfMemoryError unavailable) {
                        return null;
                    }
                }
                public void endBatch() { batchDepth--; }
            }
            """;
    private static final String REGISTRATION = """
            public final class ListenerRegistration implements AutoCloseable {
                private long id;
                private ListenerRegistration(long id) { this.id = id; }
                long requireOpen() {
                    if (id == 0) throw new BridgeLifetimeException("listener registration is closed");
                    return id;
                }
                @Override public void close() {
                    long current = id;
                    if (current == 0) return;
                    id = 0;
                    $close(current);
                }
                private static native void $close(long id);
            }
            """;
    private static final String HANDLE = """
            public final class RootHandle<T> implements AutoCloseable {
                private final T value;
                private long anchor;
                private RootHandle(T value, long anchor) { this.value = value; this.anchor = anchor; }
                public T value() {
                    if (anchor == 0) throw new BridgeLifetimeException("native handle is closed");
                    return value;
                }
                @Override public void close() {
                    long current = anchor;
                    if (current == 0) return;
                    anchor = 0;
                    $release(current);
                }
                private static native void $release(long anchor);
            }
            """;
}
