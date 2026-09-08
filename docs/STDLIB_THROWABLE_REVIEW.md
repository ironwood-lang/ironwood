# Throwable rendering compatibility review

## T3: inherited identity rendering loses the message

**Status:** Fixed under D114. Ordinary exception rendering now includes the
concrete class and localized message. This completes T3's description behavior,
not the entire Java Throwable API. Public stack-trace printing was subsequently
implemented under [D121](STDLIB_STACK_TRACE_REVIEW.md).

### Evidence and cause

Before this repair, at commit `4ded1c0603e78590277862bc89b193ff51c96978`,
`Throwable` declared message/cause access and secondary-exception inspection,
but neither `getLocalizedMessage()` nor `toString()`. A native reproduction of
`System.out.println(new RuntimeException("boom"))` produced
`ironwood.lang.RuntimeException@9c899c1f`. The hash is allocation-dependent;
the inherited identity rendering was the defect.

The D054 uncaught reporter already used the concrete type name and stored
message. It does so through runtime-private metadata and a field offset,
without calling the virtual public description methods. Correct crash output
therefore did not establish correct printing, concatenation, or builder append.
D097 later added constructor-supplied causes for a focused application without
closing this existing inherited-method gap. That application called
`getMessage()` explicitly, which also bypassed the defective path. This is
evidence of limited coverage, not proof of the original author's intent.

The failure was an incomplete behavioral surface audit. A missing override was
treated like an omitted capability, even though Object's default kept the Java
call valid and silently changed its meaning. Checking explicit method lists
and application output was insufficient. Neither closed-world compilation nor
explicit reclamation requires dropping exception messages.

### Contract and implementation choice

The [Java SE 21 Throwable specification](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Throwable.html#toString())
defines the description through the concrete class name and virtual
`getLocalizedMessage()`. The default localized getter delegates to
`getMessage()`. Null omits the separator; empty text retains it. Getter overrides
and failures are observable behavior, so reading only the stored message field
would leave another compatibility defect.

Independent implementation is appropriate for this small composition rule.
The public methods remain ordinary Ironwood source. A private typed operation
uses the type name already present in native descriptors and allocates one
exact-size result String, with no temporary class-name String, builder, or
character array. The compiler/runtime boundary is an Ironwood mechanism, not a
reason to change the public text. No OpenJDK implementation source or tests were
copied or adapted; all additions use `MIT OR Apache-2.0`.

The repair also accounts for virtual getters that allocate text. Extending
D089's concrete-descriptor ownership protocol lets the facade reclaim a getter
result only when the compiler proves it fresh and unescaped. Borrowed, retained,
and uncertain results stay live. Source `finally` handles description allocation
failure. Receiver borrowing is separately conditioned on getter escape effects;
an override that publishes `this` must still prevent subsequent reclamation.
These requirements explain why a safe native implementation involves more than
adding string concatenation to one override.

### Verification

The focused regressions cover:

- A Java-generated output oracle for normal, missing, empty, Unicode, nested,
  custom, localized, fresh-message, null-localized, Error, cause-bearing, and
  caught exceptions. Only class names are normalized to Ironwood names.
- Direct results, `print`, `println`, concatenation, and builder append, including
  distinct caller-owned results and reclamation of consumed descriptions.
- Both getter override points, single getter evaluation, borrowed heap text,
  fresh temporary text, and a getter that retains its newly allocated result.
- Getter exceptions and description allocation failure, including cleanup of an
  already produced fresh message without an extra emergency allocation.
- Typed allocation and ownership metadata, destructor allocation rejection,
  and safe-`free` rejection when a getter publishes its receiver.

Native fixtures compile to separate `.ironclass` inputs and link at `-O3` with
the bundled standard-library archive. Existing object/collection rendering,
PrintStream ownership, and uncaught-trace regressions also pass. The full suite
is not required for this focused repair.

### Remaining boundary and prevention

T3 left `printStackTrace()` absent because D054's first-throw snapshot could not
provide Java's construction-time trace for never-thrown exceptions. D121 now
resolves that boundary with construction-time capture, explicit refresh and
public printing. The [stack-trace review](STDLIB_STACK_TRACE_REVIEW.md) records
causes, secondary failures, virtual descriptions, destinations, ownership and
allocation failure. This follow-up preserves the uncaught reporter's fatal
prefix and exit status.

The mandatory [porting review](OPENJDK_PORTING.md#behavioral-contract-review)
now explicitly calls for consumer and subclass-override checks when auditing
inherited behavior. `AGENTS.md` already requires that review for all
standard-library semantic work. No additional top-level rule is needed.

### Compiler regression follow-up

The T3 repair at `37caa2b` introduced an unbounded superclass walk in
`ThrowableSemantics.isThrowable`. The compiler already diagnosed inheritance
cycles but continued into analyses that assumed an acyclic hierarchy. A focused
historical reproduction rejects the same self-cycle at `4ded1c0` and hangs at
`37caa2b`. D121 subsequently added another path into this helper.

Cycle validation now stops semantic analysis before those consumers. The cycle
regression runs a child compiler with a timeout and checks self, indirect,
interface, and generic cycle diagnostics. The original rendering checks were
insufficient coverage for a shared compiler helper. This was an implementation
and verification mistake, not a necessary cost of Java-compatible descriptions.
See the [performance investigation](TEST_PERFORMANCE.md#throwable-compiler-regression-2026-09-06)
for the separate compilation slowdown and its measurements.

### Constructor-family follow-up

D126 completes the common cause-constructor family on `Error`, `IOException`,
`IllegalArgumentException`, `IllegalStateException`, and
`UnsupportedOperationException`. These forms delegate to the established
`Throwable` behavior, including the cause description used as the message by a
cause-only constructor. Suppression, serialization, and the protected
four-argument Throwable constructor remain outside the supported surface.
