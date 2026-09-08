// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.TypeName;
import ironwood.compiler.source.SourceSpan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Immutable semantic data shared by local/anonymous discovery and capture analysis. */
final class LocalClassSemantics {
    private LocalClassSemantics() {
    }

    enum ClassKind {
        ROOT,
        MEMBER,
        LOCAL,
        ANONYMOUS,
        ENUM_CONSTANT
    }

    enum ScopeKind {
        TYPE_BODY,
        FIELD_INITIALIZER,
        INSTANCE_INITIALIZER,
        STATIC_INITIALIZER,
        CONSTRUCTOR,
        METHOD,
        BLOCK,
        BRANCH,
        LOOP,
        SWITCH,
        TRY,
        CATCH,
        FINALLY,
        ANONYMOUS_ARGUMENTS
    }

    enum VariableKind {
        PARAMETER,
        LOCAL,
        PATTERN,
        CATCH_PARAMETER
    }

    enum WriteKind {
        SIMPLE_ASSIGNMENT,
        COMPOUND_ASSIGNMENT,
        UPDATE
    }

    enum DiagnosticCode {
        NON_EFFECTIVELY_FINAL_CAPTURE,
        WRITE_TO_CAPTURED_VARIABLE,
        CAPTURE_FROM_STATIC_CONTEXT
    }

    record ClassIdentity(String binaryName, ClassKind kind, Optional<String> simpleName,
                         Optional<String> enclosingBinaryName, int lexicalOrdinal,
                         SourceSpan span) {
        ClassIdentity {
            if (binaryName == null || binaryName.isBlank()) {
                throw new IllegalArgumentException("lexical class requires a binary name");
            }
            if (kind == null || span == null) {
                throw new IllegalArgumentException("lexical class identity is incomplete");
            }
            simpleName = simpleName == null ? Optional.empty() : simpleName;
            enclosingBinaryName = enclosingBinaryName == null
                    ? Optional.empty() : enclosingBinaryName;
            if ((kind == ClassKind.LOCAL || kind == ClassKind.ANONYMOUS
                    || kind == ClassKind.ENUM_CONSTANT)
                    != (lexicalOrdinal > 0)) {
                throw new IllegalArgumentException(
                        "only local, anonymous, and enum-constant classes have lexical ordinals");
            }
            if ((kind == ClassKind.ANONYMOUS || kind == ClassKind.ENUM_CONSTANT)
                    && simpleName.isPresent()) {
                throw new IllegalArgumentException(
                        "anonymous or enum-constant class cannot have a simple name");
            }
            if (kind != ClassKind.ANONYMOUS && kind != ClassKind.ENUM_CONSTANT
                    && simpleName.isEmpty()) {
                throw new IllegalArgumentException("named class requires a simple name");
            }
        }

        boolean isLexicalClass() {
            return kind == ClassKind.LOCAL || kind == ClassKind.ANONYMOUS
                    || kind == ClassKind.ENUM_CONSTANT;
        }
    }

    record ScopeDescriptor(String id, ScopeKind kind, Optional<String> parentId,
                           String ownerBinaryName, SourceSpan span,
                           boolean staticContext) {
        ScopeDescriptor {
            if (id == null || id.isBlank() || kind == null
                    || ownerBinaryName == null || ownerBinaryName.isBlank() || span == null) {
                throw new IllegalArgumentException("lexical scope descriptor is incomplete");
            }
            parentId = parentId == null ? Optional.empty() : parentId;
        }
    }

    record VariableIdentity(String id, String name, VariableKind kind,
                            String ownerBinaryName, String declaringScopeId,
                            List<TypeName> types, SourceSpan nameSpan, int declarationOrdinal) {
        VariableIdentity {
            types = List.copyOf(types);
            if (id == null || id.isBlank() || name == null || name.isBlank()
                    || kind == null || ownerBinaryName == null || ownerBinaryName.isBlank()
                    || declaringScopeId == null || declaringScopeId.isBlank()
                    || types.isEmpty() || nameSpan == null || declarationOrdinal < 0) {
                throw new IllegalArgumentException("variable identity is incomplete");
            }
        }

        TypeName type() {
            return types.getFirst();
        }
    }

    record Write(VariableIdentity variable, WriteKind kind, SourceSpan span,
                 String writingClassBinaryName) {
        Write {
            if (variable == null || kind == null || span == null
                    || writingClassBinaryName == null || writingClassBinaryName.isBlank()) {
                throw new IllegalArgumentException("capture write is incomplete");
            }
        }
    }

    record CaptureReference(VariableIdentity variable, String capturingClassBinaryName,
                            SourceSpan span, boolean fromStaticContext,
                            boolean assignmentTarget) {
        CaptureReference {
            if (variable == null || capturingClassBinaryName == null
                    || capturingClassBinaryName.isBlank() || span == null) {
                throw new IllegalArgumentException("capture reference is incomplete");
            }
        }
    }

    record ClassCapturePlan(ClassIdentity identity,
                            Set<VariableIdentity> directCaptures,
                            Set<VariableIdentity> requiredCaptures,
                            Set<String> constructedLexicalTypes,
                            Optional<String> directEnclosingInstance,
                            Set<String> requiredEnclosingInstances,
                            List<CaptureReference> references,
                            Set<String> unresolvedNames) {
        ClassCapturePlan {
            if (identity == null) {
                throw new IllegalArgumentException("capture plan requires a class identity");
            }
            directCaptures = immutableSet(directCaptures);
            requiredCaptures = immutableSet(requiredCaptures);
            constructedLexicalTypes = immutableSet(constructedLexicalTypes);
            directEnclosingInstance = directEnclosingInstance == null
                    ? Optional.empty() : directEnclosingInstance;
            requiredEnclosingInstances = immutableSet(requiredEnclosingInstances);
            references = List.copyOf(references);
            unresolvedNames = immutableSet(unresolvedNames);
        }
    }

    record CaptureDiagnostic(DiagnosticCode code, String message, SourceSpan span,
                             String classBinaryName,
                             Optional<VariableIdentity> variable) {
        CaptureDiagnostic {
            if (code == null || message == null || message.isBlank() || span == null
                    || classBinaryName == null || classBinaryName.isBlank()) {
                throw new IllegalArgumentException("capture diagnostic is incomplete");
            }
            variable = variable == null ? Optional.empty() : variable;
        }
    }

    record CaptureResult(LocalClassDiscovery.Result discovery,
                         List<VariableIdentity> variables,
                         List<Write> writes,
                         Map<String, ClassCapturePlan> plans,
                         List<CaptureDiagnostic> diagnostics) {
        CaptureResult {
            if (discovery == null) {
                throw new IllegalArgumentException("capture result requires lexical discovery");
            }
            variables = List.copyOf(variables);
            writes = List.copyOf(writes);
            plans = Collections.unmodifiableMap(new LinkedHashMap<>(plans));
            diagnostics = List.copyOf(diagnostics);
        }

        Optional<ClassCapturePlan> plan(String binaryName) {
            return Optional.ofNullable(plans.get(binaryName));
        }
    }

    private static <T> Set<T> immutableSet(Set<T> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
