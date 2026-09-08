// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.doc;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class IronDocOptions {
    Path output = Path.of(".");
    List<Path> sourcePath = List.of(Path.of("."));
    final List<String> inputs = new ArrayList<>();
    final List<String> subpackages = new ArrayList<>();
    final List<String> excludes = new ArrayList<>();
    String title = "Ironwood API";
    String apiVersion = "";
    int visibility = 1;
    boolean quiet;
    boolean help;
    boolean version;
    boolean author;
    boolean documentVersion;

    static IronDocOptions parse(String[] arguments) throws IOException {
        IronDocOptions result = new IronDocOptions();
        List<String> args = new ArrayList<>();
        for (String arg : arguments) {
            if (arg.startsWith("@")) args.addAll(argumentFile(Path.of(arg.substring(1))));
            else args.add(arg);
        }
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.startsWith("--") && arg.contains("=")) {
                int equals = arg.indexOf('=');
                args.add(i + 1, arg.substring(equals + 1));
                arg = arg.substring(0, equals);
            }
            switch (arg) {
                case "--help", "-help", "-h", "-?" -> result.help = true;
                case "--version", "-v" -> result.version = true;
                case "-quiet" -> result.quiet = true;
                case "-author" -> result.author = true;
                case "-version" -> result.documentVersion = true;
                case "-public" -> result.visibility = 0;
                case "-protected" -> result.visibility = 1;
                case "-package" -> result.visibility = 2;
                case "-private" -> result.visibility = 3;
                case "-d" -> result.output = Path.of(value(args, ++i, arg));
                case "-sourcepath", "--source-path" -> {
                    result.sourcePath = Pattern.compile(Pattern.quote(File.pathSeparator))
                            .splitAsStream(value(args, ++i, arg)).map(Path::of).toList();
                }
                case "-subpackages" -> result.subpackages.addAll(List.of(value(args, ++i, arg).split(":", -1)));
                case "-exclude" -> result.excludes.addAll(List.of(value(args, ++i, arg).split(":", -1)));
                case "-doctitle" -> result.title = value(args, ++i, arg);
                case "--doc-version" -> result.apiVersion = value(args, ++i, arg);
                case "-encoding", "-docencoding", "-charset" -> {
                    String encoding = value(args, ++i, arg);
                    if (!encoding.equalsIgnoreCase("UTF-8") && !encoding.equalsIgnoreCase("UTF8")) {
                        throw new IllegalArgumentException("Ironwood source and IronDocs output require UTF-8");
                    }
                }
                default -> {
                    if (arg.startsWith("-")) throw new IllegalArgumentException("unsupported irondoc option: " + arg);
                    if (arg.startsWith("@")) throw new IllegalArgumentException("nested argument files are unsupported: " + arg);
                    result.inputs.add(arg);
                }
            }
        }
        if (result.title.isBlank() || result.title.contains("\n") || result.title.contains("\r")) {
            throw new IllegalArgumentException("-doctitle requires a nonempty single-line plain-text title");
        }
        if (result.apiVersion.contains("\n") || result.apiVersion.contains("\r")) {
            throw new IllegalArgumentException("--doc-version requires a single-line plain-text version");
        }
        return result;
    }

    private static String value(List<String> args, int index, String option) {
        if (index >= args.size() || args.get(index).isBlank() || args.get(index).startsWith("-")) {
            throw new IllegalArgumentException("missing value for " + option);
        }
        return args.get(index);
    }

    private static List<String> argumentFile(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else if (c == '\\' && i + 1 < text.length()
                        && (text.charAt(i + 1) == quote || text.charAt(i + 1) == '\\')) token.append(text.charAt(++i));
                else token.append(c);
            } else if (c == '\'' || c == '"') { quote = c; started = true; }
            else if (c == '#' && !started) {
                while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') i++;
            } else if (Character.isWhitespace(c)) {
                if (started) { result.add(token.toString()); token.setLength(0); started = false; }
            } else { token.append(c); started = true; }
        }
        if (quote != 0) throw new IllegalArgumentException("unterminated quote in argument file " + path);
        if (started) result.add(token.toString());
        return result;
    }
}
