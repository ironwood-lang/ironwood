// SPDX-License-Identifier: MIT OR Apache-2.0
// Original Ironwood LLVM 23 development pass. BDWGC never relocates objects.
#include "llvm/IR/Constants.h"
#include "llvm/IR/IntrinsicInst.h"
#include "llvm/IR/Module.h"
#include "llvm/IR/PassManager.h"
#include "llvm/Passes/PassBuilder.h"
#include "llvm/Plugins/PassPlugin.h"
#include "llvm/Support/ErrorHandling.h"
#include "llvm/Transforms/Scalar/RewriteStatepointsForGC.h"

using namespace llvm;

namespace {
struct NonmovingStatepoints : PassInfoMixin<NonmovingStatepoints> {
    PreservedAnalyses run(Module &module, ModuleAnalysisManager &analyses) {
        auto *abi = module.getGlobalVariable("iw_gc_root_abi");
        auto *version = abi && abi->hasInitializer() ? dyn_cast<ConstantInt>(abi->getInitializer()) : nullptr;
        if (!version || version->getZExtValue() != 2)
            report_fatal_error("Ironwood nonmoving statepoints require experimental root ABI 2");
        RewriteStatepointsForGC().run(module, analyses);
        // LLVM's rewrite attaches unwind relocates to its landingpad. Itanium
        // pads carry {exception,selector}, whereas gc.relocate requires token.
        // Keep all statepoint gc-live operands (and their exact stack maps).
        // In this nonmoving heap, exceptional relocations are identity values.
        // Resolve them through LLVM's operand graph before the verifier runs;
        // neither textual IR repair nor a disabled verifier is involved.
        SmallVector<GCRelocateInst *> exceptional;
        for (auto &function : module)
            for (auto &block : function)
                for (auto &instruction : block)
                    if (auto *relocate = dyn_cast<GCRelocateInst>(&instruction))
                        if (isa<LandingPadInst>(relocate->getArgOperand(0))) exceptional.push_back(relocate);
        for (auto *relocate : exceptional) {
            Value *original = relocate->getDerivedPtr();
            if (original->getType() != relocate->getType() || !original->getType()->isPointerTy()
                    || original->getType()->getPointerAddressSpace() != 1)
                report_fatal_error("unsupported Ironwood exceptional root relocation");
            relocate->replaceAllUsesWith(original);
            relocate->eraseFromParent();
        }
        return PreservedAnalyses::none();
    }
};
}

extern "C" LLVM_ATTRIBUTE_WEAK PassPluginLibraryInfo llvmGetPassPluginInfo() {
    return {LLVM_PLUGIN_API_VERSION, "IronwoodNonmovingStatepoints", LLVM_VERSION_STRING,
        [](PassBuilder &builder) {
            builder.registerPipelineParsingCallback([](StringRef name, ModulePassManager &passes,
                                                        ArrayRef<PassBuilder::PipelineElement>) {
                if (name != "ironwood-nonmoving-statepoints") return false;
                passes.addPass(NonmovingStatepoints());
                return true;
            });
        }};
}
