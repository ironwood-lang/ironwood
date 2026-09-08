// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.ast.ClassDeclaration;
import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.ast.TypeName;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class IronClass {
    static final String EXTENSION = ".ironclass";

    private static final String MANIFEST = "META-INF/IRONWOOD.MF";
    private static final String TYPE_INDEX = "META-INF/types.tsv";
    private static final String ENTRY_POINT = "META-INF/entry-point";
    private static final String SOURCE_DIRECTORY = "source/";
    private static final String FORMAT = "Ironwood-Class-Format: 1\n";

    private final String path;
    private final Map<String, String> types;
    private final Optional<String> entryPoint;
    private final String sourceEntry;
    private final String source;

    private IronClass(String path, Map<String, String> types, Optional<String> entryPoint,
                      String sourceEntry, String source) {
        this.path = path;
        this.types = Map.copyOf(types);
        this.entryPoint = entryPoint;
        this.sourceEntry = sourceEntry;
        this.source = source;
    }

    Optional<SourceFile> source(String canonicalType) {
        if (!types.containsKey(canonicalType)) {
            return Optional.empty();
        }
        return Optional.of(SourceFile.of(path + "!/" + sourceEntry, source));
    }

    Optional<String> entryPoint() {
        return entryPoint;
    }

    Set<String> declaredTypes() {
        return types.keySet();
    }

    static IronClass read(Path artifact) throws IOException {
        Path absolute = artifact.toAbsolutePath().normalize();
        return read(Files.readAllBytes(absolute), absolute.toString());
    }

    static IronClass read(byte[] artifact, String displayPath) throws IOException {
        Map<String, byte[]> entries = readEntries(artifact, displayPath);
        byte[] manifest = entries.get(MANIFEST);
        if (manifest == null || !decode(manifest).equals(FORMAT)) {
            throw new IOException("unsupported or missing Ironwood class manifest in " + displayPath);
        }
        byte[] index = entries.get(TYPE_INDEX);
        if (index == null) {
            throw new IOException("missing " + TYPE_INDEX + " in " + displayPath);
        }
        List<String> sourceEntries = entries.keySet().stream()
                .filter(name -> name.startsWith(SOURCE_DIRECTORY))
                .filter(name -> name.endsWith(".iron")).toList();
        if (sourceEntries.size() != 1) {
            throw new IOException("Ironwood class must contain exactly one source entry: " + displayPath);
        }
        Map<String, String> types = new LinkedHashMap<>();
        for (String line : decode(index).lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\\t", -1);
            if (fields.length != 2 || fields[0].isBlank() || fields[1].isBlank()) {
                throw new IOException("invalid type index entry in " + displayPath + ": " + line);
            }
            if (!fields[1].equals("class") && !fields[1].equals("interface")) {
                throw new IOException("invalid type kind in " + displayPath + ": " + fields[1]);
            }
            String previous = types.putIfAbsent(fields[0], fields[1]);
            if (previous != null) {
                throw new IOException("duplicate class type '" + fields[0] + "' in " + displayPath);
            }
        }
        if (types.isEmpty()) {
            throw new IOException("Ironwood class contains no declared types: " + displayPath);
        }
        Optional<String> entryPoint = Optional.empty();
        byte[] entryPointBytes = entries.get(ENTRY_POINT);
        if (entryPointBytes != null) {
            String entryPointName = decode(entryPointBytes).trim();
            if (!entryPointName.isEmpty()) {
                entryPoint = Optional.of(entryPointName);
            }
        }
        if (entryPoint.isPresent() && !types.containsKey(entryPoint.orElseThrow())) {
            throw new IOException("Ironwood class entry point is not a declared type: "
                    + entryPoint.orElseThrow());
        }
        String sourceEntry = sourceEntries.getFirst();
        return new IronClass(displayPath, types, entryPoint,
                sourceEntry, decode(entries.get(sourceEntry)));
    }

    static void write(Path artifact, CompilationUnit unit, String artifactType) throws IOException {
        Path absolute = artifact.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        StringBuilder index = new StringBuilder();
        var artifactDeclaration = DeclaredTypes.in(unit).stream()
                .filter(declared -> declared.binaryName().equals(artifactType))
                .findFirst().orElseThrow(() -> new IOException("compilation unit does not declare artifact type '"
                        + artifactType + "'"));
        index.append(artifactType).append('\t')
                .append(artifactDeclaration.declaration()
                        instanceof ironwood.compiler.ast.InterfaceDeclaration
                        ? "interface" : "class")
                .append('\n');
        Optional<String> entryPoint = DeclaredTypes.in(unit).stream()
                .filter(declared -> declared.declaration() instanceof ClassDeclaration)
                .filter(declared -> declared.binaryName().equals(artifactType))
                .filter(declared -> ((ClassDeclaration) declared.declaration()).methods().stream().anyMatch(method ->
                        method.name().equals("main")
                                && method.accessModifier() == AccessModifier.PUBLIC
                                && method.isStatic()
                                && (method.returnType().kind() == TypeName.Kind.INT
                                    || method.returnType().kind() == TypeName.Kind.VOID)
                                && method.parameters().size() == 1
                                && isStringArray(method.parameters().getFirst().type())))
                .map(declared -> declared.binaryName())
                .findFirst();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(absolute))) {
            write(zip, MANIFEST, FORMAT);
            write(zip, TYPE_INDEX, index.toString());
            if (entryPoint.isPresent()) {
                write(zip, ENTRY_POINT, entryPoint.orElseThrow() + "\n");
            }
            write(zip, SOURCE_DIRECTORY + unit.source().path().getFileName(), unit.source().content());
        }
    }

    private static String canonicalName(CompilationUnit unit, String simpleName) {
        return unit.packageName().isEmpty() ? simpleName : unit.packageName() + "." + simpleName;
    }

    private static boolean isStringArray(TypeName type) {
        if (type.kind() != TypeName.Kind.ARRAY
                || type.elementType().kind() != TypeName.Kind.REFERENCE) {
            return false;
        }
        String elementName = type.elementType().referenceName();
        return elementName.equals("String") || elementName.equals("ironwood.lang.String");
    }

    private static Map<String, byte[]> readEntries(byte[] artifact, String displayPath)
            throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(artifact))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                if (!names.add(entry.getName())) {
                    throw new IOException("duplicate entry '" + entry.getName() + "' in " + displayPath);
                }
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                zip.transferTo(content);
                entries.put(entry.getName(), content.toByteArray());
                zip.closeEntry();
            }
        }
        return entries;
    }

    private static String decode(byte[] content) {
        return new String(content, StandardCharsets.UTF_8);
    }

    private static void write(ZipOutputStream zip, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
