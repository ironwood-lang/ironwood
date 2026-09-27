// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

final class BridgeRootEntryTests {
    private BridgeRootEntryTests() {}

    static final Set<String> TYPES = Set.of("rootfixture.Item", "rootfixture.Holder");
    static final Set<String> METHODS = Set.of("set", "two", "clear", "setThenFail", "other", "both");
    static final String SOURCE = BridgeRootRetentionTests.SOURCE.replace("Holder(Item item) { first = item; }", """
            private int[] storage;
            static int destroyed;
            Holder(Holder other, Item item, boolean fail) {
                first = item;
                if (other != null) helper(other, item);
                storage = new int[2];
                if (fail) throw null;
            }
            destructor { free storage; first = null; second = null; destroyed++; }
            void both(Holder other, Item a, Item b) { first = a; other.first = b; }
            """);

    static BridgeEntryModule module() {
        var artifact = BridgeRootRetentionTests.artifact(SOURCE, UnfreedMode.OFF);
        return BridgeEntryModule.rootObjects(artifact, BridgeRootRetentionTests.roots(artifact, METHODS, TYPES));
    }

    static void lowering() throws Exception {
        var module = module();
        check(module.rootRetention().isPresent() && module.destructions().size() == 2, "missing proved root metadata");
        for (var destruction : module.destructions()) {
            check(destruction.contract().unpublishedConstructor().isEmpty(), "normal free used rollback proof");
            check(module.entrySymbols().contains(destruction.function().linkageName()), "missing destruction entry root");
        }
        var constructor = module.entries().stream().filter(entry -> entry.root().callable().owner().equals("rootfixture.Holder")
                && entry.root().callable().kind() == IrCallableKind.CONSTRUCTOR).findFirst().orElseThrow();
        check(constructor.function().parameters().size() == 4, "constructor receiver leaked into foreign ABI");
        var rollback = constructor.function().blocks().stream().filter(block -> block.label().equals("failure.constructed"))
                .findFirst().orElseThrow();
        check(rollback.instructions().get(1) instanceof IrExceptionCaughtInstruction
                && rollback.instructions().getLast() instanceof IrRollbackInstruction, "missing protected rollback");
        var receiver = ((IrRollbackInstruction) rollback.instructions().getLast()).allocation();
        check(constructor.function().blocks().stream().filter(block -> block.label().startsWith("failure."))
                .flatMap(block -> block.instructions().stream()).filter(IrFieldLoadInstruction.class::isInstance)
                .map(IrFieldLoadInstruction.class::cast).noneMatch(load -> load.receiver().equals(receiver)),
                "failure snapshot dereferences an unallocated/freed constructor receiver");
        check(constructor.function().blocks().stream().filter(block -> block.label().startsWith("failure.constructed.slots"))
                .flatMap(block -> block.instructions().stream()).anyMatch(IrBridgeSlotStoreInstruction.class::isInstance),
                "constructor failure lost existing argument-root mutation");
        var renamer = new IrCfgRenamer(value -> new IrValueReference(value.id() + 1000, value.type(), value.sourceSpan()), label -> "x." + label);
        for (var entry : module.entries()) {
            entry.function().blocks().forEach(renamer::block);
            for (var block : entry.function().blocks()) {
                if (block.terminator() instanceof IrInvokeTerminator invoke
                        && invoke.call() instanceof IrBridgeFailureSnapshotInstruction) {
                    check(block.label().endsWith(".snapshot"), "failure extraction moved before final slot payload");
                }
            }
        }
        var discovery = LlvmToolchain.discover(null);
        check(discovery.successful(), discovery.error());
        Path directory = Path.of("workspace/java-bridge/evidence/p0b/root-entries").toAbsolutePath();
        Files.createDirectories(directory);
        directory = Files.createTempDirectory(directory, "run-");
        Path llvm = directory.resolve("program.ll");
        Files.writeString(llvm, new LlvmEmitter().emit(module));
        Files.writeString(directory.resolve("Roots.iron"), SOURCE);
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) {
            var linked = new NativeBackend().linkShared(discovery.toolchain().orElseThrow(), llvm,
                    directory.resolve("roots-" + level + (System.getProperty("os.name").startsWith("Mac") ? ".dylib" : ".so")), level, List.of());
            Files.writeString(directory.resolve("link-" + level + ".log"), linked.output());
            check(linked.success(), linked.output());
        }
        System.out.println("bridge root entry lowering evidence: " + directory);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
