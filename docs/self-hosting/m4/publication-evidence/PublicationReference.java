// SPDX-License-Identifier: MIT OR Apache-2.0

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Java 21 reference for integration-tests/cases/stdlib_publication.iron:
 * "atomic" is {@code Files.move(source, target, ATOMIC_MOVE)}, as
 * BridgeJarArchive.publish moves; "replacing" is IronJar.write's atomic move
 * falling back to {@code REPLACE_EXISTING} when atomic movement is not
 * supported; "exclusive" is the default {@code Files.move}, Java's check
 * followed by a rename, which agrees wherever no creator competes and the
 * names are distinct files.
 */
public final class PublicationReference {
    public static void main(String[] args) {
        for (int index = 0; index + 2 < args.length; index += 3) {
            Path source = Path.of(args[index + 1]);
            Path target = Path.of(args[index + 2]);
            StringBuilder line = new StringBuilder().append(args[index]).append(' ').append(args[index + 1])
                    .append(' ').append(args[index + 2]).append(" -> ");
            try {
                Path moved = switch (args[index]) {
                    case "atomic" -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                    case "replacing" -> replacing(source, target);
                    default -> Files.move(source, target);
                };
                line.append(moved == target ? "ok" : "other path");
            } catch (IOException failure) {
                line.append('!').append(kind(failure));
            }
            System.out.println(line);
        }
    }

    private static Path replacing(Path source, Path target) throws IOException {
        try {
            return Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            return Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String kind(IOException failure) {
        if (failure instanceof AtomicMoveNotSupportedException) return "AtomicMoveNotSupportedException";
        if (failure instanceof NoSuchFileException) return "NoSuchFileException";
        if (failure instanceof AccessDeniedException) return "AccessDeniedException";
        if (failure instanceof FileAlreadyExistsException) return "FileAlreadyExistsException";
        if (failure instanceof DirectoryNotEmptyException) return "DirectoryNotEmptyException";
        if (failure instanceof FileSystemException) return "FileSystemException";
        return "IOException";
    }
}
