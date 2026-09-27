// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Manifest;

/** Versioned pairing and content inventory, kept outside the private native ABI. */
final class BridgePackageManifest {
    private BridgePackageManifest() {}
    static final String PATH = "META-INF/ironwood/bridge.properties";

    static byte[] javaManifest(BridgeGeneration generation) throws IOException {
        var manifest = new Manifest();
        var attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue("Automatic-Module-Name", moduleName(generation));
        attributes.putValue("Ironwood-Bridge-Schema", BridgeGeneration.SCHEMA);
        attributes.putValue("Ironwood-Bridge-Generation", generation.identity());
        var output = new ByteArrayOutputStream(); manifest.write(output); return output.toByteArray();
    }

    static byte[] create(BridgeGeneration generation, BridgeGeneration.NativeBuild build, BridgeJavaSources java,
            BridgeMacPayload target, String imagePath, Map<String, byte[]> entries) {
        var properties = new TreeMap<>(generation.manifest());
        properties.put("java.module", moduleName(generation));
        properties.put("native.target", build.target()); properties.put("native.build", build.identity());
        properties.put("native.resource", imagePath); properties.put("native.sha256", BridgeGeneration.bytesDigest(entries.get(imagePath)));
        properties.put("native.pointer.bits", "64"); properties.put("native.endian", "little");
        properties.put("native.cpu", "baseline-arm64"); properties.put("native.macos.minimum", target.minimumOs());
        properties.put("native.macos.sdk", target.sdk());
        for (int i = 0; i < target.dependencies().size(); i++) properties.put("native.dependency." + i, target.dependencies().get(i));
        build.inputs().forEach((key, value) -> properties.put("native.input." + key, value));
        for (int i = 0; i < java.generatedTypes().size(); i++) properties.put("java.type." + i, java.generatedTypes().get(i));
        for (int i = 0; i < java.bindings().size(); i++) {
            var binding = java.bindings().get(i); String prefix = "java.binding." + i;
            properties.put(prefix + ".type", binding.binaryName()); properties.put(prefix + ".name", binding.nativeName());
            properties.put(prefix + ".descriptor", binding.descriptor()); properties.put(prefix + ".entry", binding.entrySymbol());
        }
        for (int i = 0; i < java.facadeRegistrations().size(); i++) {
            var binding = java.facadeRegistrations().get(i); String prefix = "java.facade.registration." + i;
            properties.put(prefix + ".type", binding.binaryName()); properties.put(prefix + ".name", binding.nativeName());
            properties.put(prefix + ".descriptor", binding.descriptor());
        }
        for (int i = 0; i < java.rootDestructions().size(); i++) {
            var binding = java.rootDestructions().get(i); String prefix = "java.root.destruction." + i;
            properties.put(prefix + ".type", binding.binaryName()); properties.put(prefix + ".name", binding.nativeName());
            properties.put(prefix + ".descriptor", binding.descriptor());
        }
        // The manifest does not contain its own digest. Every other final jar byte
        // sequence, including Java classes and the signed image, is inventoried.
        entries.forEach((name, bytes) -> properties.put("content.sha256." + name, BridgeGeneration.bytesDigest(bytes)));
        var text = new StringBuilder("# Ironwood Java Bridge paired artifact; schema " + BridgeGeneration.SCHEMA + "\n");
        properties.forEach((key, value) -> text.append(escape(key)).append('=').append(escape(value)).append('\n'));
        return text.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static String escape(String text) {
        var result = new StringBuilder();
        for (char value : text.toCharArray()) {
            if (value <= 32 || value > 126 || "\\:=#!".indexOf(value) >= 0) result.append(String.format("\\u%04x", (int)value));
            else result.append(value);
        }
        return result.toString();
    }

    private static String moduleName(BridgeGeneration generation) {
        // Implementation updates under the same producing artifact name must not
        // invalidate a compiled Java module's requires directive.
        return "ironwood.bridge.a" + BridgeGeneration.bytesDigest(generation.manifest().get("artifact").getBytes(StandardCharsets.UTF_8));
    }
}
