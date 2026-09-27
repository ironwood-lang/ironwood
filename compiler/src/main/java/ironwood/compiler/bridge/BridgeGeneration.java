// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrConstant;
import ironwood.compiler.ir.IrOperand;
import ironwood.compiler.ir.IrStringConstant;
import ironwood.compiler.semantic.BridgeApiFacts;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Target-independent generation identity, separate from API compatibility and image bytes. */
public final class BridgeGeneration {
    public static final String SCHEMA = "1";
    private final Map<String, String> manifest;

    private BridgeGeneration(Map<String, String> manifest) {
        this.manifest = Collections.unmodifiableMap(new TreeMap<>(manifest));
    }

    public Map<String, String> manifest() { return manifest; }
    public String identity() { return manifest.get("generation"); }
    public String apiIdentity() { return manifest.get("api"); }
    public String supportPackage() { return "ironwood.bridge.generated.g" + identity(); }

    public boolean matches(CompilationArtifact artifact, BridgeExportSurface surface) {
        return manifest.equals(create(manifest.get("artifact"), artifact, surface,
                manifest.get("compiler.version"), manifest.get("compiler.sha256"), manifest.get("runtime.sha256")).manifest);
    }

    /** Producer hashes must identify actual compiler content and complete runtime source inputs. */
    public static BridgeGeneration create(String artifactName, CompilationArtifact artifact,
            BridgeExportSurface surface, String compilerVersion, String compilerHash, String runtimeHash) {
        requireText(artifactName, "artifact name");
        requireText(compilerVersion, "compiler version");
        requireHash(compilerHash);
        requireHash(runtimeHash);
        var packages = surface.types().stream().map(BridgeApiFacts.Type::packageName).distinct().sorted().toList();
        var selected = BridgeExportSurface.valuePreview(artifact, packages);
        if (selected.surface().isEmpty() || !selected.surface().orElseThrow().equals(surface)) {
            throw new IllegalArgumentException("generation requires the complete current resolved export surface");
        }
        var facts = artifact.bridgeApiFacts().orElseThrow();
        var programInputs = new TreeMap<String, String>();
        // Include every analyzed declaration's entire source unit, including private
        // dependencies and bundled source. Container paths and archive packing differ
        // across reconstruction; nominal identities and source content do not.
        facts.types().forEach((name, type) -> programInputs.put(name, digest(Map.of("source", type.source().content()))));
        var manifest = new TreeMap<String, String>();
        manifest.put("schema", SCHEMA);
        manifest.put("transport", "jni");
        manifest.put("artifact", artifactName);
        manifest.put("java.release", "21");
        manifest.put("java.supported", "21,22,23");
        manifest.put("compiler.version", compilerVersion);
        manifest.put("compiler.sha256", compilerHash);
        manifest.put("runtime.sha256", runtimeHash);
        manifest.put("program", digest(programInputs));
        manifest.put("api", digest(api(surface)));
        manifest.put("generation", digest(manifest));
        return new BridgeGeneration(manifest);
    }

    /** Build inputs include target constraints, actual toolchain/options and dependency identities. */
    public NativeBuild nativeBuild(String target, Map<String, String> buildInputs) {
        requireText(target, "native target");
        if (buildInputs.isEmpty()) throw new IllegalArgumentException("native build requires its input inventory");
        var inputs = new TreeMap<String, String>();
        buildInputs.forEach((key, value) -> {
            requireText(key, "native input key");
            requireText(value, "native input value");
            inputs.put("input." + key, value);
        });
        inputs.put("schema", SCHEMA);
        inputs.put("generation", identity());
        inputs.put("api", apiIdentity());
        inputs.put("target", target);
        // The result can be embedded in the image. Hash the signed final bytes
        // separately at packaging, never feed that digest back into this identity.
        return new NativeBuild(identity(), apiIdentity(), target, digest(inputs), buildInputs);
    }

    public record NativeBuild(String generation, String api, String target, String identity,
                              Map<String, String> inputs) {
        public NativeBuild {
            inputs = Collections.unmodifiableMap(new TreeMap<>(inputs));
        }
    }

    public static String bytesDigest(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    public static String contentIdentity(Map<String, String> files) {
        if (files.isEmpty()) throw new IllegalArgumentException("content identity requires an input inventory");
        files.forEach((name, hash) -> {
            requireText(name, "input name");
            requireHash(hash);
        });
        return digest(files);
    }

    private static Map<String, String> api(BridgeExportSurface surface) {
        var values = new TreeMap<String, String>();
        values.put("schema", SCHEMA);
        values.put("marker", BridgeExportSurface.PACKAGE_MARKER);
        for (var type : surface.types()) {
            String prefix = "type." + type.binaryName();
            values.put(prefix + ".name", type.sourceName());
            values.put(prefix + ".kind", type.kind().name());
            values.put(prefix + ".enclosing", type.enclosingType().orElse(""));
            values.put(prefix + ".static", Boolean.toString(type.staticMember()));
            values.put(prefix + ".final", Boolean.toString(type.finalType()));
            values.put(prefix + ".abstract", Boolean.toString(type.abstractType()));
            indexed(values, prefix + ".parents", type.supertypes().stream().map(BridgeJavaTypes::descriptor).toList());
            for (var field : type.fields()) {
                String key = prefix + ".field." + field.name();
                values.put(key + ".type", BridgeJavaTypes.descriptor(field.type()));
                values.put(key + ".constant", constant(field.constant().orElseThrow()));
            }
            for (var method : type.callables()) {
                if (method.owner().equals("ironwood.lang.Object")) continue;
                String descriptor = "(" + method.parameters().stream().map(BridgeJavaTypes::descriptor)
                        .collect(java.util.stream.Collectors.joining()) + ")" + BridgeJavaTypes.descriptor(method.result());
                String key = prefix + ".method." + method.name() + descriptor;
                values.put(key + ".kind", method.kind().name());
                values.put(key + ".static", Boolean.toString(method.isStatic()));
                indexed(values, key + ".parameters", method.parameterNames());
                indexed(values, key + ".throws", method.thrownTypes().stream().map(BridgeJavaTypes::descriptor).toList());
            }
        }
        return values;
    }

    private static String constant(IrOperand value) {
        if (value instanceof IrStringConstant string) return "utf16:" + string.value();
        if (value instanceof IrConstant number) {
            if (number.value() instanceof Float real) return "float:" + Integer.toHexString(Float.floatToRawIntBits(real));
            if (number.value() instanceof Double real) return "double:" + Long.toHexString(Double.doubleToRawLongBits(real));
            return "integer:" + number.value();
        }
        throw new IllegalArgumentException("unsupported Java API constant: " + value);
    }

    private static void indexed(Map<String, String> values, String prefix, List<String> items) {
        values.put(prefix + ".count", Integer.toString(items.size()));
        for (int index = 0; index < items.size(); index++) values.put(prefix + "." + index, items.get(index));
    }

    private static String digest(Map<String, String> values) {
        var digest = sha256();
        update(digest, "ironwood-java-bridge-identity-v1");
        new TreeMap<>(values).forEach((key, value) -> {
            update(digest, key);
            update(digest, value);
        });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length()).array());
        // UTF-8 replacement would alias distinct unpaired-surrogate constants.
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            digest.update((byte) (character >>> 8));
            digest.update((byte) character);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void requireText(String text, String role) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("missing " + role);
    }

    private static void requireHash(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("expected SHA-256 input identity");
    }
}
