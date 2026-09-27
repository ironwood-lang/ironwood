// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public record IrProgram(String moduleName, List<IrClass> classes, List<IrStaticField> staticFields,
                        List<IrTypeInitialization> typeInitializations,
                        List<IrArrayType> arrayTypes,
                        List<IrStringConstant> stringConstants,
                        List<IrDispatchSlot> dispatchSlots,
                        List<IrFunction> functions, Optional<IrFunction> entryPoint,
                        Optional<IrImmortalObject> allocationFailure,
                        Set<String> exportRoots) {
    public IrProgram(String moduleName, List<IrClass> classes, List<IrStaticField> staticFields,
                     List<IrTypeInitialization> typeInitializations, List<IrArrayType> arrayTypes,
                     List<IrStringConstant> stringConstants, List<IrDispatchSlot> dispatchSlots,
                     List<IrFunction> functions, Optional<IrFunction> entryPoint,
                     Optional<IrImmortalObject> allocationFailure) {
        this(moduleName, classes, staticFields, typeInitializations, arrayTypes, stringConstants,
                dispatchSlots, functions, entryPoint, allocationFailure, Set.of());
    }

    public IrProgram {
        classes = List.copyOf(classes);
        staticFields = List.copyOf(staticFields);
        typeInitializations = List.copyOf(typeInitializations);
        arrayTypes = List.copyOf(arrayTypes);
        stringConstants = List.copyOf(stringConstants);
        dispatchSlots = List.copyOf(dispatchSlots);
        functions = List.copyOf(functions);
        entryPoint = entryPoint == null ? Optional.empty() : entryPoint;
        allocationFailure = allocationFailure == null ? Optional.empty() : allocationFailure;
        exportRoots = Set.copyOf(exportRoots);
        if (!exportRoots.isEmpty() && entryPoint.isPresent()) {
            throw new IllegalArgumentException("native library roots cannot coexist with an executable entry point");
        }
        var resolved = functions.stream().map(IrFunction::linkageName).collect(java.util.stream.Collectors.toSet());
        if (!resolved.containsAll(exportRoots)) throw new IllegalArgumentException("unresolved native export root");
    }

    public boolean hasNativeRoots() { return entryPoint.isPresent() || !exportRoots.isEmpty(); }
}
