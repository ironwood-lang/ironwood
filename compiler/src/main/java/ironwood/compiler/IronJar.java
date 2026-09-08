// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class IronJar {
    static final String EXTENSION = ".ironjar";

    private static final String MANIFEST = "META-INF/IRONWOOD.MF";
    private static final String TYPE_INDEX = "META-INF/types.tsv";
    private static final String LICENSE_DIRECTORY = "META-INF/LICENSES/";
    private static final String FORMAT = "Ironwood-Jar-Format: 1\n";

    private final Path path;
    private final Map<String, String> typeEntries;
    private final List<String> entries;

    private IronJar(Path path, Map<String, String> typeEntries, List<String> entries) {
        this.path = path;
        this.typeEntries = Map.copyOf(typeEntries);
        this.entries = List.copyOf(entries);
    }

    static void create(Path artifact, List<Path> inputs) throws IOException {
        create(artifact, inputs, List.of());
    }

    static void create(Path artifact, List<Path> inputs, List<Path> licenses) throws IOException {
        if (inputs.isEmpty()) {
            throw new IOException("expected at least one .ironclass file or class directory");
        }
        TreeMap<String, ClassPayload> payloads = new TreeMap<>();
        for (Path input : inputs) {
            collect(input.toAbsolutePath().normalize(), payloads);
        }
        if (payloads.isEmpty()) {
            throw new IOException("archive inputs contain no .ironclass files");
        }

        StringBuilder index = new StringBuilder();
        TreeMap<String, byte[]> archiveEntries = new TreeMap<>();
        archiveEntries.put(MANIFEST, FORMAT.getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, ClassPayload> payload : payloads.entrySet()) {
            index.append(payload.getKey()).append('\t')
                    .append(payload.getValue().entryName()).append('\n');
            byte[] previous = archiveEntries.put(payload.getValue().entryName(),
                    payload.getValue().content());
            if (previous != null) {
                throw new IOException("duplicate archive entry '"
                        + payload.getValue().entryName() + "'");
            }
        }
        archiveEntries.put(TYPE_INDEX, index.toString().getBytes(StandardCharsets.UTF_8));
        for (Path license : licenses.stream().map(path -> path.toAbsolutePath().normalize())
                .sorted().toList()) {
            if (!Files.isRegularFile(license)) {
                throw new IOException("license input does not exist or is not a regular file: " + license);
            }
            String entryName = LICENSE_DIRECTORY + license.getFileName();
            validateEntryPath(entryName, license);
            if (entryName.endsWith(IronClass.EXTENSION) || entryName.endsWith(EXTENSION)) {
                throw new IOException("license metadata cannot use an Ironwood artifact extension: "
                        + license);
            }
            byte[] previous = archiveEntries.put(entryName, Files.readAllBytes(license));
            if (previous != null) {
                throw new IOException("duplicate archive entry '" + entryName + "'");
            }
        }
        write(artifact, archiveEntries);
    }

    static IronJar read(Path artifact) throws IOException {
        Path absolute = artifact.toAbsolutePath().normalize();
        try (ZipFile zip = new ZipFile(absolute.toFile())) {
            List<? extends ZipEntry> physicalEntries = zip.stream().toList();
            List<String> names = new ArrayList<>();
            Set<String> uniqueNames = new HashSet<>();
            String previous = null;
            for (ZipEntry entry : physicalEntries) {
                String name = entry.getName();
                validateEntryPath(name, absolute);
                if (!uniqueNames.add(name)) {
                    throw new IOException("duplicate archive entry '" + name + "' in " + absolute);
                }
                if (previous != null && previous.compareTo(name) >= 0) {
                    throw new IOException("archive entries are not strictly sorted in " + absolute);
                }
                if (entry.isDirectory()) {
                    throw new IOException("directory entries are not permitted in " + absolute
                            + ": " + name);
                }
                if (name.endsWith(EXTENSION)) {
                    throw new IOException("nested Ironwood archive is not permitted in " + absolute
                            + ": " + name);
                }
                names.add(name);
                previous = name;
            }

            ZipEntry manifestEntry = zip.getEntry(MANIFEST);
            if (manifestEntry == null || !readText(zip, manifestEntry, absolute).equals(FORMAT)) {
                throw new IOException("unsupported or missing Ironwood archive manifest in " + absolute);
            }
            ZipEntry indexEntry = zip.getEntry(TYPE_INDEX);
            if (indexEntry == null) {
                throw new IOException("missing " + TYPE_INDEX + " in " + absolute);
            }
            Map<String, String> typeEntries = parseIndex(readText(zip, indexEntry, absolute), absolute);
            Set<String> indexedPaths = new HashSet<>(typeEntries.values());
            for (String name : names) {
                if (name.equals(MANIFEST) || name.equals(TYPE_INDEX)) {
                    continue;
                }
                if (name.startsWith(LICENSE_DIRECTORY)) {
                    if (name.endsWith(IronClass.EXTENSION)) {
                        throw new IOException("unindexed Ironwood class entry '" + name
                                + "' in " + absolute);
                    }
                    continue;
                }
                if (!name.endsWith(IronClass.EXTENSION)) {
                    throw new IOException("unexpected archive entry '" + name + "' in " + absolute);
                }
                if (!indexedPaths.contains(name)) {
                    throw new IOException("unindexed Ironwood class entry '" + name + "' in " + absolute);
                }
            }
            for (String indexedPath : indexedPaths) {
                ZipEntry entry = zip.getEntry(indexedPath);
                if (entry == null || entry.isDirectory()) {
                    throw new IOException("missing indexed Ironwood class entry '" + indexedPath
                            + "' in " + absolute);
                }
            }
            return new IronJar(absolute, typeEntries, names);
        }
    }

    Optional<SourceFile> source(String canonicalType) throws IOException {
        String entryName = typeEntries.get(canonicalType);
        if (entryName == null) {
            return Optional.empty();
        }
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IOException("missing indexed Ironwood class entry '" + entryName
                        + "' in " + path);
            }
            byte[] content = readBytes(zip, entry);
            IronClass ironClass = IronClass.read(content, path + "!/" + entryName);
            Optional<SourceFile> source = ironClass.source(canonicalType);
            if (source.isEmpty()) {
                throw new IOException("indexed Ironwood class entry '" + entryName
                        + "' does not declare type '" + canonicalType + "' in " + path);
            }
            return source;
        }
    }

    List<String> entries() {
        return entries;
    }

    Set<String> declaredTypes() {
        return typeEntries.keySet();
    }

    private static void collect(Path input, TreeMap<String, ClassPayload> payloads)
            throws IOException {
        if (Files.isDirectory(input)) {
            List<Path> classes;
            try (var paths = Files.walk(input)) {
                classes = paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(IronClass.EXTENSION))
                        .sorted().toList();
            }
            for (Path classFile : classes) {
                String relative = input.relativize(classFile).toString()
                        .replace(File.separatorChar, '/');
                String canonical = relative.substring(0,
                                relative.length() - IronClass.EXTENSION.length())
                        .replace('/', '.');
                addClass(classFile, canonical, payloads);
            }
            return;
        }
        if (!Files.isRegularFile(input)) {
            throw new IOException("archive input does not exist or is not a regular file: " + input);
        }
        if (input.getFileName().toString().endsWith(EXTENSION)) {
            throw new IOException("nested Ironwood archives are not accepted as inputs: " + input);
        }
        if (!input.getFileName().toString().endsWith(IronClass.EXTENSION)) {
            throw new IOException("archive input is not an .ironclass file: " + input);
        }
        IronClass ironClass = IronClass.read(input);
        String fileName = input.getFileName().toString();
        String simpleName = fileName.substring(0, fileName.length() - IronClass.EXTENSION.length());
        List<String> matches = ironClass.declaredTypes().stream()
                .filter(type -> simpleName(type).equals(simpleName)).sorted().toList();
        if (matches.size() != 1) {
            throw new IOException("cannot determine the canonical type for " + input
                    + "; its filename must match exactly one declared type");
        }
        addClass(input, matches.getFirst(), payloads);
    }

    private static void addClass(Path classFile, String canonical,
                                 TreeMap<String, ClassPayload> payloads) throws IOException {
        validateCanonicalType(canonical, classFile);
        IronClass ironClass = IronClass.read(classFile);
        if (!ironClass.declaredTypes().contains(canonical)) {
            throw new IOException("class path '" + classFile + "' implies type '" + canonical
                    + "', but the artifact does not declare it");
        }
        String entryName = canonical.replace('.', '/') + IronClass.EXTENSION;
        ClassPayload previous = payloads.putIfAbsent(canonical,
                new ClassPayload(entryName, Files.readAllBytes(classFile)));
        if (previous != null) {
            throw new IOException("duplicate archive type '" + canonical + "'");
        }
    }

    private static Map<String, String> parseIndex(String index, Path artifact) throws IOException {
        if (index.indexOf('\r') >= 0 || !index.endsWith("\n")) {
            throw new IOException("type index must use LF-terminated lines in " + artifact);
        }
        String body = index.substring(0, index.length() - 1);
        if (body.isEmpty()) {
            throw new IOException("Ironwood archive contains no indexed types: " + artifact);
        }
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> paths = new HashSet<>();
        String previous = null;
        for (String line : body.split("\n", -1)) {
            String[] fields = line.split("\\t", -1);
            if (fields.length != 2 || fields[0].isBlank() || fields[1].isBlank()) {
                throw new IOException("invalid type index entry in " + artifact + ": " + line);
            }
            String canonical = fields[0];
            String entryName = fields[1];
            validateCanonicalType(canonical, artifact);
            validateEntryPath(entryName, artifact);
            String expectedPath = canonical.replace('.', '/') + IronClass.EXTENSION;
            if (!entryName.equals(expectedPath)) {
                throw new IOException("type '" + canonical + "' must map to '" + expectedPath
                        + "' in " + artifact);
            }
            if (result.containsKey(canonical)) {
                throw new IOException("duplicate archive type '" + canonical + "' in " + artifact);
            }
            if (previous != null && previous.compareTo(canonical) >= 0) {
                throw new IOException("archive type index is not strictly sorted in " + artifact);
            }
            if (!paths.add(entryName)) {
                throw new IOException("duplicate indexed class path '" + entryName + "' in " + artifact);
            }
            result.put(canonical, entryName);
            previous = canonical;
        }
        return result;
    }

    private static void validateCanonicalType(String canonical, Path artifact) throws IOException {
        String[] parts = canonical.split("\\.", -1);
        for (String part : parts) {
            if (part.isEmpty() || !isIdentifierStart(part.charAt(0))) {
                throw new IOException("invalid canonical type '" + canonical + "' in " + artifact);
            }
            for (int index = 1; index < part.length(); index++) {
                if (!isIdentifierPart(part.charAt(index))) {
                    throw new IOException("invalid canonical type '" + canonical + "' in " + artifact);
                }
            }
        }
    }

    private static void validateEntryPath(String name, Path artifact) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0 || name.indexOf(':') >= 0) {
            throw new IOException("unsafe archive entry path '" + name + "' in " + artifact);
        }
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IOException("unsafe archive entry path '" + name + "' in " + artifact);
            }
        }
    }

    private static boolean isIdentifierStart(char value) {
        return value == '_' || value == '$'
                || value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z';
    }

    private static boolean isIdentifierPart(char value) {
        return isIdentifierStart(value) || value >= '0' && value <= '9';
    }

    private static String simpleName(String canonical) {
        int separator = canonical.lastIndexOf('.');
        return separator < 0 ? canonical : canonical.substring(separator + 1);
    }

    private static String readText(ZipFile zip, ZipEntry entry, Path artifact) throws IOException {
        byte[] content = readBytes(zip, entry);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("invalid UTF-8 in '" + entry.getName() + "' in " + artifact,
                    exception);
        }
    }

    private static byte[] readBytes(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream input = zip.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private static void write(Path artifact, Map<String, byte[]> entries) throws IOException {
        Path absolute = artifact.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(parent, ".ironjar-", ".tmp");
        boolean moved = false;
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                    byte[] content = item.getValue();
                    CRC32 crc = new CRC32();
                    crc.update(content);
                    ZipEntry entry = new ZipEntry(item.getKey());
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(content.length);
                    entry.setCompressedSize(content.length);
                    entry.setCrc(crc.getValue());
                    entry.setTime(0);
                    zip.putNextEntry(entry);
                    zip.write(content);
                    zip.closeEntry();
                }
            }
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private record ClassPayload(String entryName, byte[] content) {
        private ClassPayload {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
