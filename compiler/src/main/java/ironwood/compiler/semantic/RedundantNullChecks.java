// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.IrAllocateInstruction;
import ironwood.compiler.ir.IrArrayAllocateInstruction;
import ironwood.compiler.ir.IrArrayTypeTestInstruction;
import ironwood.compiler.ir.IrBasicBlock;
import ironwood.compiler.ir.IrBinaryInstruction;
import ironwood.compiler.ir.IrBinaryOperator;
import ironwood.compiler.ir.IrBranch;
import ironwood.compiler.ir.IrFieldLoadInstruction;
import ironwood.compiler.ir.IrFieldStoreInstruction;
import ironwood.compiler.ir.IrInstanceOfInstruction;
import ironwood.compiler.ir.IrInstruction;
import ironwood.compiler.ir.IrInvokeTerminator;
import ironwood.compiler.ir.IrJump;
import ironwood.compiler.ir.IrNull;
import ironwood.compiler.ir.IrNullCheckInstruction;
import ironwood.compiler.ir.IrOperand;
import ironwood.compiler.ir.IrPhiIncoming;
import ironwood.compiler.ir.IrPhiInstruction;
import ironwood.compiler.ir.IrReferenceConversionInstruction;
import ironwood.compiler.ir.IrValueReference;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Null checks that the finished graph of a function proves redundant through merges
 * (D288).
 *
 * <p>Lowering omits a null check only where one null test dominates it (D287), so a
 * value merged at a join or a loop header kept its checks although every value it
 * merges is non-null. This pass solves the references known non-null at each block
 * entry over the whole graph, as the greatest fixed point of a must analysis: a block
 * knows what every reached predecessor knows on the edge into it, an edge adds what its
 * null test, instanceof test or null check proves, and a phi is non-null when each of
 * its incoming values is non-null on its edge. Loop headers start from everything and
 * keep only what every back edge preserves, so a value reassigned in the loop is not
 * covered. Allocation results and the proven references of lowering are never null.
 *
 * <p>Facts name SSA values, which never change, their reference conversions, and final
 * fields of one object, whatever reference type reads them. A store to a final field
 * ends the facts of that field and, when the stored value is known non-null, starts
 * the fact for the stored object. The entry may also start with facts that hold before
 * the function runs, such as the fields a destructor's object keeps until it frees
 * them (D289).
 *
 * <p>A null check whose reference is known non-null becomes a jump to its valid path,
 * and the failure blocks that only it reached are removed with their phi entries. The
 * pass runs after lowering, so only typed-IR consumers see the shorter graph.
 */
final class RedundantNullChecks {
    /** A final instance field of one object. */
    record FieldKey(IrOperand object, String owner, String name) {
        boolean storedBy(IrFieldStoreInstruction store) {
            return store.field().ownerClass().equals(owner) && store.field().name().equals(name);
        }
    }

    private final List<IrBasicBlock> blocks;
    private final NullGuards guards;
    private final Map<String, IrBasicBlock> byLabel = new HashMap<>();
    private final Map<String, Set<String>> predecessors = new HashMap<>();
    private final Set<IrOperand> nonNull = new HashSet<>();
    private final Map<IrOperand, IrOperand> conversions = new HashMap<>();
    private final Map<IrOperand, IrFieldLoadInstruction> finalLoads = new HashMap<>();
    /** Facts at block entries; a block not yet reached knows everything. */
    private final Map<String, Set<Object>> entries = new HashMap<>();
    private final Map<String, Set<Object>> exits = new HashMap<>();

    private RedundantNullChecks(List<IrBasicBlock> blocks, Set<IrOperand> proven, NullGuards guards) {
        this.blocks = blocks;
        this.guards = guards;
        nonNull.addAll(proven);
        for (IrBasicBlock block : blocks) {
            byLabel.put(block.label(), block);
            for (String successor : NullGuards.successors(block.terminator())) {
                predecessors.computeIfAbsent(successor, ignored -> new LinkedHashSet<>()).add(block.label());
            }
            for (IrInstruction instruction : block.instructions()) {
                note(instruction);
            }
            if (block.terminator() instanceof IrInvokeTerminator invoke) {
                note(invoke.call());
            }
        }
    }

