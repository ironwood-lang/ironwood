// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeApiFacts;
import ironwood.compiler.source.SourceFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Compiler-owned proxy declarations, rebound before mandatory source safety analysis. */
public final class BridgeListenerProxies {
    public record Proxy(String binaryName, BridgeApiFacts.Type listener, SourceFile source,
                        List<BridgeApiFacts.Callable> methods) {
        public Proxy { methods = List.copyOf(methods); }
    }

    private final List<Proxy> proxies;
    private final Map<java.nio.file.Path, String> sourceInputs;

    private BridgeListenerProxies(List<Proxy> proxies, Map<java.nio.file.Path, String> sourceInputs) {
        this.proxies = List.copyOf(proxies);
        this.sourceInputs = Map.copyOf(sourceInputs);
    }

    public List<Proxy> proxies() { return proxies; }
    public List<SourceFile> sources() { return proxies.stream().map(Proxy::source).toList(); }

    /** Rebind generated ownership operations only to the complete original input inventory. */
    public void validateArtifact(CompilationArtifact artifact) {
        if (!artifact.valid() || artifact.bridgeApiFacts().isEmpty()
                || !artifact.bridgeApiFacts().orElseThrow().matches(artifact.program().orElseThrow())) {
            throw new IllegalArgumentException("listener ownership requires matching semantic API facts");
        }
        var facts = artifact.bridgeApiFacts().orElseThrow();
        Map<java.nio.file.Path, String> actual = new LinkedHashMap<>();
        facts.types().values().forEach(type -> actual.put(type.source().path(), type.source().content()));
        Map<java.nio.file.Path, String> expected = new LinkedHashMap<>(sourceInputs);
        proxies.forEach(proxy -> expected.put(proxy.source().path(), proxy.source().content()));
        if (!actual.equals(expected)) throw new IllegalArgumentException("listener ownership source inventory changed");
        var program = artifact.program().orElseThrow();
        for (var proxy : proxies) {
            var owner = program.classes().stream().filter(type -> type.name().equals(proxy.binaryName())).findFirst().orElseThrow();
            for (var function : program.functions()) {
                if (function.ownerClass().equals(proxy.binaryName()) && function.kind() == IrCallableKind.METHOD
                        && !function.equals(lower(function, owner, proxy.source()))) {
                    throw new IllegalArgumentException("listener ownership requires exact typed proxy bodies");
                }
            }
        }
    }

    /** Analysis only. Interface discovery never grants JNI or lifetime admission. */
    public static BridgeListenerProxies discover(CompilationArtifact artifact, List<String> exports) {
        if (!artifact.valid() || artifact.bridgeApiFacts().isEmpty()
                || !artifact.bridgeApiFacts().orElseThrow().matches(artifact.program().orElseThrow())) {
            throw new IllegalArgumentException("listener proxies require matching semantic API facts");
        }
        var facts = artifact.bridgeApiFacts().orElseThrow();
        var packages = Set.copyOf(exports);
        List<Proxy> result = new ArrayList<>();
        for (var type : facts.types().values()) {
            if (!packages.contains(type.packageName()) || !type.accessible()
                    || type.kind() != BridgeApiFacts.Kind.INTERFACE) continue;
            if (type.generic() || !type.fields().isEmpty()) {
                throw new IllegalArgumentException("listener interface requires nongeneric methods and no public fields: " + type.sourceName());
            }
            var methods = type.callables().stream().filter(method -> !method.owner().equals("ironwood.lang.Object")).toList();
            for (var method : methods) {
                if (method.isStatic() || method.generic() || method.target().isPresent() || method.dispatchSlot().isEmpty()
                        || !transportType(method.result()) || method.parameters().stream().anyMatch(parameter ->
                        parameter.equals(IrType.VOID) || !transportType(parameter))) {
                    throw new IllegalArgumentException("unsupported listener method: " + type.sourceName() + "." + method.name());
                }
            }
            if (methods.isEmpty()) throw new IllegalArgumentException("listener interface has no callback methods: " + type.sourceName());
            String simple = "_IronwoodListenerProxy_" + BridgeGeneration.bytesDigest(
                    type.binaryName().getBytes(StandardCharsets.UTF_8)).substring(0, 24);
            String binary = type.packageName().isEmpty() ? simple : type.packageName() + "." + simple;
            if (facts.types().containsKey(binary)) throw new IllegalArgumentException("reserved listener proxy type collision: " + binary);
            StringBuilder text = new StringBuilder("// SPDX-License-Identifier: MIT OR Apache-2.0\n");
            if (!type.packageName().isEmpty()) text.append("package ").append(type.packageName()).append(";\n");
            text.append("final class ").append(simple).append(" implements ").append(type.sourceName()).append(" {\n")
                    .append("    private final long javaHandle;\n")
                    .append("    public ").append(simple).append("(long handle) { javaHandle = handle; }\n");
            for (var method : methods) {
                text.append("    @Override public ").append(sourceType(method.result(), facts)).append(' ').append(method.name()).append('(');
                for (int index = 0; index < method.parameters().size(); index++) {
                    if (index > 0) text.append(", ");
                    text.append(sourceType(method.parameters().get(index), facts)).append(" argument").append(index);
                }
                // These parsed bodies only establish ordinary declarations. The exact
                // bound functions are replaced in every provisional/final lowering.
                text.append(") { ");
                if (!method.result().equals(IrType.VOID)) {
                    text.append("return ").append(method.result().isReference() ? "null"
                            : method.result().equals(IrType.I1) ? "false" : "(" + sourceType(method.result(), facts) + ") 0").append(';');
                }
                text.append(" }\n");
            }
            text.append("}\n");
            result.add(new Proxy(binary, type, SourceFile.of("<java-bridge>/" + binary.replace('.', '/') + ".iron", text.toString()), methods));
        }
        Map<java.nio.file.Path, String> inputs = new LinkedHashMap<>();
        facts.types().values().forEach(type -> inputs.put(type.source().path(), type.source().content()));
        return new BridgeListenerProxies(result, inputs);
    }

