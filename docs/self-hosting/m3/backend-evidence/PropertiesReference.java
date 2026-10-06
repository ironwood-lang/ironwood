// SPDX-License-Identifier: MIT OR Apache-2.0

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;

/**
 * Java 21 reference for integration-tests/cases/compiler_properties_text.iron,
 * with the corpus files as arguments. Each file is read as TlsDependency.read
 * and BridgeNativeSupport.read do (Files.newBufferedReader and
 * Properties.load); a text with a backslash is outside the admitted format
 * and reported as rejected. For each file: the size, every entry in sorted
 * key order, containsKey probes, Properties.equals with itself and with the
 * next admitted file in both directions.
 */
public final class PropertiesReference {
    private static final StringBuilder OUT = new StringBuilder();
    static final List<String> PROBES = List.of("format", "platform", "missing", "");

    private PropertiesReference() { }

    static Properties read(Path path) throws Exception {
        Properties result = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            result.load(reader);
        }
        return result;
    }

    static boolean admitted(Path path) throws Exception {
        return Files.readString(path).indexOf('\\') < 0;
    }

    public static void main(String[] args) throws Exception {
        for (int index = 0; index < args.length; index++) {
            Path path = Path.of(args[index]);
            if (!admitted(path)) {
                OUT.append("file ").append(index).append(" rejected\n");
                continue;
            }
            Properties properties = read(path);
            OUT.append("file ").append(index).append(" size ").append(properties.size()).append('\n');
            for (String key : new TreeSet<>(properties.stringPropertyNames())) {
                OUT.append("entry ").append(key).append('=').append(properties.getProperty(key)).append("|\n");
            }
            for (String probe : PROBES) OUT.append("contains ").append(probe).append(' ')
                    .append(properties.containsKey(probe)).append('\n');
            OUT.append("self ").append(properties.equals(read(path))).append('\n');
            if (index + 1 < args.length && admitted(Path.of(args[index + 1]))) {
                Properties next = read(Path.of(args[index + 1]));
                OUT.append("next ").append(properties.equals(next)).append(' ').append(next.equals(properties))
                        .append('\n');
            }
        }
        System.out.print(OUT);
    }
}
