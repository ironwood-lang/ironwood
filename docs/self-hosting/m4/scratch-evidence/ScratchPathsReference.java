// SPDX-License-Identifier: MIT OR Apache-2.0

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Java 21 reference for integration-tests/cases/stdlib_scratch_paths.iron:
 * the same cases through java.nio.file, printed in the same normalized form.
 * The harness runs it in the fixture root, with -Djava.io.tmpdir set to the
 * directory Ironwood selects for the "default" scenario.
 */
public final class ScratchPathsReference {
    private static String root;
    private static String realRoot;

    public static void main(String[] args) throws IOException {
        root = args[1];
        realRoot = Path.of(root).toRealPath().toString();
        if (args[0].equals("cases")) {
            temporaries();
            realPaths();
            access();
            noFollow();
            deletions();
        } else {
            defaults();
        }
    }

    private static void deletions() {
        for (String name : new String[]{"link-file", "file.txt", "file.txt", "missing", "missing/child",
                "notdir.txt/child", "full", "empty", "dangling", "locked/child.txt", "link-full"}) {
            StringBuilder line = new StringBuilder("deleteIfExists ").append(name).append(" -> ");
            try {
                line.append(Files.deleteIfExists(Path.of(name)));
            } catch (IOException failure) {
                failed(line, failure, true);
            }
            System.out.println(line);
        }
        System.out.println("exists full/inner.txt -> " + Files.exists(Path.of("full/inner.txt")));
    }

    private static void temporaries() {
        String[][] files = {{"tmp", "pre", ".suf"}, {"tmp", null, null}, {"tmp", "", ""}, {"tmp", "🌲 x", " y"},
                {"tmp", "a", "b/"}, {"tmp", "a", "/"}, {"tmp", "a", "./"}, {"tmp", "X", "X"}, {"tmp", "a/b", null},
                {"tmp", "a", "/b"}, {"tmp", "/", null}, {"tmp", "a@", null}, {"tmp", "a", "@"}, {"tmp", "a@/", null},
                {"missing", "a", null}, {"notdir.txt", "a", null}, {"ro", "a", null}, {"locked", "a", null}, {"dangling", "a", null},
                {"link-full", "via", null}, {"", "rel", null}};
        for (String[] item : files) {
            StringBuilder line = new StringBuilder("createTempFile ").append(item[0]).append(' ')
                    .append(item[1] == null ? "<null>" : item[1]).append(' ')
                    .append(item[2] == null ? "<null>" : item[2]).append(" -> ");
            try {
                Path created = Files.createTempFile(Path.of(item[0]), nul(item[1]), nul(item[2]));
                line.append(show(created.toString())).append(Files.isRegularFile(created) ? " file" : " other");
            } catch (IOException | IllegalArgumentException failure) {
                failed(line, failure, false);
            }
            System.out.println(line);
        }
        String[][] directories = {{"tmp", "d"}, {"tmp", null}, {"tmp", "🌲 d"}, {"tmp", "x/"}, {"tmp", "x@"},
                {"missing", "d"}, {"ro", "d"}, {"", "reld"}};
        for (String[] item : directories) {
            StringBuilder line = new StringBuilder("createTempDirectory ").append(item[0]).append(' ')
                    .append(item[1] == null ? "<null>" : item[1]).append(" -> ");
            try {
                Path created = Files.createTempDirectory(Path.of(item[0]), nul(item[1]));
                line.append(show(created.toString())).append(Files.isDirectory(created) ? " directory" : " other");
            } catch (IOException | IllegalArgumentException failure) {
                failed(line, failure, false);
            }
            System.out.println(line);
        }
    }

