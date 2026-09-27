// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeApiFacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Exact constant-specific targets, without granting invocation or lifetime permission. */
public final class BridgeEnumDispatch {
    public record Target(BridgeEnumConstants.Constant constant, BridgeCallableId callable, boolean javaIdentity) {}

    private final IrProgram program;
    private final IrType type;
    private final BridgeApiFacts.Callable method;
    private final List<Target> targets;

    private BridgeEnumDispatch(IrProgram program, IrType type, BridgeApiFacts.Callable method, List<Target> targets) {
        this.program = program;
        this.type = type;
        this.method = method;
        this.targets = List.copyOf(targets);
    }

    public List<Target> targets() { return targets; }
    public boolean matches(IrProgram candidate, IrType expectedType, BridgeApiFacts.Callable expectedMethod) {
        return program.equals(candidate) && type.equals(expectedType) && method.equals(expectedMethod);
    }

    public static BridgeEnumDispatch prove(CompilationArtifact artifact, IrType type, BridgeApiFacts.Callable method,
            BridgeEnumConstants constants) {
        if (!artifact.valid() || artifact.bridgeApiFacts().isEmpty()) {
            throw new IllegalArgumentException("enum dispatch requires final semantic API facts");
        }
        var program = artifact.program().orElseThrow();
        var facts = artifact.bridgeApiFacts().orElseThrow();
        var declaration = type.isNominalReference() ? facts.types().get(type.referenceName()) : null;
        if (!facts.matches(program) || !constants.matches(program, constants.constants().keySet())
                || !constants.constants().containsKey(type) || declaration == null || declaration.kind() != BridgeApiFacts.Kind.ENUM
                || !declaration.callables().contains(method) || method.isStatic() || method.kind() != IrCallableKind.METHOD
                || method.generic() || method.dispatchSlot().isEmpty()) {
            throw new IllegalArgumentException("enum dispatch requires matching named constants and an exact instance method");
        }
        var slot = method.dispatchSlot().orElseThrow();
        List<Target> targets = new ArrayList<>();
        for (var constant : constants.constants().get(type)) {
            var singleton = (IrEnumConstant) constant.field().initialValue();
            var classes = program.classes().stream().filter(candidate -> candidate.name().equals(singleton.storageType().referenceName())).toList();
            if (classes.size() != 1) throw new IllegalArgumentException("missing enum constant dynamic type");
            var entries = classes.getFirst().dispatchEntries().stream().filter(entry -> entry.slot().equals(slot)).toList();
            if (entries.size() != 1) throw new IllegalArgumentException("missing constant-specific enum dispatch: " + singleton.constantName());
            var functions = program.functions().stream().filter(function -> function.linkageName().equals(entries.getFirst().targetLinkageName())).toList();
            if (functions.size() != 1) throw new IllegalArgumentException("missing constant-specific enum body: " + singleton.constantName());
            var callable = BridgeCallableId.of(functions.getFirst());
            boolean javaIdentity = javaIdentity(facts, method, callable);
            if (callable.kind() != IrCallableKind.METHOD || !callable.result().equals(method.result())
                    || callable.parameters().size() != method.parameters().size() + 1
                    || !javaIdentity && !callable.parameters().subList(1, callable.parameters().size()).equals(method.parameters())) {
                throw new IllegalArgumentException("constant-specific enum body has a different resolved signature");
            }
            var receiver = callable.parameters().getFirst();
            var owner = program.classes().stream().filter(candidate -> receiver.isNominalReference()
                    && candidate.name().equals(receiver.referenceName())).findFirst();
            if (owner.isEmpty() || !classes.getFirst().typeMembership().contains(owner.orElseThrow().typeId())) {
                throw new IllegalArgumentException("enum constant cannot supply the resolved body receiver");
            }
            targets.add(new Target(constant, callable, javaIdentity));
        }
        return new BridgeEnumDispatch(program, type, method, targets);
    }

    private static boolean javaIdentity(BridgeApiFacts facts, BridgeApiFacts.Callable method, BridgeCallableId target) {
        if (Set.of("ironwood.lang.Object", "ironwood.lang.Enum").contains(target.owner())) {
            return Set.of("equals", "hashCode", "toString", "compareTo").contains(method.name());
        }
        var owner = facts.types().get(target.owner());
        return owner != null && owner.kind() == BridgeApiFacts.Kind.ENUM
                && Set.of("name", "ordinal", "equals", "hashCode", "toString", "compareTo").contains(method.name())
                && owner.callables().stream().anyMatch(candidate -> candidate.synthetic()
                        && candidate.target().filter(target::equals).isPresent());
    }
}
