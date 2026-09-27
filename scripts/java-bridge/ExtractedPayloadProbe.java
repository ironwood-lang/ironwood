// SPDX-License-Identifier: MIT OR Apache-2.0

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Private D210 fixture, compiled with a P1 scalar consumer into a test jar. */
public final class ExtractedPayloadProbe {
    public static void main(String[] arguments) throws Exception {
        String mode = arguments[0];
        String digest = arguments[1];
        Path destination = Path.of(arguments[2]).toAbsolutePath();
        if (mode.equals("extract")) {
            Path directory = Files.createTempDirectory(destination, "payload-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path pending = Files.createTempFile(directory, "pending-", ".dylib",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try (var input = ExtractedPayloadProbe.class.getResourceAsStream("/native/payload.dylib")) {
                if (input == null) throw new IllegalStateException("missing paired payload");
                Files.copy(input, pending, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.setPosixFilePermissions(pending, PosixFilePermissions.fromString("rw-------"));
            checkDigest(pending, digest);
            Path image = directory.resolve("payload-" + digest + ".dylib");
            Files.move(pending, image, StandardCopyOption.ATOMIC_MOVE);
            System.out.println(image);
        } else if (mode.equals("load")) {
            checkDigest(destination, digest);
            BridgeScalarConsumer.main(new String[]{destination.toString()});
        } else {
            throw new IllegalArgumentException("expected extract or load");
        }
    }

    private static void checkDigest(Path path, String expected) throws Exception {
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        if (!actual.equals(expected)) throw new IllegalStateException("payload SHA-256 mismatch");
    }
}
