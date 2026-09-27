// SPDX-License-Identifier: MIT OR Apache-2.0

package org.ironwood.javabridge.consumer;

import java.io.IOException;
import org.ironwood.javabridge.value.Values;

/** Single-threaded bridge use: expect four output lines and exit status zero. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException {
        int sum = Values.add(19, 23);
        if (sum != 42) throw new AssertionError("incorrect sum");
        System.out.println(sum);
        String copied = Values.copy("bridge");
        if (!copied.equals("bridge")) throw new AssertionError("incorrect copied result");
        System.out.println("copied: " + copied);
        try {
            Values.fail();
            throw new AssertionError("missing checked exception");
        } catch (IOException expected) {
            if (!expected.getMessage().equals("example failure")) throw expected;
            System.out.println("caught: " + expected.getMessage());
        }
        int continued = Values.add(20, 22);
        if (continued != 42) throw new AssertionError("call after exception failed");
        System.out.println("continued: " + continued);
    }
}