    /** The blocks with every null check the graph proves redundant removed. */
    static List<IrBasicBlock> omit(List<IrBasicBlock> blocks, Set<IrOperand> proven, NullGuards guards) {
        if (blocks.stream().noneMatch(block -> nullCheck(block) != null)) {
            return blocks;
        }
        RedundantNullChecks pass = new RedundantNullChecks(blocks, proven, guards);
        pass.solve(Set.of());
        return pass.rewrite();
    }

    /** As {@link #omit(List, Set, NullGuards)}, from the finished IR alone and entry facts. */
    static List<IrBasicBlock> omit(List<IrBasicBlock> blocks, Set<IrOperand> proven,
                                   Set<FieldKey> entry) {
        if (blocks.stream().noneMatch(block -> nullCheck(block) != null)) {
            return blocks;
        }
        RedundantNullChecks pass = new RedundantNullChecks(blocks, proven, null);
        pass.solve(entry);
        return pass.rewrite();
    }

    /** The facts of the finished IR alone, for {@link #knownBefore} queries. */
    static RedundantNullChecks solved(List<IrBasicBlock> blocks, Set<IrOperand> proven) {
        RedundantNullChecks pass = new RedundantNullChecks(blocks, proven, null);
        if (!blocks.isEmpty()) {
            pass.solve(Set.of());
        }
        return pass;
    }

    /** Whether the value is known non-null before the instruction at the index of the block. */
    boolean knownBefore(String label, int index, IrOperand value) {
        Set<Object> entry = entries.get(label);
        return entry != null && known(value,
                after(byLabel.get(label).instructions().subList(0, index), entry));
    }

    /** Whether the two references name the same object through conversions. */
    boolean sameObject(IrOperand first, IrOperand second) {
        return root(first).equals(root(second));
    }

    private void note(IrInstruction instruction) {
        switch (instruction) {
            case IrAllocateInstruction allocation -> nonNull.add(allocation.result());
            case IrArrayAllocateInstruction allocation -> nonNull.add(allocation.result());
            case IrReferenceConversionInstruction conversion ->
                    conversions.put(conversion.result(), conversion.value());
            case IrFieldLoadInstruction load when load.field().isFinal()
                    && load.result().type().isReference() -> finalLoads.put(load.result(), load);
            default -> { }
        }
    }

