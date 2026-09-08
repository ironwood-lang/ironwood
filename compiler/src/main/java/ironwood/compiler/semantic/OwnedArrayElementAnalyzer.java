// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.*;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.ir.*;
import java.util.*;

/**
 * Proves the bounded creation-array cleanup idiom. A private array may own values
 * installed once from fresh allocations, and may be resized by a full shallow
 * copy into fresh storage. Ordinary arrays retain their shallow-free semantics.
 * This is not a proof for arbitrary container algorithms or borrowed elements.
 */
final class OwnedArrayElementAnalyzer {
    private OwnedArrayElementAnalyzer() {}

    static FieldSymbol fieldFor(TypeSymbol owner, ForStatement loop) {
        if (!(loop.initializer().orElse(null) instanceof LocalVariableDeclaration local)
                || local.type().kind() != TypeName.Kind.INT || local.isFinal()
                || !(local.initializer() instanceof IntegerLiteralExpression zero)
                || !zero.text().equals("0")
                || !(loop.condition().orElse(null) instanceof BinaryExpression condition)
                || condition.operator() != BinaryOperator.LESS
                || !named(condition.left(), local.name())
                || !(condition.right() instanceof FieldAccessExpression length)
                || !length.fieldName().equals("length")
                || loop.updates().size() != 1
                || !(loop.updates().getFirst() instanceof UpdateExpression update)
                || update.operator() != UpdateOperator.INCREMENT
                || !named(update.target(), local.name())
                || !(loop.body() instanceof Block body) || body.statements().size() != 1
                || !(body.statements().getFirst() instanceof FreeStatement free)
                || !(free.value() instanceof ArrayAccessExpression element)
                || !named(element.index(), local.name())) { return null; }
        String fieldName = fieldName(length.receiver());
        if (fieldName == null || fieldName.equals(local.name())
                || !fieldName.equals(fieldName(element.array()))) { return null; }
        FieldSymbol field = owner.declaredFields().get(fieldName);
        return field != null && !field.isStatic() && field.type().isArray()
                && field.type().elementType().isReference()
                && field.accessModifier() == AccessModifier.PRIVATE ? field : null;
    }

    static Set<FieldSymbol> fields(TypeSymbol owner) {
        Set<FieldSymbol> result = new LinkedHashSet<>();
        owner.destructor().ifPresent(destructor -> {
            for (Statement statement : destructor.body().orElseThrow().statements()) {
                if (statement instanceof ForStatement loop) {
                    FieldSymbol field = fieldFor(owner, loop);
                    if (field != null) { result.add(field); }
                }
            }
        });
        return result;
    }

    private static boolean named(Expression expression, String name) {
        return expression instanceof NameExpression reference && reference.name().equals(name);
    }

    private static String fieldName(Expression expression) {
        if (expression instanceof FieldAccessExpression field && field.receiver() instanceof ThisExpression) {
            return field.fieldName();
        }
        return null;
    }

    static void validate(Map<String, TypeSymbol> types, List<IrFunction> functions,
                         OwnedArrayFieldAnalyzer ownership, EscapeSummaryAnalyzer summaries,
                         List<Diagnostic> diagnostics) {
        for (TypeSymbol owner : types.values()) {
            for (FieldSymbol field : fields(owner)) {
                if (!ownership.isOwned(field)) { continue; }
                for (IrFunction function : functions) {
                    new Checker(owner, field, function, summaries, diagnostics).check();
                }
            }
        }
    }

    private static final class Checker {
        private final TypeSymbol owner;
        private final FieldSymbol field;
        private final IrFunction function;
        private final EscapeSummaryAnalyzer summaries;
        private final List<Diagnostic> diagnostics;
        private final List<IrInstruction> instructions = new ArrayList<>();
        private final Map<IrOperand, IrOperand> roots = new HashMap<>();
        private final Map<IrOperand, IrInstruction> definitions = new HashMap<>();
        private final Map<IrInstruction, String> blocks = new IdentityHashMap<>();
        private final Map<IrOperand, IrInstruction> fresh = new HashMap<>();
        private final Set<IrOperand> arrays = new HashSet<>();
        private final Map<String, Set<String>> successors = new LinkedHashMap<>();
        private final Map<String, Set<String>> dominators = new LinkedHashMap<>();
        private boolean failed;

