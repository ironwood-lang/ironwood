// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.BridgeOwnedCallbackAdmission;
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

/** Private facade metadata and exact Java/native guard partition for admitted owners. */
public final class BridgeOwnedCallbackJavaSources {
    private static final String HEADER = "// SPDX-License-Identifier: MIT OR Apache-2.0\n\n";
    private BridgeOwnedCallbackJavaSources() {}

    public enum Transport { VALUE, LISTENER, LOCAL_OWNER, FOREIGN_OWNER }
    public record Input(int index, IrType type, Transport transport, boolean ownerRecord) {}
    public record Call(BridgeJavaSources.Binding binding, List<Input> inputs, boolean constructor, boolean callback) {
        public Call { inputs = List.copyOf(inputs); }
    }
    public record Sources(BridgeJavaSources declarations, List<BridgePermanentJavaSources.Facade> facades, List<Call> calls,
                          BridgeCallbackBatching batching) {
        public Sources { facades = List.copyOf(facades); calls = List.copyOf(calls); }
    }

    public static Sources generate(BridgeOwnedCallbackAdmission admission, BridgeGeneration generation) {
        return generate(admission, generation, BridgeCallbackBatching.prove(admission));
    }

    public static Sources generate(BridgeOwnedCallbackAdmission admission, BridgeGeneration generation, BridgeCallbackBatching batching) {
        if (!batching.matches(admission)) throw new IllegalArgumentException("callback batching requires exact admitted program");
        if (!generation.matchesOwnedCallbacks(admission)) throw new IllegalArgumentException("owner facades require exact native admission");
        var surface = admission.surface();
        String support = generation.supportPackage(), stateType = support + ".RootState";
        if (surface.types().stream().anyMatch(type -> type.packageName().equals(support) || type.enclosingType().isPresent())) {
            throw new IllegalArgumentException("owner facade namespace or nesting is unsupported");
        }
        var entrySymbols = admission.entries().entries().stream().collect(Collectors.toMap(
                entry -> entry.root().callable(), entry -> entry.function().linkageName()));
        var callbacks = admission.callbacks().entries().stream().collect(Collectors.toMap(BridgeOwnedCallbackEntries.Entry::callable, entry -> entry));
        var slots = admission.slots().stream().flatMap(proof -> proof.entries().entries().stream()).collect(Collectors.toMap(
                BridgeListenerSlotEntries.Entry::callable, entry -> entry));
        var owners = admission.lifetime().protocol().constructedRootTypes();
        var listeners = admission.listeners().proxies().stream().map(proxy -> IrType.reference(proxy.listener().binaryName())).collect(Collectors.toSet());
        var occupied = new HashSet<String>();
        surface.types().forEach(type -> {
            type.fields().forEach(field -> occupied.add(field.name()));
            type.callables().forEach(method -> { occupied.add(method.name()); occupied.addAll(method.parameterNames()); });
        });
        String ensure = unique(occupied, "$ironwood$ensure");
        String annotation = "@" + support + ".Identity(" + BridgeJavaSources.quote(generation.identity()) + ")\n";
        var sources = new TreeMap<String, String>();
        var types = new TreeSet<String>();
        var facades = new ArrayList<BridgePermanentJavaSources.Facade>();
        var bindings = new ArrayList<BridgeJavaSources.Binding>();
        var calls = new ArrayList<Call>();
        var destructions = new ArrayList<BridgeJavaSources.RootDestruction>();
        for (var type : surface.types()) {
            String simple = type.sourceName().substring(type.sourceName().lastIndexOf('.') + 1);
            StringBuilder text = new StringBuilder(HEADER).append("package ").append(type.packageName()).append(";\n\n")
                    .append("import static ").append(support).append(".Support.").append(ensure).append(";\n\n").append(annotation);
            boolean listener = type.kind() == BridgeApiFacts.Kind.INTERFACE;
            boolean owner = owners.contains(IrType.reference(type.binaryName()));
            text.append("public ").append(listener ? "interface " : "final class ").append(simple).append(" {\n");
            String address = unique(occupied, "$ironwood$address"), state = unique(occupied, "$ironwood$state"), typeName = unique(occupied, "$ironwood$type");
            if (!listener) text.append("    static { ").append(ensure).append("(); }\n");
            if (owner) {
                text.append("    private final long ").append(address).append(";\n    private final ").append(stateType).append(' ').append(state)
                        .append(";\n    private final java.lang.String ").append(typeName).append(" = ").append(BridgeJavaSources.quote(type.binaryName())).append(";\n");
                facades.add(new BridgePermanentJavaSources.Facade(type.binaryName(), address, typeName, state, stateType));
                // Reconstitutes only an existing authoritative owner and never
                // allocates native storage or retargets a live Java reference.
                text.append("    private ").append(simple).append("(long address, ").append(stateType)
                        .append(" state, java.lang.Void marker) { this.").append(address).append(" = address; this.")
                        .append(state).append(" = state; }\n");
                String destroy = unique(occupied, "$ironwood$destroy");
                text.append("    /** Reclaims this owner after its active callback invocations finish. */\n")
                        .append("    public void free() { if (this.").append(state).append(".prepareFree(this.").append(address).append(")) ")
                        .append(destroy).append("(this.").append(state).append(", this.").append(address).append("); }\n")
                        .append("    private static native void ").append(destroy).append('(').append(stateType).append(" state, long address);\n");
                destructions.add(new BridgeJavaSources.RootDestruction(type.binaryName(), destroy, stateType));
                BridgePermanentJavaSources.identity(text, type, address, typeName, occupied, "");
            } else if (!listener) text.append("    private ").append(simple).append("() {}\n");
            for (var field : type.fields()) text.append("    public static final ").append(javaType(field.type(), surface)).append(' ')
                    .append(field.name()).append(" = ").append(BridgeJavaSources.literal(field.constant().orElseThrow())).append(";\n");
            for (var method : type.callables()) {
                if (method.owner().equals("ironwood.lang.Object")) continue;
                String formals = java.util.stream.IntStream.range(0, method.parameters().size())
                        .mapToObj(index -> javaType(method.parameters().get(index), surface) + " " + method.parameterNames().get(index)).collect(Collectors.joining(", "));
                String throwsClause = method.thrownTypes().isEmpty() ? "" : " throws " + method.thrownTypes().stream()
                        .map(thrown -> javaType(thrown, surface)).collect(Collectors.joining(", "));
                if (listener) {
                    text.append("    ").append(javaType(method.result(), surface)).append(' ').append(method.name()).append('(').append(formals)
                            .append(')').append(throwsClause).append(";\n");
                    continue;
                }
                var id = method.target().orElseThrow();
                boolean constructor = method.kind() == IrCallableKind.CONSTRUCTOR, callback = callbacks.containsKey(id);
                if ((!method.isStatic() || constructor) && !owner) throw new IllegalArgumentException("instance callback facade lacks owner proof");
                Set<Integer> slotHolders = slots.containsKey(id) ? slots.get(id).retention().slots().stream()
                        .map(BridgeRetentionContract.Slot::holderInput).collect(Collectors.toSet()) : Set.of();
                var inputs = new ArrayList<Input>();
                var nativeFormals = new ArrayList<String>(); var arguments = new ArrayList<String>();
                var descriptor = new StringBuilder("("); var preparation = new StringBuilder(); var javaGuards = new ArrayList<String>();
                if (constructor) {
                    preparation.append("this.").append(state).append(" = new ").append(stateType).append("();\n");
                    nativeFormals.add(stateType + " reserved"); arguments.add("this." + state); descriptor.append('L').append(stateType.replace('.', '/')).append(';');
                }
                for (int index = constructor ? 1 : 0; index < id.parameters().size(); index++) {
                    var inputType = id.parameters().get(index);
                    boolean receiver = !method.isStatic() && !constructor && index == 0;
                    String argument = receiver ? "this" : method.parameterNames().get(index - (method.isStatic() ? 0 : 1));
                    boolean localOwner = owners.contains(inputType) && inputType.referenceName().equals(type.binaryName());
                    var transport = localOwner ? Transport.LOCAL_OWNER : owners.contains(inputType) ? Transport.FOREIGN_OWNER
                            : listeners.contains(inputType) ? Transport.LISTENER : Transport.VALUE;
                    boolean record = owners.contains(inputType) && (callback || slotHolders.contains(index));
                    inputs.add(new Input(index, inputType, transport, record));
                    String nativeArgument = "arg" + index;
                    if (localOwner) {
                        String local = unique(occupied, "$ironwood$owner");
                        preparation.append(stateType).append(' ').append(local).append(" = ")
                                .append(receiver ? "this." + state : argument + " == null ? null : " + argument + "." + state).append(";\n");
                        if (callback) javaGuards.add(local);
                        else preparation.append("if (").append(local).append(" != null) ").append(local).append(".checkLive();\n");
                        nativeFormals.add("long " + nativeArgument); descriptor.append('J');
                        arguments.add(receiver ? "this." + address : argument + " == null ? 0L : " + argument + "." + address);
                        if (record) {
                            nativeFormals.add("long owner" + index); descriptor.append('J');
                            arguments.add(local + " == null ? 0L : " + local + ".listenerOwner()");
                        }
                    } else {
                        nativeFormals.add(javaType(inputType, surface) + " " + nativeArgument);
                        arguments.add(argument); descriptor.append(BridgeJavaTypes.descriptor(inputType));
                    }
                }
                if (callback && !callbacks.get(id).guardedInputs().equals(inputs.stream()
                        .filter(input -> input.transport() == Transport.LOCAL_OWNER || input.transport() == Transport.FOREIGN_OWNER).map(Input::index).toList())) {
                    throw new IllegalArgumentException("facade guard partition differs from exact invocation proof");
                }
                var batch = batching.entries().get(id);
                if (batch != null) {
                    if (javaGuards.size() != 1) throw new IllegalArgumentException("batched callback requires one local owner guard");
                    nativeFormals.add("java.nio.LongBuffer batchBuffer"); descriptor.append("Ljava/nio/LongBuffer;");
                    arguments.add(method.parameterNames().get(batch.countInput() - 1) + " >= " + BridgeCallbackBatching.MINIMUM_COUNT + " ? "
                            + javaGuards.getFirst() + ".callbackBuffer() : null");
                }
                String nativeName = unique(occupied, "$ironwood$native"), result = constructor ? "long" : javaType(method.result(), surface);
                String invocation = nativeName + "(" + String.join(", ", arguments) + ");";
                String body = constructor ? "this." + address + " = " + invocation + "\nthis." + state + ".remember(this." + address + ", this);"
                        : (method.result().equals(IrType.VOID) ? "" : "return ") + invocation;
                if (callback) body = BridgeCallbackGuardSources.wrap(body, javaGuards);
                text.append("    public ").append(constructor ? simple : (method.isStatic() ? "static " : "") + result + " " + method.name())
                        .append('(').append(formals).append(')').append(throwsClause).append(" {\n").append(preparation).append(body).append("\n    }\n")
                        .append("    private static native ").append(result).append(' ').append(nativeName).append('(').append(String.join(", ", nativeFormals))
                        .append(')').append(throwsClause).append(";\n");
                var binding = new BridgeJavaSources.Binding(type.binaryName(), nativeName,
                        descriptor.append(')').append(constructor ? "J" : BridgeJavaTypes.descriptor(method.result())).toString(), method, entrySymbols.get(id));
                bindings.add(binding); calls.add(new Call(binding, inputs, constructor, callback));
            }
            text.append("}\n"); types.add(type.binaryName()); sources.put(type.binaryName().replace('.', '/') + ".java", text.toString());
        }
        if (!bindings.stream().map(binding -> binding.method().target().orElseThrow()).collect(Collectors.toSet()).equals(entrySymbols.keySet())) {
            throw new IllegalArgumentException("owner Java projection omitted admitted roots");
        }
        for (String pkg : surface.types().stream().map(BridgeApiFacts.Type::packageName).distinct().toList()) {
            String marker = pkg + "." + BridgeExportSurface.PACKAGE_MARKER;
            types.add(marker); sources.put(marker.replace('.', '/') + ".java", HEADER + "package " + pkg + ";\n" + annotation
                    + "public final class " + BridgeExportSurface.PACKAGE_MARKER + " { private " + BridgeExportSurface.PACKAGE_MARKER + "() {} }\n");
        }
        sources.put(support.replace('.', '/') + "/Identity.java", HEADER + "package " + support + ";\n" + annotation
                + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                + "@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)\npublic @interface Identity { String value(); }\n");
        types.add(support + ".Identity"); types.add(support + ".Support");
        var exceptions = BridgeExceptionSources.generate(admission.artifact(), generation, admission.exceptions().projection(), admission.carriers());
        sources.putAll(exceptions.sources()); types.addAll(exceptions.types());
        var state = BridgeRootStateSources.generateOwnedCallbacks(admission, generation, batching);
        sources.putAll(state.sources()); types.addAll(state.types());
        var declarations = new BridgeJavaSources(sources, bindings, new ArrayList<>(types), ensure, List.of(), destructions);
        declarations = BridgeCallbackDispatchSources.add(declarations, admission.listeners(), generation, surface);
        return new Sources(BridgeCallbackBatchSources.addDispatch(declarations, batching, generation, surface), facades, calls, batching);
    }

    private static String unique(Set<String> occupied, String name) { return BridgePermanentJavaSources.unique(occupied, name); }
    private static String javaType(IrType type, BridgeExportSurface surface) { return BridgePermanentJavaSources.javaType(type, surface); }
}