    private void solve(Set<FieldKey> entryFacts) {
        String entry = blocks.getFirst().label();
        entries.put(entry, new HashSet<>(entryFacts));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (IrBasicBlock block : blocks) {
                if (block.label().equals(entry)) {
                    continue;
                }
                Set<Object> facts = meet(block);
                if (facts != null && !facts.equals(entries.get(block.label()))) {
                    entries.put(block.label(), facts);
                    exits.remove(block.label());
                    changed = true;
                }
            }
        }
    }

    /** What every reached predecessor knows on its edge into the block, with its phis. */
    private Set<Object> meet(IrBasicBlock block) {
        Set<Object> result = null;
        for (String predecessor : predecessors.getOrDefault(block.label(), Set.of())) {
            Set<Object> edge = edge(predecessor, block.label());
            if (edge == null) {
                continue;
            }
            if (result == null) {
                result = new HashSet<>(edge);
            } else {
                result.retainAll(edge);
            }
        }
        if (result == null) {
            return null;
        }
        for (IrInstruction instruction : block.instructions()) {
            if (instruction instanceof IrPhiInstruction phi && phi.result().type().isReference()
                    && phi.incoming().stream().allMatch(incoming -> {
                        Set<Object> edge = edge(incoming.predecessor(), block.label());
                        return edge == null || known(incoming.value(), edge);
                    })) {
                result.add(phi.result());
            }
        }
        return result;
    }

    /** What is known on the edge, or null while its source is not reached. */
    private Set<Object> edge(String from, String to) {
        Set<Object> exit = exit(from);
        if (exit == null) {
            return null;
        }
        Set<Object> result = new HashSet<>(exit);
        IrBasicBlock source = byLabel.get(from);
        List<Object> proven = new ArrayList<>();
        if (guards != null) {
            for (Object fact : guards.edgeFacts(from, to)) {
                proven.add(fact instanceof NullGuards.FinalField field
                        ? new FieldKey(root(field.receiver()), field.owner(), field.name()) : fact);
            }
        }
        if (source.terminator() instanceof IrBranch branch
                && !branch.trueTarget().equals(branch.falseTarget())) {
            IrOperand tested = tested(source, branch, to);
            if (tested != null) {
                proven.addAll(facts(tested));
            }
        }
        for (Object fact : proven) {
            // A load of the field before a store in the same block saw the old value.
            if (!(fact instanceof FieldKey field) || source.instructions().stream()
                    .noneMatch(instruction -> instruction instanceof IrFieldStoreInstruction store
                            && field.storedBy(store))) {
                result.add(fact);
            }
        }
        return result;
    }

    /** The reference that the branch ending {@code block} proves non-null toward {@code to}. */
    private static IrOperand tested(IrBasicBlock block, IrBranch branch, String to) {
        boolean whenTrue = branch.trueTarget().equals(to);
        for (IrInstruction instruction : block.instructions()) {
            switch (instruction) {
                case IrNullCheckInstruction check when check.result().equals(branch.condition()) -> {
                    return whenTrue ? check.receiver() : null;
                }
                case IrInstanceOfInstruction test when test.result().equals(branch.condition()) -> {
                    return whenTrue ? test.value() : null;
                }
                case IrArrayTypeTestInstruction test when test.result().equals(branch.condition()) -> {
                    return whenTrue ? test.value() : null;
                }
                case IrBinaryInstruction compare when compare.result().equals(branch.condition())
                        && (compare.operator() == IrBinaryOperator.EQUAL
                        || compare.operator() == IrBinaryOperator.NOT_EQUAL) -> {
                    IrOperand other = compare.right() instanceof IrNull ? compare.left()
                            : compare.left() instanceof IrNull ? compare.right() : null;
                    boolean nonNull = whenTrue == (compare.operator() == IrBinaryOperator.NOT_EQUAL);
                    return nonNull ? other : null;
                }
                default -> { }
            }
        }
        return null;
    }

    /** What is known at the end of the block, or null while it is not reached. */
    private Set<Object> exit(String label) {
        Set<Object> entry = entries.get(label);
        if (entry == null) {
            return null;
        }
        return exits.computeIfAbsent(label, ignored -> after(byLabel.get(label).instructions(), entry));
    }

    /**
     * The facts after the instructions run: a store to a final field ends that field's
     * facts and, when its value is known non-null, starts the fact for its object.
     */
    private Set<Object> after(List<IrInstruction> instructions, Set<Object> facts) {
        Set<Object> result = new HashSet<>(facts);
        for (IrInstruction instruction : instructions) {
            if (instruction instanceof IrFieldStoreInstruction store) {
                boolean stored = store.field().isFinal() && known(store.value(), result);
                result.removeIf(fact -> fact instanceof FieldKey field && field.storedBy(store));
                if (stored) {
                    result.add(new FieldKey(root(store.receiver()), store.field().ownerClass(),
                            store.field().name()));
                }
            }
        }
        return result;
    }

    /** Whether the facts prove the value non-null. */
    private boolean known(IrOperand value, Set<Object> facts) {
        for (Object fact : facts(value)) {
            if (facts.contains(fact) || nonNull.contains(fact)) {
                return true;
            }
        }
        return false;
    }

    /** The value, the references it was converted from, and the final fields they read. */
    private List<Object> facts(IrOperand value) {
        List<Object> result = new ArrayList<>();
        Set<IrOperand> seen = new HashSet<>();
        for (IrOperand current = value; current instanceof IrValueReference && seen.add(current);
             current = conversions.get(current)) {
            result.add(current);
            IrFieldLoadInstruction load = finalLoads.get(current);
            if (load != null) {
                result.add(new FieldKey(root(load.receiver()), load.field().ownerClass(),
                        load.field().name()));
            }
        }
        return result;
    }

    /** The reference that conversions start from, which names the same object. */
    private IrOperand root(IrOperand value) {
        Set<IrOperand> seen = new HashSet<>();
        IrOperand current = value;
        while (conversions.containsKey(current) && seen.add(current)) {
            current = conversions.get(current);
        }
        return current;
    }

    /** The null check ending the block before a branch on its result, or null. */
    private static IrNullCheckInstruction nullCheck(IrBasicBlock block) {
        if (block.instructions().isEmpty()
                || !(block.instructions().getLast() instanceof IrNullCheckInstruction check)
                || !(block.terminator() instanceof IrBranch branch)
                || !branch.condition().equals(check.result())
                || branch.trueTarget().equals(branch.falseTarget())) {
            return null;
        }
        return check;
    }

    private List<IrBasicBlock> rewrite() {
        List<IrBasicBlock> rewritten = new ArrayList<>();
        boolean omitted = false;
        for (IrBasicBlock block : blocks) {
            IrNullCheckInstruction check = nullCheck(block);
            Set<Object> entry = entries.get(block.label());
            if (check != null && entry != null && known(check.receiver(),
                    after(block.instructions(), entry))) {
                IrBranch branch = (IrBranch) block.terminator();
                rewritten.add(new IrBasicBlock(block.label(),
                        block.instructions().subList(0, block.instructions().size() - 1),
                        new IrJump(branch.trueTarget(), branch.sourceSpan()), block.sourceSpan()));
                omitted = true;
            } else {
                rewritten.add(block);
            }
        }
        if (!omitted) {
            return blocks;
        }
        Set<String> removed = reachable(blocks);
        removed.removeAll(reachable(rewritten));
        List<IrBasicBlock> result = new ArrayList<>();
        for (IrBasicBlock block : rewritten) {
            if (removed.contains(block.label())) {
                continue;
            }
            List<IrInstruction> instructions = block.instructions().stream()
                    .map(instruction -> instruction instanceof IrPhiInstruction phi
                            ? withoutRemoved(phi, removed) : instruction)
                    .toList();
            result.add(instructions.equals(block.instructions()) ? block
                    : new IrBasicBlock(block.label(), instructions, block.terminator(),
                    block.sourceSpan()));
        }
        return result;
    }

    private static IrInstruction withoutRemoved(IrPhiInstruction phi, Set<String> removed) {
        List<IrPhiIncoming> incoming = phi.incoming().stream()
                .filter(value -> !removed.contains(value.predecessor())).toList();
        return incoming.size() == phi.incoming().size() ? phi
                : new IrPhiInstruction(phi.result(), incoming, phi.sourceSpan());
    }

    private static Set<String> reachable(List<IrBasicBlock> blocks) {
        Map<String, IrBasicBlock> byLabel = new HashMap<>();
        blocks.forEach(block -> byLabel.put(block.label(), block));
        Set<String> seen = new LinkedHashSet<>(List.of(blocks.getFirst().label()));
        ArrayDeque<String> work = new ArrayDeque<>(seen);
        while (!work.isEmpty()) {
            IrBasicBlock block = byLabel.get(work.removeFirst());
            if (block == null) {
                continue;
            }
            for (String successor : NullGuards.successors(block.terminator())) {
                if (seen.add(successor)) {
                    work.addLast(successor);
                }
            }
        }
        return seen;
    }
}