        Checker(TypeSymbol owner, FieldSymbol field, IrFunction function,
                EscapeSummaryAnalyzer summaries, List<Diagnostic> diagnostics) {
            this.owner = owner;
            this.field = field;
            this.function = function;
            this.summaries = summaries;
            this.diagnostics = diagnostics;
        }

        void check() {
            for (IrBasicBlock block : function.blocks()) {
                for (IrInstruction instruction : block.instructions()) { add(instruction, block.label()); }
                if (block.terminator() instanceof IrInvokeTerminator invoke) { add(invoke.call(), block.label()); }
                successors.put(block.label(), targets(block.terminator()));
            }
            boolean changed;
            do {
                changed = false;
                for (IrInstruction instruction : instructions) {
                    if (instruction instanceof IrReferenceConversionInstruction conversion) {
                        IrOperand previous = roots.put(conversion.result(), root(conversion.value()));
                        changed |= !Objects.equals(previous, root(conversion.value()));
                    }
                    if (instruction instanceof IrPhiInstruction phi) {
                        if (phi.incoming().stream().anyMatch(incoming -> arrays.contains(root(incoming.value())))) {
                            changed |= arrays.add(phi.result());
                        }
                    }
                }
            } while (changed);
            if (arrays.isEmpty()) { return; }
            computeDominators();
            Map<IrOperand, IrArrayStoreInstruction> recorded = new HashMap<>();
            List<IrFieldStoreInstruction> replacements = instructions.stream()
                    .filter(IrFieldStoreInstruction.class::isInstance)
                    .map(IrFieldStoreInstruction.class::cast)
                    .filter(store -> store.field().equals(field.irField()))
                    .filter(store -> !(store.value() instanceof IrNull)).toList();
            for (IrInstruction instruction : instructions) {
                if (instruction instanceof IrArrayLoadInstruction load && isArray(load.array())) {
                    reject("creation-array elements may only be consumed by their destructor loop");
                } else if (instruction instanceof IrArrayStoreInstruction store && isArray(store.array())
                        && !(store.value() instanceof IrNull)) {
                    IrOperand value = root(store.value());
                    IrInstruction creation = fresh.get(value);
                    if (creation == null || recorded.putIfAbsent(value, store) != null
                            || canRepeatWithout(store, creation)) {
                        reject("each creation-array entry must receive a distinct fresh object exactly once");
                    }
                } else if (instruction instanceof IrSystemArrayCopyInstruction copy
                        && (isArray(copy.source()) || isArray(copy.destination()))) {
                    if (!validResize(copy, replacements)) {
                        reject("creation-array copying must preserve each element once in fresh replacement storage");
                    }
                }
                for (IrOperand argument : arguments(instruction)) {
                    if (isArray(argument)) { reject("creation-array storage cannot be passed to an arbitrary call"); }
                }
            }
            // A freshly recorded object cannot be published independently. The
            // bundled private pool factories may return recorded values to their
            // availability-storage algorithms; public checkout remains a dependent borrow.
            for (IrOperand value : recorded.keySet()) {
                for (IrInstruction instruction : instructions) {
                    if (instruction instanceof IrFieldStoreInstruction store && root(store.value()).equals(value)
                            || instruction instanceof IrStaticFieldStoreInstruction staticStore && root(staticStore.value()).equals(value)) {
                        reject("a creation-array object cannot also escape through a field");
                    }
                    if (instruction instanceof IrFreeInstruction free && root(free.allocation()).equals(value)) {
                        reject("a recorded object is reclaimed only by creation-array cleanup");
                    }
                    if (instruction instanceof IrArrayStoreInstruction store && root(store.value()).equals(value)
                            && !isArray(store.array())) {
                        reject("a fresh creation-array object cannot also be stored in another array");
                    }
                    List<IrOperand> arguments = arguments(instruction);
                    for (int index = 0; index < arguments.size(); index++) {
                        if (!root(arguments.get(index)).equals(value)) { continue; }
                        CallableSymbol target = instruction instanceof IrCallInstruction call
                                ? summaries.callable(call.targetLinkageName()) : null;
                        if (target == null || !target.isConstructor() || index != 0
                                || summaries.summary(target).thisEscapesWithoutReturn()) {
                            reject("a fresh creation-array object cannot escape through a call");
                        }
                    }
                }
                for (IrBasicBlock block : function.blocks()) {
                    if (block.terminator() instanceof IrReturnTerminator returned
                            && returned.value().isPresent() && root(returned.value().orElseThrow()).equals(value)
                            && !isPoolCreationHelper()) {
                        reject("returning a creation-array object requires a proved dependent-borrow contract");
                    }
                    if (block.terminator() instanceof IrThrowTerminator thrown && root(thrown.exception()).equals(value)) {
                        reject("a creation-array object cannot escape through throw");
                    }
                }
            }
        }

