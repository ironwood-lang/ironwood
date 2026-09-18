// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.source.SourceFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

final class WgetTests {
    private WgetTests() {}

    static void ownership() throws Exception {
        String preamble = "package org.ironwood.wget; class Probe { static int run() throws Exception { ";
        check(preamble + "Url url = Url.parse(\"http://127.1/\"); "
                + "Url next = url.redirect(\"next\"); free url; String target = next.target(); "
                + "free next; int size = target.length(); free target; return size; } }", true);
        check(preamble + "Url url = Url.parse(\"http://127.1/\"); String host = url.host(); "
                + "free url; return host.length(); } }", false);
        check(preamble + "Url url = Url.parse(\"http://127.1/\"); String host = url.host(); "
                + "free host; free url; return 0; } }", false);
        check(preamble + "byte[] bytes = new byte[0]; ironwood.io.ByteArrayInputStream input = new ironwood.io.ByteArrayInputStream(bytes); "
                + "Response response = new Response(input, null); free input; "
                + "int status = response.head(0); free response; free bytes; return status; } }", false);
        check(preamble + "String host = new String(\"127.1\"); "
                + "ironwood.net.InetAddress address = ironwood.net.InetAddress.parseLiteral(host); "
                + "free host; boolean loopback = address.isLoopbackAddress(); free address; return loopback ? 0 : 1; } }", true);
    }

    private static void check(String source, boolean expected) throws Exception {
        var files = new ArrayList<SourceFile>();
        try (var paths = Files.list(Path.of("projects/wget/src/main/ironwood/org/ironwood/wget"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".iron")).sorted().toList())
                files.add(SourceFile.of(path.toString(), Files.readString(path)));
        }
        files.add(SourceFile.of("Probe.iron", source));
        var result = new CompilerPipeline(UnfreedMode.ERROR).compile(files);
        if (result.successful() != expected || !expected && result.diagnostics().stream()
                .noneMatch(d -> d.isError() && d.message().contains("free")))
            throw new AssertionError("wget ownership: " + result.diagnostics());
    }

    static void contracts() throws Exception {
        var discovery = ironwood.compiler.backend.LlvmToolchain.discover(null);
        if (!discovery.successful()) throw new AssertionError(discovery.error());
        var builder = new ProcessBuilder("python3", "scripts/test-networking-m6.py").inheritIO();
        builder.environment().put("IRONWOOD_LLVM_HOME", discovery.toolchain().orElseThrow().home().toString());
        var process = builder.start();
        if (!process.waitFor(900, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("wget driver exceeded fifteen minutes");
        }
        if (process.exitValue() != 0) throw new AssertionError("wget driver exit " + process.exitValue());
    }
}
