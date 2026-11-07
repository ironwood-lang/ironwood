// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.backend;

import java.io.IOException;
import java.util.Properties;

/** Exact build-profile selection must never mix a configuration and receipt. */
public final class GcConfigurationTests {
    private GcConfigurationTests() {}

    public static void profiles() throws Exception {
        var properties = new Properties();
        String old = "a".repeat(64), current = "b".repeat(64);
        String oldSystem = "c".repeat(64), currentSystem = "d".repeat(64);
        properties.setProperty("linux-x86_64.configuration.sha256", old);
        properties.setProperty("linux-x86_64.sysroot.record.sha256", oldSystem);
        properties.setProperty("linux-x86_64.native-host.configuration.sha256", current);
        properties.setProperty("linux-x86_64.native-host.sysroot.record.sha256", currentSystem);
        check(GcDependency.qualifiedProfile(properties, "linux-x86_64", old)
                .equals(new GcDependency.QualifiedProfile(old, oldSystem)), "old profile changed");
        check(GcDependency.qualifiedProfile(properties, "linux-x86_64", current)
                .equals(new GcDependency.QualifiedProfile(current, currentSystem)), "native profile mixed receipts");
        check(!GcDependency.qualifiedProfile(properties, "linux-x86_64", current).matchesSystemInput(oldSystem),
                "native profile accepted the old profile's receipt");
        check(!GcDependency.qualifiedProfile(properties, "linux-x86_64", old).matchesSystemInput(currentSystem),
                "old profile accepted the native profile's receipt");
        denied(properties, "linux-x86_64", null);
        denied(properties, "linux-x86_64", "e".repeat(64));
        denied(properties, "linux-arm64", current);
        properties.setProperty("linux-arm64.configuration.sha256", current);
        properties.setProperty("linux-arm64.sysroot.record.sha256", currentSystem);
        check(GcDependency.qualifiedProfile(properties, "linux-arm64", current)
                .equals(new GcDependency.QualifiedProfile(current, currentSystem)), "arm64 profile mixed receipts");
        check(!GcDependency.qualifiedProfile(properties, "linux-arm64", current).matchesSystemInput(oldSystem),
                "arm64 profile accepted another sysroot receipt");
        denied(properties, "linux-arm64", old);
        properties.remove("linux-arm64.sysroot.record.sha256");
        denied(properties, "linux-arm64", current);
        denied(properties, "macos-arm64e", current);
        properties.remove("linux-x86_64.native-host.sysroot.record.sha256");
        denied(properties, "linux-x86_64", current);
        properties.setProperty("linux-x86_64.native-host.sysroot.record.sha256", "missing");
        denied(properties, "linux-x86_64", current);
        check(GcDependency.qualifiedProfile(properties, "linux-x86_64", old).systemInput().equals(oldSystem),
                "bad alternate profile invalidated the existing profile");
        properties.setProperty("macos-arm64.configuration.sha256", current);
        properties.setProperty("macos-arm64.sdk.settings.sha256", currentSystem);
        check(GcDependency.qualifiedProfile(properties, "macos-arm64", current).systemInput().equals(currentSystem),
                "macOS SDK binding changed");
    }

    private static void denied(Properties properties, String target, String identity) throws Exception {
        try { GcDependency.qualifiedProfile(properties, target, identity); }
        catch (IOException expected) { return; }
        throw new AssertionError("unqualified profile accepted: " + target);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
