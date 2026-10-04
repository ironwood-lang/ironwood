// SPDX-License-Identifier: MIT OR Apache-2.0
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Test-only model discovery independent of observed corpus and serializer dispatch. */
public final class ModelCapture {
    private ModelCapture() {}
    private static void describe(Class<?> type, List<String> rows) {
        if (type.isRecord()) {
            List<String> fields = new ArrayList<>();
            for (var field : type.getRecordComponents()) {
                fields.add(field.getName() + ":" + field.getGenericType().getTypeName());
            }
            rows.add("record\t" + type.getName() + "\t" + String.join(";", fields));
        } else if (type.isEnum()) {
            List<String> values = new ArrayList<>();
            for (Object value : type.getEnumConstants()) values.add(((Enum<?>)value).name());
            rows.add("enum\t" + type.getName() + "\t" + String.join(";", values));
        } else if (type.isSealed()) {
            List<String> variants = new ArrayList<>();
            for (Class<?> variant : type.getPermittedSubclasses()) variants.add(variant.getName());
            variants.sort(Comparator.naturalOrder());
            rows.add("sealed\t" + type.getName() + "\t" + String.join(";", variants));
        } else {
            List<String> fields = new ArrayList<>();
            for (var field : type.getDeclaredFields()) {
                fields.add(field.getName() + ":" + field.getGenericType().getTypeName()
                        + ":" + Modifier.toString(field.getModifiers()));
            }
            rows.add("class\t" + type.getName() + "\t" + String.join(";", fields));
        }
        for (Class<?> child : type.getDeclaredClasses()) describe(child, rows);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("class-name-list");
        List<String> rows = new ArrayList<>();
        for (String name : Files.readAllLines(Path.of(args[0]))) describe(Class.forName(name), rows);
        rows.sort(Comparator.naturalOrder());
        for (String row : rows) System.out.println(row);
    }
}