    public void validateUnits(List<CompilationUnit> units) {
        Map<java.nio.file.Path, String> actual = new LinkedHashMap<>();
        for (var unit : units) {
            if (actual.putIfAbsent(unit.source().path(), unit.source().content()) != null) {
                throw new IllegalArgumentException("duplicate listener analysis source path");
            }
        }
        for (var entry : sourceInputs.entrySet()) {
            if (!entry.getValue().equals(actual.remove(entry.getKey()))) {
                throw new IllegalArgumentException("listener analysis source changed: " + entry.getKey());
            }
        }
        for (var proxy : proxies) {
            if (!proxy.source().content().equals(actual.remove(proxy.source().path()))) {
                throw new IllegalArgumentException("generated listener proxy changed: " + proxy.binaryName());
            }
        }
        if (!actual.isEmpty()) throw new IllegalArgumentException("listener analysis contains unbound source inputs");
    }

    public IrFunction lower(IrFunction function, IrClass owner, SourceFile source) {
        var selected = proxies.stream().filter(proxy -> proxy.binaryName().equals(function.ownerClass())).findFirst();
        if (selected.isEmpty() || function.kind() != IrCallableKind.METHOD) return function;
        var proxy = selected.orElseThrow();
        if (!source.path().equals(proxy.source().path()) || !source.content().equals(proxy.source().content())) {
            throw new IllegalArgumentException("foreign body requires its exact generated proxy source");
        }
        var arguments = function.parameters().stream().skip(1).map(parameter -> parameter.value().type()).toList();
        var method = proxy.methods().stream().filter(candidate -> candidate.name().equals(function.sourceName())
                && candidate.parameters().equals(arguments) && candidate.result().equals(function.returnType())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unbound listener proxy method"));
        var field = owner.fields().stream().filter(candidate -> candidate.ownerClass().equals(owner.name())
                && candidate.name().equals("javaHandle") && candidate.type().equals(IrType.I64)).findFirst().orElseThrow();
        var span = function.sourceSpan();
        var handle = new IrValueReference(function.parameters().size(), IrType.I64, span);
        var result = method.result().equals(IrType.VOID) ? Optional.<IrValueReference>empty()
                : Optional.of(new IrValueReference(function.parameters().size() + 1, method.result(), span));
        List<IrOperand> foreignArguments = new ArrayList<>();
        foreignArguments.add(handle);
        function.parameters().stream().skip(1).map(IrParameter::value).forEach(foreignArguments::add);
        String symbol = "ironwood_bridge_callback_" + BridgeGeneration.bytesDigest((proxy.listener().binaryName()
                + ":" + method.name() + ":" + arguments + ":" + method.result()).getBytes(StandardCharsets.UTF_8));
        var body = List.<IrInstruction>of(new IrFieldLoadInstruction(handle, function.parameters().getFirst().value(), field, span),
                new IrForeignCallInstruction(result, symbol, method.result(), foreignArguments, span));
        return new IrFunction(function.ownerClass(), function.sourceName(), function.linkageName(), function.returnType(),
                function.parameters(), List.of(new IrBasicBlock("entry", body,
                new IrReturnTerminator(result.map(value -> (IrOperand) value), span), span)), span,
                function.sourceFileName(), function.kind());
    }

    private static boolean transportType(IrType type) {
        return BridgeAbi.carrierFor(type).isPresent();
    }

    private static String sourceType(IrType type, BridgeApiFacts facts) {
        if (!type.isReference()) return type.displayName();
        var declaration = facts.types().get(type.referenceName());
        if (declaration == null) throw new IllegalArgumentException("unresolved listener signature type: " + type);
        return declaration.sourceName();
    }
}
