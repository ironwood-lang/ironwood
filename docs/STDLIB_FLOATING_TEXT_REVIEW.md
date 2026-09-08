# Floating text overload compatibility review

## T5: missing float append silently selects double formatting

**Status:** Fixed under D116. `StringBuilder.append(float)` preserves float
conversion. `String.valueOf(float)` and `String.valueOf(double)` also exist and
return fresh caller-owned Strings. General Formatter support and floating
wrapper-class `toString` APIs remain deferred in D116; D122 subsequently
implements the selected Float/Double static text helpers.

### Evidence and cause

At the preceding T4 commit `d7c23db`, native `-O3` reproduction confirmed:

| Builder argument | Previous text | Required Java text |
| --- | --- | --- |
| `0.1f` | `0.10000000149011612` | `0.1` |
| `1.0f / 3.0f` | `0.3333333432674408` | `0.33333334` |

`String.valueOf(float)` also failed at compilation because its overload set
ended with long. The existing output and concatenation paths already retained
float versus double conversion kinds.

D097 added `append(double)` for an application-driven numeric output path. Its
implementation correctly rendered a double and reclaimed the temporary String,
but float callers were admitted by ordinary widening. The compiler preserved the numeric
value exactly; the resulting type selected double decimal spelling. A float's
shorter text need only distinguish its binary32 value, while its widened double
may need more digits to distinguish that binary64 value.

This was an incomplete overload and consumer review. Application demand for
double append did not establish that it was an adequate replacement for every
numeric call it accepted. U1's float-aware output, D061's concatenation, and
String's missing floating valueOf overloads should have been compared together.
No native compilation or ownership constraint required these differences.

### Contract and implementation choice

The [Java 21 builder contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/StringBuilder.html#append(float))
uses float text for the float overload. The matching
[String.valueOf contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html#valueOf(float))
preserves that type-specific conversion. An explicit cast to double still
selects double text; the fix must not shorten that intentionally different call.

The repair adds the missing overloads as original Ironwood source. Each floating
valueOf uses the existing typed concatenation conversion with its original
primitive type. Both builder overloads delegate to the corresponding valueOf,
copy its text, and reclaim the temporary in `finally`. Compiler-owned result
summaries mark both new valueOf results fresh so callers and builder cleanup can
use normal safe-`free` proofs. No runtime or decimal algorithm changes are needed.
No OpenJDK implementation source or tests were copied or adapted; the additions
remain `MIT OR Apache-2.0`.

Independent implementation was appropriate, and reusing the existing converter
reduces the chance of inconsistent spelling. The wrong decision was treating a
partial overload set as a sufficient behavioral surface. Translating an entire
OpenJDK class would not replace the need to verify which calls reach which
conversion after adapting the API.

### Verification and prevention

Typed coverage checks that float valueOf uses the FLOAT concatenation kind,
double uses DOUBLE, and a float append caller introduces no float-to-double
conversion. Native `-O3` coverage compares four output paths against Java for
39 representative values, including both report examples, explicit widening,
signed zero, subnormals, minimum normal values, extreme magnitudes, scientific
notation thresholds, NaN, and infinities. A mixed append chain checks neighboring
primitive overloads and chaining. This is focused overload coverage, not an
exhaustive audit of every input to the existing decimal formatter.

Each valueOf must allocate exactly one result. With sufficient builder capacity,
append allocates and reclaims one temporary and returns the same builder. The
fixture reclaims all results and builders. Allocation limits separately fail
conversion and subsequent builder growth for both primitive types, verifying
cleanup and unchanged builder contents/capacity. Native fixtures use separate
class inputs and final linking with the standard-library archive. The existing
floating concatenation regression remains passing.

The required [behavioral review](OPENJDK_PORTING.md#behavioral-contract-review)
now explicitly distinguishes value-preserving widening from type-dependent text
semantics. `AGENTS.md` already mandates that review; another top-level rule is
unnecessary. Review the whole related overload group and ordinary consumers,
even when the motivating application uses only one member.
