// SPDX-License-Identifier: MIT OR Apache-2.0
package probe;

/** Java 22-only entry point; JNI baseline never links FFM classes. */
public final class Launch {
    public static void main(String[] args) throws Throwable {
        System.load(args[0]);
        FfmProbe.run(args);
    }
}
