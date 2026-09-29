// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.semantic.BridgeArrayInputs;

/** Proved array ownership drives copy-back, Java identity, and result destruction. */
final class BridgeArrayValueSources {
    private BridgeArrayValueSources() {}

    static BridgeArrayInputs.Contract contract(CompilationArtifact artifact, BridgeCallableId callable) {
        if (!callable.result().isArray() && BridgeArrayInputSources.indices(callable.parameters()).isEmpty()) return null;
        var proof = BridgeArrayInputs.values(artifact, callable);
        if (proof.status() != BridgeProof.Status.PROVED) throw new IllegalArgumentException(proof.reason());
        return proof.contract().orElseThrow();
    }

    static void finish(StringBuilder text, BridgeArrayInputs.Contract contract, String frame, String failure,
                       String exit, boolean pendingCommit, boolean ownedStringResult) {
        if (contract == null || !contract.mutable() || contract.inputs().isEmpty()) {
            text.append("    if (status != 0) { ").append(failure).append("(env, status, ").append(frame)
                    .append(".exception);\n");
            if (contract != null) text.append(BridgeArrayInputSources.release(contract.callable().parameters()));
            text.append("        ").append(exit).append(" }\n");
            return;
        }
        text.append("    jthrowable array_primary = NULL;\n")
                .append("    if (status != 0) ").append(failure).append("(env, status, ").append(frame).append(".exception);\n")
                .append("    if (").append(pendingCommit ? "(*env)->ExceptionCheck(env)" : "status != 0").append(") {\n")
                .append("        array_primary = (*env)->ExceptionOccurred(env); (*env)->ExceptionClear(env);\n    }\n");
        // Only the first occurrence owns a converted buffer. Even on failure each
        // later distinct input is attempted with no pending JNI exception.
        for (int index : contract.inputs().stream().sorted().toList()) {
            var type = contract.callable().parameters().get(index);
            text.append("    if (array_state").append(index).append(".converted != NULL && array_state")
                    .append(index).append(".length > 0) {\n")
                    .append("        (*env)->Set").append(BridgeArrayInputSources.regionKind(type)).append("ArrayRegion(env, arg")
                    .append(index).append(", 0, array_state").append(index).append(".length, (const j")
                    .append(BridgeJavaTypes.sourceName(type.elementType())).append(" *)array_state").append(index).append(".converted->data);\n")
                    .append("        if ((*env)->ExceptionCheck(env)) {\n")
                    .append("            jthrowable failure = (*env)->ExceptionOccurred(env); (*env)->ExceptionClear(env);\n")
                    .append("            if (array_primary == NULL) array_primary = failure;\n")
                    .append("            iw_array_failure(env, &iw_exceptions, array_primary, failure, ").append(index).append(");\n")
                    .append("            if (failure != array_primary) (*env)->DeleteLocalRef(env, failure);\n        }\n    }\n");
        }
        text.append("    if (array_primary != NULL) {\n");
        if (fresh(contract) || ownedStringResult) text.append("        if (status == 0) ironwood_deallocate(").append(frame).append(".value.reference);\n");
        text.append(BridgeArrayInputSources.release(contract.callable().parameters()))
                .append("        (*env)->Throw(env, array_primary); (*env)->DeleteLocalRef(env, array_primary);\n")
                .append("        ").append(exit).append("\n    }\n");
    }

    static boolean fresh(BridgeArrayInputs.Contract contract) {
        return contract.result().map(result -> result.kind() == BridgeResultOriginContract.Kind.FRESH_ROOT).orElse(false);
    }

    static void result(StringBuilder text, BridgeArrayInputs.Contract contract, String frame) {
        var type = contract.callable().result();
        var origin = contract.result().orElseThrow();
        text.append("    ").append(BridgeValueNativeSources.jniType(type)).append(" copied = NULL;\n");
        if (origin.kind() == BridgeResultOriginContract.Kind.INPUT_ALIAS) {
            for (int index : origin.inputs().stream().sorted().toList()) {
                text.append("    if (").append(frame).append(".value.reference != NULL && ").append(frame)
                        .append(".value.reference == array").append(index).append("->converted) copied = arg").append(index).append(";\n");
            }
        } else if (origin.kind() == BridgeResultOriginContract.Kind.FRESH_ROOT) {
            String kind = BridgeArrayInputSources.regionKind(type);
            text.append("    const struct ironwood_array *value = ").append(frame).append(".value.reference;\n")
                    .append("    if (value != NULL) {\n")
                    .append("        copied = (*env)->New").append(kind).append("Array(env, (jsize)value->length);\n")
                    .append("        if (copied == NULL) iw_exception_error(env, &iw_exceptions, 1, \"Ironwood array result allocation failed\");\n")
                    .append("        else if (value->length != 0) (*env)->Set").append(kind)
                    .append("ArrayRegion(env, copied, 0, (jsize)value->length, (const j")
                    .append(BridgeJavaTypes.sourceName(type.elementType())).append(" *)value->data);\n    }\n")
                    .append("    ironwood_deallocate(").append(frame).append(".value.reference);\n");
        } else if (origin.kind() != BridgeResultOriginContract.Kind.NULL_ONLY) {
            throw new IllegalArgumentException("unproved copied array result");
        }
        text.append(BridgeArrayInputSources.release(contract.callable().parameters())).append("    return copied;\n");
    }
}
