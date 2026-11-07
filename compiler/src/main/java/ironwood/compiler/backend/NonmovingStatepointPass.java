// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;

/** Cold build/load boundary for the first-party LLVM pass, outside SDK relocation. */
public final class NonmovingStatepointPass {
    private static final String PAYLOAD = "ironwood-nonmoving-statepoints.so";
    private static final String MANIFEST = "ironwood-nonmoving-statepoints.properties";
    private static final String SOURCE = "compiler/src/main/cpp/GcNonmovingStatepoints.cpp";

    private NonmovingStatepointPass() {}

    static Path select(LlvmToolchain tools, Path distribution, Path temporary, MacNativeTools mac) throws IOException {
        Path lib = distribution.resolve("lib"), manifest = lib.resolve(MANIFEST), payload = lib.resolve(PAYLOAD);
        boolean bundled = Files.isDirectory(distribution.resolve("toolchain/lib/jvm"));
        if (bundled || Files.exists(manifest) || Files.exists(payload)) {
            var values = new Properties();
            try (var input = Files.newBufferedReader(manifest)) { values.load(input); }
            if (!"1".equals(values.getProperty("format"))
                    || !tools.version().equals(values.getProperty("llvm.version"))
                    || !System.getProperty("os.name").equals(values.getProperty("target.os"))
                    || !System.getProperty("os.arch").equals(values.getProperty("target.arch"))
                    || Files.isSymbolicLink(payload) || !Files.isRegularFile(payload)
                    || !TlsDependency.sha256(payload).equals(values.getProperty("pass.binary.sha256"))
                    || !TlsDependency.sha256(distribution.resolve(SOURCE)).equals(values.getProperty("pass.source.sha256"))) {
                throw new IOException("statepoint pass: missing, stale or mismatched packaged payload");
            }
            return payload;
        }
        return build(tools, distribution, temporary, mac);
    }

    private static Path build(LlvmToolchain tools, Path distribution, Path output, MacNativeTools mac) throws IOException {
        if (!tools.version().equals("23.1.0")) throw new IOException("statepoint pass requires LLVM 23.1.0");
        Files.createDirectories(output);
        Path payload = output.resolve(PAYLOAD);
        var command = new ArrayList<>(List.of(tools.clang().toString(), "--driver-mode=g++"));
        if (mac == null) command.addAll(List.of("--no-default-config", "--gcc-toolchain=/usr", "--sysroot=/"));
        else {
            command.addAll(mac.compileFlags()); command.addAll(mac.linkFlags());
            command.add("-mmacosx-version-min=14.0");
        }
        command.addAll(List.of("-std=c++17", "-fno-exceptions", "-fPIC", "-shared", "-O2",
                "-I" + tools.home().resolve("include"), "-L" + tools.home().resolve("lib"),
                "-Wl,-rpath," + (mac == null ? "$ORIGIN/../toolchain/lib" : "@loader_path/../toolchain/lib"),
                "-lLLVM", distribution.resolve(SOURCE).toString(), "-o", payload.toString()));
        run(command);
        return payload;
    }

    private static void run(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (process.waitFor() != 0) throw new IOException("statepoint pass build/load failed: " + output);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("statepoint pass preparation interrupted", error);
        }
    }

    /** Packaging entry point; users never build this dependency at IDK link time. */
    public static void main(String[] args) throws IOException {
        if (args.length != 3) throw new IOException("expected distribution, LLVM home and output directory");
        Path distribution = Path.of(args[0]).toAbsolutePath(), output = Path.of(args[2]).toAbsolutePath();
        var discovery = LlvmToolchain.discover(Path.of(args[1]));
        if (!discovery.successful()) throw new IOException(discovery.error());
        var tools = discovery.toolchain().orElseThrow();
        var mac = System.getProperty("os.name").startsWith("Mac") ? MacNativeTools.discover() : null;
        Path payload = build(tools, distribution, output, mac);
        Path probe = output.resolve("statepoint-probe.ll");
        Files.writeString(probe, "@iw_gc_root_abi = constant i32 2\n");
        try {
            run(List.of(tools.opt().toString(), "-load-pass-plugin=" + payload,
                    "-passes=ironwood-nonmoving-statepoints,verify", "-disable-output", probe.toString()));
        } finally { Files.deleteIfExists(probe); }
        var values = new TreeMap<String, String>();
        values.put("format", "1"); values.put("llvm.version", tools.version());
        values.put("target.os", System.getProperty("os.name")); values.put("target.arch", System.getProperty("os.arch"));
        values.put("pass.source.sha256", TlsDependency.sha256(distribution.resolve(SOURCE)));
        values.put("pass.binary.sha256", TlsDependency.sha256(payload));
        values.put("producer.opt.sha256", TlsDependency.sha256(tools.opt()));
        StringBuilder text = new StringBuilder("# SPDX-License-Identifier: MIT OR Apache-2.0\n");
        values.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        Files.writeString(output.resolve(MANIFEST), text);
    }
}