        private boolean isPoolCreationHelper() {
            CallableSymbol method = summaries.callable(function.linkageName());
            return method != null && method.accessModifier() == AccessModifier.PRIVATE
                    && PoolSemantics.isPool(function.ownerClass())
                    && (function.sourceName().equals("createObject")
                        || function.sourceName().equals("createArrayHolder"));
        }

        private void add(IrInstruction instruction, String block) {
            if (instruction instanceof IrCallInstruction call) {
                CallableSymbol target = summaries.callable(call.targetLinkageName());
                if (target != null && target.ownerType().equals("ironwood.lang.System")
                        && target.sourceName().equals("arraycopy") && target.isStatic()
                        && call.arguments().size() == 5) {
                    List<IrOperand> args = call.arguments();
                    instruction = new IrSystemArrayCopyInstruction(args.get(0), args.get(1),
                            args.get(2), args.get(3), args.get(4), call.sourceSpan());
                }
            }
            instructions.add(instruction);
            blocks.put(instruction, block);
            if (instruction instanceof IrFieldStoreInstruction store && store.field().equals(field.irField())
                    && !(store.value() instanceof IrNull)) {
                arrays.add(store.value());
            }
            if (instruction instanceof IrFieldLoadInstruction load && load.field().equals(field.irField())) {
                arrays.add(load.result());
                if (function.parameters().isEmpty() || !load.receiver().equals(function.parameters().getFirst().value())
                        || !function.ownerClass().equals(owner.name())
                            && function.kind() != IrCallableKind.CONSTRUCTOR_ROLLBACK) {
                    reject("creation-array storage must remain private to its owner");
                }
            }
            IrOperand result = result(instruction);
            if (result != null) { definitions.put(result, instruction); }
            if (instruction instanceof IrAllocateInstruction allocation) { fresh.put(allocation.result(), instruction); }
            else if (instruction instanceof IrArrayAllocateInstruction allocation) { fresh.put(allocation.result(), instruction); }
            else if (instruction instanceof IrInterfaceCallInstruction call
                    && call.interfaceName().equals("ironwood.pool.ObjectBuilder")
                    && call.slot().methodName().equals("newInstance")) {
                if (call.result().isPresent()) { fresh.put(call.result().orElseThrow(), instruction); }
            } else if (instruction instanceof IrCallInstruction call) {
                CallableSymbol target = summaries.callable(call.targetLinkageName());
                if (target != null && !target.isConstructor() && summaries.summary(target).returnsOwnedFresh()) {
                    if (call.result().isPresent()) { fresh.put(call.result().orElseThrow(), instruction); }
                }
            }
        }

        private boolean validResize(IrSystemArrayCopyInstruction copy, List<IrFieldStoreInstruction> replacements) {
            if (!zero(copy.sourcePosition()) || !zero(copy.destinationPosition())
                    || root(copy.source()).equals(root(copy.destination())) || replacements.size() != 1) { return false; }
            IrFieldStoreInstruction replacement = replacements.getFirst();
            IrInstruction allocation = definitions.get(root(replacement.value()));
            IrInstruction source = definitions.get(root(copy.source()));
            IrInstruction destination = definitions.get(root(copy.destination()));
            IrInstruction length = definitions.get(root(copy.length()));
            return allocation instanceof IrArrayAllocateInstruction
                    && source instanceof IrFieldLoadInstruction from && from.field().equals(field.irField())
                    && destination instanceof IrFieldLoadInstruction to && to.field().equals(field.irField())
                    && length instanceof IrArrayLengthInstruction size && root(size.array()).equals(root(copy.source()))
                    && precedes(source, replacement) && precedes(replacement, destination)
                    && precedes(destination, copy)
                    && instructions.stream().filter(IrSystemArrayCopyInstruction.class::isInstance)
                        .map(IrSystemArrayCopyInstruction.class::cast)
                        .filter(other -> isArray(other.source()) || isArray(other.destination())).count() == 1;
        }

