// SPDX-License-Identifier: MIT OR Apache-2.0

import java.lang.reflect.Method;

/**
 * Calls the Java seed's discovery adaptations by reflection on the compiler
 * classes on the class path, in this JVM's environment: "brew" prints
 * LlvmToolchain.discoverHomebrewPrefix(), "sdk" TlsDependency.appleSdk() and
 * "mac" the SDK and linker of MacNativeTools.discover().
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
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    private static Object call(String owner, String name) throws Exception {
        Method method = Class.forName(owner).getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(null);
    }
}
