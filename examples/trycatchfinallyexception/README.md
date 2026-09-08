# Try/catch/finally exception outcomes

This example builds four native programs covering every combination of normal
completion and exception propagation across a protected body and its `finally`
block.

| Program | Protected body | `finally` | Verified result |
| --- | --- | --- | --- |
| `Case1BodyCompletesFinallyCompletes` | completes | completes | Prints normal completion and exits 42. |
| `Case2BodyThrowsFinallyCompletes` | throws `FailureA` | completes | Prints uncaught `FailureA` with its message and trace. |
| `Case3BodyCompletesFinallyThrows` | completes | throws `FailureB` | Prints uncaught `FailureB` with its message and trace. |
| `Case4BodyThrowsFinallyThrows` | throws `FailureA` | throws `FailureB` | Prints primary `FailureA` and secondary `FailureB`, each with its message and trace. |

Build and run all four from this directory:

```console
$ ./compile.sh
$ ./link.sh
$ ./run1.sh
$ ./run2.sh
$ ./run3.sh
$ ./run4.sh
```

`run1.sh` verifies normal completion and status `42`. The other three programs
catch the propagated value long enough to validate its type and secondary state,
then rethrow the same primary object. Their run scripts expect status `1` and
verify Feature 73's complete uncaught report, including messages, source frames,
and Case 4's independently traced secondary exception.