        private boolean precedes(IrInstruction first, IrInstruction second) {
            String a = blocks.get(first), b = blocks.get(second);
            return a != null && b != null && (a.equals(b) ? instructions.indexOf(first) < instructions.indexOf(second)
                    : dominators.getOrDefault(b, Set.of()).contains(a));
        }

        private boolean canRepeatWithout(IrInstruction store, IrInstruction creation) {
            String storeBlock = blocks.get(store), creationBlock = blocks.get(creation);
            if (storeBlock.equals(creationBlock) && precedes(creation, store)) { return false; }
            Set<String> visited = new HashSet<>();
            Deque<String> pending = new ArrayDeque<>(successors.getOrDefault(storeBlock, Set.of()));
            while (!pending.isEmpty()) {
                String block = pending.removeFirst();
                if (block.equals(creationBlock) || !visited.add(block)) { continue; }
                if (block.equals(storeBlock)) { return true; }
                pending.addAll(successors.getOrDefault(block, Set.of()));
            }
            return false;
        }

        private void computeDominators() {
            if (function.blocks().isEmpty()) { return; }
            String entry = function.blocks().getFirst().label();
            for (String block : successors.keySet()) {
                dominators.put(block, new HashSet<>(block.equals(entry) ? Set.of(entry) : successors.keySet()));
            }
            boolean changed;
            do {
                changed = false;
                for (String block : successors.keySet()) {
                    if (block.equals(entry)) { continue; }
                    Set<String> next = null;
                    for (var predecessor : successors.entrySet()) {
                        if (!predecessor.getValue().contains(block)) { continue; }
                        if (next == null) { next = new HashSet<>(dominators.get(predecessor.getKey())); }
                        else { next.retainAll(dominators.get(predecessor.getKey())); }
                    }
                    if (next == null) { next = new HashSet<>(); }
                    next.add(block);
                    changed |= !next.equals(dominators.put(block, next));
                }
            } while (changed);
        }

        private IrOperand root(IrOperand value) {
            for (int count = roots.size(); count > 0 && roots.containsKey(value); count--) { value = roots.get(value); }
            return value;
        }
        private boolean isArray(IrOperand value) { return arrays.contains(root(value)); }
        private static boolean zero(IrOperand value) { return value instanceof IrConstant c && c.value().longValue() == 0; }
        private void reject(String reason) {
            if (!failed) {
                diagnostics.add(Diagnostic.error(owner.source(), field.declaration().nameSpan(),
                        "cannot prove owned elements of '" + field.declaration().name() + "' safe: " + reason));
                failed = true;
            }
        }
        private static IrOperand result(IrInstruction instruction) {
            if (instruction instanceof IrFieldLoadInstruction value) { return value.result(); }
            if (instruction instanceof IrArrayLengthInstruction value) { return value.result(); }
            if (instruction instanceof IrArrayAllocateInstruction value) { return value.result(); }
            if (instruction instanceof IrAllocateInstruction value) { return value.result(); }
            if (instruction instanceof IrReferenceConversionInstruction value) { return value.result(); }
            return null;
        }
        private static List<IrOperand> arguments(IrInstruction instruction) {
            if (instruction instanceof IrCallInstruction call) { return call.arguments(); }
            if (instruction instanceof IrInterfaceCallInstruction call) { return call.arguments(); }
            if (instruction instanceof IrVirtualCallInstruction call) { return call.arguments(); }
            return List.of();
        }
        private static Set<String> targets(IrTerminator terminator) {
            if (terminator instanceof IrSwitchTerminator selection) {
                Set<String> result = new HashSet<>();
                result.add(selection.defaultTarget());
                selection.cases().forEach(branch -> result.add(branch.target()));
                return result;
            }
            if (terminator instanceof IrJump jump) { return Set.of(jump.target()); }
            if (terminator instanceof IrBranch branch) { return new HashSet<>(List.of(branch.trueTarget(), branch.falseTarget())); }
            if (terminator instanceof IrInvokeTerminator invoke) { return Set.of(invoke.normalTarget(), invoke.unwindTarget()); }
            if (terminator instanceof IrThrowTerminator thrown) { return thrown.unwindTarget().map(Set::of).orElse(Set.of()); }
            return Set.of();
        }
    }
}
