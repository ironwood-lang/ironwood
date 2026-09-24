// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

public final class ParityStdlibProbe {
    private ParityStdlibProbe() {
    }

    public static void main(String[] names) throws Exception {
        StandardLibrary library = StandardLibrary.discover();
        for (String name : names) {
            SourceFile source = library.locate(name).orElseThrow(
                    () -> new IllegalStateException("bundled type unavailable: " + name));
            System.out.println(name + "\t" + source.path());
        }
    }
}
