// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import java.util.List;
import java.util.Optional;

public record IrProgram(String moduleName, List<IrClass> classes, List<IrStaticField> staticFields,
                        List<IrTypeInitialization> typeInitializations,
                        List<IrArrayType> arrayTypes,
                        List<IrStringConstant> stringConstants,
                        List<IrDispatchSlot> dispatchSlots,
                        List<IrFunction> functions, Optional<IrFunction> entryPoint,
                        Optional<IrImmortalObject> allocationFailure) {
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
    }
}
