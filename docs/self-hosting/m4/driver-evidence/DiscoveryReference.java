// SPDX-License-Identifier: MIT OR Apache-2.0

import java.lang.reflect.Method;

/**
 * Calls the Java seed's discovery adaptations by reflection on the compiler
 * classes on the class path, in this JVM's environment: "brew" prints
 * LlvmToolchain.discoverHomebrewPrefix(), "sdk" TlsDependency.appleSdk() and
 * "mac" the SDK and linker of MacNativeTools.discover(), and "toolchain"
 * (with an LLVM home) the probe answers the native fixture
 * compiler_discovery_probes.iron prints: LLVM and Clang versions and, on
 * macOS, the SDK, linker and their versions; "search" (with a working
 * directory, then name and PATH pairs) prints ExecutableSearch.find for each.
 */
public final class DiscoveryReference {
    public static void main(String[] args) throws Exception {
        String backend = "ironwood.compiler.backend.";
        switch (args[0]) {
            case "brew" -> System.out.println(call(backend + "LlvmToolchain", "discoverHomebrewPrefix"));
            case "sdk" -> System.out.println(call(backend + "TlsDependency", "appleSdk"));
            case "mac" -> {
                Object tools = call(backend + "MacNativeTools", "discover");
                System.out.println(tools.getClass().getMethod("sdk").invoke(tools) + "\n"
                        + tools.getClass().getMethod("linker").invoke(tools));
            }
            case "toolchain" -> {
                Object discovery = Class.forName(backend + "LlvmToolchain").getMethod("discover",
                        java.nio.file.Path.class).invoke(null, java.nio.file.Path.of(args[1]));
                Object toolchain = ((java.util.Optional<?>) discovery.getClass().getMethod("toolchain")
                        .invoke(discovery)).orElseThrow();
                System.out.println("llvm version " + toolchain.getClass().getMethod("version").invoke(toolchain));
                System.out.println("clang " + toolchain.getClass().getMethod("clangVersion").invoke(toolchain));
                if (System.getProperty("os.name").startsWith("Mac")) {
                    Object tools = call(backend + "MacNativeTools", "discover");
                    for (String field : new String[]{"sdk", "linker", "sdkVersion", "linkerVersion"}) {
                        System.out.println(field + " " + tools.getClass().getMethod(field).invoke(tools));
                    }
                }
            }
            case "search" -> {
                Method find = Class.forName(backend + "ExecutableSearch").getDeclaredMethod("find", String.class,
                        String.class, java.nio.file.Path.class);
                find.setAccessible(true);
                for (int index = 2; index + 1 < args.length; index += 2) {
                    Object found = find.invoke(null, args[index], args[index + 1], java.nio.file.Path.of(args[1]));
                    System.out.println(((java.util.Optional<?>) found).map(Object::toString).orElse("<none>"));
                }
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    private static Object call(String owner, String name) throws Exception {
        Method method = Class.forName(owner).getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(null);
    }
}
