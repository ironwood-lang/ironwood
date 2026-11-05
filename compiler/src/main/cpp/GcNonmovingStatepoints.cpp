// SPDX-License-Identifier: MIT OR Apache-2.0
// Original Ironwood LLVM 23 statepoint pass. BDWGC never relocates objects.
#include "llvm/ADT/MapVector.h"
#include "llvm/ADT/SmallPtrSet.h"
#include "llvm/IR/Constants.h"
#include "llvm/IR/IRBuilder.h"
#include "llvm/IR/ValueHandle.h"
#include "llvm/IR/IntrinsicInst.h"
#include "llvm/IR/Statepoint.h"
#include "llvm/IR/Module.h"
#include "llvm/IR/PassManager.h"
#include "llvm/Passes/PassBuilder.h"
#include "llvm/Plugins/PassPlugin.h"
#include "llvm/Support/ErrorHandling.h"
#include "llvm/Transforms/Scalar/RewriteStatepointsForGC.h"
#include "llvm/Transforms/Utils/ModuleUtils.h"
#include "llvm/TargetParser/Triple.h"

using namespace llvm;

namespace {
struct NonmovingStatepoints : PassInfoMixin<NonmovingStatepoints> {
    PreservedAnalyses run(Module &module, ModuleAnalysisManager &analyses) {
        auto *abi = module.getGlobalVariable("iw_gc_root_abi");
        auto *version = abi && abi->hasInitializer() ? dyn_cast<ConstantInt>(abi->getInitializer()) : nullptr;
        if (!version || version->getZExtValue() != 2)
            report_fatal_error("Ironwood nonmoving statepoints require root ABI 2");
        RewriteStatepointsForGC().run(module, analyses);
        // Itanium landingpads carry {exception,selector}, not the token type
        // required by gc.relocate. Identity replacement is valid in this heap,
        // but erasing an exceptional-only relocate also erases LLVM's only
        // base/derived map-pair user. Preserve that proven base in a transient
        // native stack region at the associated collecting invoke.
        SmallVector<GCRelocateInst *> exceptional;
        MapVector<CallBase *, SmallVector<WeakTrackingVH>> bases;
        for (auto &function : module)
            for (auto &block : function)
                for (auto &instruction : block)
                    if (auto *relocate = dyn_cast<GCRelocateInst>(&instruction))
                        if (isa<LandingPadInst>(relocate->getArgOperand(0))) {
                            auto *point = dyn_cast<InvokeInst>(const_cast<Value *>(relocate->getStatepoint()));
                            Value *base = relocate->getBasePtr();
                            if (!point || !base->getType()->isPointerTy()
                                    || base->getType()->getPointerAddressSpace() != 1)
                                report_fatal_error("unsupported Ironwood exceptional root base");
                            bases[point].push_back(base);
                            exceptional.push_back(relocate);
                        }
        for (auto *relocate : exceptional) {
            Value *original = relocate->getDerivedPtr();
            if (original->getType() != relocate->getType() || !original->getType()->isPointerTy()
                    || original->getType()->getPointerAddressSpace() != 1)
                report_fatal_error("unsupported Ironwood exceptional root relocation");
            relocate->replaceAllUsesWith(original);
            relocate->eraseFromParent();
        }
        for (auto &entry : bases) {
            CallBase *point = entry.first;
            SmallPtrSet<Value *, 8> seen;
            SmallVector<Value *> slots;
            IRBuilder<> before(point);
            IRBuilder<> start(&*point->getFunction()->getEntryBlock().getFirstInsertionPt());
            for (auto &tracked : entry.second) {
                Value *base = tracked;
                if (!seen.insert(base).second) continue;
                auto *slot = start.CreateAlloca(base->getType(), nullptr, "iw.gc.unwind.root");
                slot->setAlignment(Align(8));
                slot->setMetadata("iw.gc.statepoint.spill", MDNode::get(module.getContext(), {}));
                before.CreateStore(base, slot)->setAlignment(Align(8));
                slots.push_back(slot);
            }
            SmallVector<OperandBundleDef> bundles;
            point->getOperandBundlesAsDefs(bundles);
            SmallVector<Value *> region;
            bool found = false;
            for (auto &bundle : bundles) if (bundle.getTag() == "deopt") {
                found = true;
                region.append(bundle.inputs().begin(), bundle.inputs().end());
                auto *magic = region.size() >= 2 ? dyn_cast<ConstantInt>(region[0]) : nullptr;
                auto *count = region.size() >= 2 ? dyn_cast<ConstantInt>(region[1]) : nullptr;
                if (!magic || magic->getZExtValue() != 1230455603 || !count
                        || count->getZExtValue() != region.size() - 2)
                    report_fatal_error("invalid Ironwood stack-region bundle");
                region[1] = before.getInt64(region.size() - 2 + slots.size());
                region.append(slots);
                bundle = OperandBundleDef("deopt", region);
            }
            if (!found) {
                region.push_back(before.getInt64(1230455603));
                region.push_back(before.getInt64(slots.size()));
                region.append(slots);
                bundles.emplace_back("deopt", region);
            }
            auto *replacement = CallBase::Create(point, bundles, point->getIterator());
            replacement->takeName(point);
            point->replaceAllUsesWith(replacement);
            point->eraseFromParent();
        }
        // A closed-world image with no statepoints still carries the exact
        // versioned empty inventory. This is ABI metadata, not a collecting
        // anchor or a fake call. An attempted unmapped collection still refuses
        // before marking, just as any other missing statepoint does.
        bool hasStatepoint = false;
        for (auto &function : module)
            for (auto &block : function)
                for (auto &instruction : block)
                    hasStatepoint |= isa<GCStatepointInst>(instruction);
        if (!hasStatepoint) {
            uint8_t bytes[16] = {3};
            auto *map = new GlobalVariable(module, ArrayType::get(Type::getInt8Ty(module.getContext()), 16),
                    true, GlobalValue::PrivateLinkage, ConstantDataArray::get(module.getContext(), bytes),
                    "iw_gc_empty_maps");
            map->setSection(Triple(module.getTargetTriple()).isOSDarwin()
                    ? "__LLVM_STACKMAPS,__llvm_stackmaps" : ".llvm_stackmaps");
            map->setAlignment(Align(8));
            appendToCompilerUsed(module, {map});
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
