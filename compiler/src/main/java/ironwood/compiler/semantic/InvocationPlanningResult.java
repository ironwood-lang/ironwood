// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A pure lookup or planning outcome which distinguishes absence from rejection. */
record InvocationPlanningResult<T>(Status status, Optional<T> value,
                                   List<PlanningRejection> rejections) {
    InvocationPlanningResult {
        Objects.requireNonNull(status, "status");
        value = value == null ? Optional.empty() : value;
        rejections = rejections == null ? List.of() : List.copyOf(rejections);
        if (status == Status.RESOLVED && value.isEmpty()) {
            throw new IllegalArgumentException("resolved planning result requires a value");
        }
        if (status != Status.RESOLVED && value.isPresent()) {
            throw new IllegalArgumentException("unresolved planning result cannot carry a value");
        }
        if (status == Status.REJECTED && rejections.isEmpty()) {
            throw new IllegalArgumentException("rejected planning result requires a reason");
        }
        if (status != Status.REJECTED && !rejections.isEmpty()) {
            throw new IllegalArgumentException("only rejected planning results carry reasons");
        }
    }

    static <T> InvocationPlanningResult<T> resolved(T value) {
        return new InvocationPlanningResult<>(Status.RESOLVED, Optional.of(value), List.of());
    }

    static <T> InvocationPlanningResult<T> notFound() {
        return new InvocationPlanningResult<>(Status.NOT_FOUND, Optional.empty(), List.of());
    }

    static <T> InvocationPlanningResult<T> rejected(PlanningRejection rejection) {
        return rejected(List.of(rejection));
    }

    static <T> InvocationPlanningResult<T> rejected(List<PlanningRejection> rejections) {
        return new InvocationPlanningResult<>(Status.REJECTED, Optional.empty(), rejections);
    }

    boolean isResolved() {
        return status == Status.RESOLVED;
    }

    boolean isNotFound() {
        return status == Status.NOT_FOUND;
    }

    boolean isRejected() {
        return status == Status.REJECTED;
    }

    T resolvedValue() {
        return value.orElseThrow(() -> new IllegalStateException("planning result is not resolved"));
    }

    enum Status {
        RESOLVED,
        NOT_FOUND,
        REJECTED
    }

    /** A diagnostic-neutral reason which the integrating analyzer can render as a diagnostic. */
    record PlanningRejection(Code code, String message, SourceSpan span,
                             Optional<String> candidateIdentity,
                             List<PlanningRejection> causes) {
        PlanningRejection {
            Objects.requireNonNull(code, "code");
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("planning rejection requires a message");
            }
            Objects.requireNonNull(span, "span");
            candidateIdentity = candidateIdentity == null ? Optional.empty() : candidateIdentity;
            causes = causes == null ? List.of() : List.copyOf(causes);
        }

        static PlanningRejection of(Code code, String message, SourceSpan span) {
            return new PlanningRejection(code, message, span, Optional.empty(), List.of());
        }

        static PlanningRejection causedBy(Code code, String message, SourceSpan span,
                                          List<PlanningRejection> causes) {
            return new PlanningRejection(code, message, span, Optional.empty(), causes);
        }

        PlanningRejection forCandidate(InvocationCandidate candidate) {
            return new PlanningRejection(code, message, span,
                    Optional.of(candidate.identity()), causes);
        }

        enum Code {
            UNRESOLVED_NAME,
            UNRESOLVED_TYPE,
            TYPE_USED_AS_VALUE,
            INVALID_RECEIVER,
            UNRESOLVED_FIELD,
            INACCESSIBLE_MEMBER,
            WRONG_INVOCATION_FORM,
            NO_CANDIDATES,
            WRONG_TYPE_ARGUMENT_COUNT,
            TYPE_ARGUMENT_REJECTED,
            INFERENCE_FAILED,
            WRONG_ARITY,
            ARGUMENT_REJECTED,
            CONVERSION_REJECTED,
            AMBIGUOUS_INVOCATION,
            INVALID_OPERAND,
            INVALID_ASSIGNMENT,
            INVALID_ARRAY_OPERATION,
            INVALID_CONSTRUCTION,
            UNSUPPORTED_EXPRESSION
        }
    }
}