    private static void realPaths() {
        for (String name : new String[]{"nested/x", "link-file", "link-full/inner.txt", "link-deep/../x",
                "nested/../link-file", "nested/./deep/..", "🌲 space", "", ".", "..", root, "dangling", "missing",
                "loop-a", "notdir.txt/x", "locked/child.txt"}) {
            StringBuilder line = new StringBuilder("toRealPath ").append(show(name)).append(" -> ");
            try {
                line.append(show(Path.of(name).toRealPath().toString()));
            } catch (IOException failure) {
                failed(line, failure, true);
            }
            System.out.println(line);
        }
    }

    private static void access() {
        for (String name : new String[]{"nested/x", "exec.sh", "secret.txt", "nested", "locked", "ro", "link-exec",
                "dangling", "missing", "locked/child.txt", "🌲 space"}) {
            Path path = Path.of(name);
            System.out.println("access " + name + " -> " + (Files.isReadable(path) ? 'r' : '-')
                    + (Files.isExecutable(path) ? 'x' : '-'));
        }
    }

    private static void noFollow() {
        for (String name : new String[]{"link-exec", "nested/x", "nested", "loop-a", "dangling", "missing"}) {
            StringBuilder line = new StringBuilder("readAttributesNoFollow ").append(name).append(" -> ");
            try {
                BasicFileAttributes attributes = Files.readAttributes(Path.of(name), BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                line.append(attributes.isSymbolicLink() ? "link" : attributes.isDirectory() ? "directory"
                        : attributes.isRegularFile() ? "file" : "other");
            } catch (IOException failure) {
                failed(line, failure, true);
            }
            System.out.println(line);
        }
    }

    private static void defaults() {
        StringBuilder line = new StringBuilder("default ");
        try {
            Path expected = Path.of(System.getProperty("java.io.tmpdir"));
            Path file = Files.createTempFile("m4-default-", null);
            Path directory = Files.createTempDirectory(null);
            line.append(file.getParent().equals(expected) && directory.getParent().equals(expected)
                    ? "in-tmpdir " : "elsewhere ").append(show(file.toString())).append(' ')
                    .append(show(directory.toString()));
            Files.delete(file);
            Files.delete(directory);
        } catch (IOException failure) {
            failed(line, failure, false);
        }
        System.out.println(line);
    }

    private static void failed(StringBuilder line, Exception failure, boolean withFile) {
        line.append('!').append(kind(failure));
        if (withFile && failure instanceof FileSystemException system) {
            line.append(' ').append(system.getFile() == null ? "<null>" : show(system.getFile()));
        }
    }

    private static String kind(Exception failure) {
        if (failure instanceof NoSuchFileException) return "NoSuchFileException";
        if (failure instanceof AccessDeniedException) return "AccessDeniedException";
        if (failure instanceof FileAlreadyExistsException) return "FileAlreadyExistsException";
        if (failure instanceof DirectoryNotEmptyException) return "DirectoryNotEmptyException";
        if (failure instanceof FileSystemException) return "FileSystemException";
        if (failure instanceof InvalidPathException) return "InvalidPathException";
        if (failure instanceof IllegalArgumentException) return "IllegalArgumentException";
        if (failure instanceof IOException) return "IOException";
        return "Exception";
    }

    private static boolean under(String path, String base) {
        return path.startsWith(base) && (path.length() == base.length() || path.charAt(base.length()) == '/');
    }

    private static String show(String path) {
        StringBuilder text = new StringBuilder();
        int index = 0;
        if (under(path, realRoot)) {
            text.append("<real>");
            index = realRoot.length();
        } else if (under(path, root)) {
            text.append("<root>");
            index = root.length();
        }
        while (index < path.length()) {
            int end = index;
            while (end < path.length() && path.charAt(end) >= '0' && path.charAt(end) <= '9') end++;
            if (end - index >= 10) {
                text.append("<n>");
                index = end;
            } else if (end > index) {
                text.append(path, index, end);
                index = end;
            } else {
                text.append(path.charAt(index++));
            }
        }
        return text.toString();
    }

    private static String nul(String text) {
        return text == null ? null : text.replace('@', (char) 0);
    }
}
