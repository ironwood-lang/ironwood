// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.doc.IronDoc;
import ironwood.compiler.lexer.Lexer;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.regex.Pattern;

public final class IronDocTests {
    private IronDocTests() {}

    public static void main(String[] args) throws Exception {
        runAll();
        System.out.println("PASS: IronDocs comment, model, CLI, link, and generation checks");
    }

    public static void runAll() throws Exception {
        Path root = Files.createTempDirectory("irondocs tests ");
        try {
            trivia();
            declarations(root);
            supertypeLinks(root);
            allocationFailureDocumentation(root);
            discovery(root);
            diagnostics(root);
            arguments(root);
            repositoryDocumentation(root);
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void trivia() {
        String source = "/** Type */ public class Sample { String value = \"/** fake */\";\n"
                + "String raw = r\"\"\"\n/** fake raw */\n\"\"\";\n"
                + "String cooked = \"\"\"\n/** fake cooked */\n\"\"\";\n"
                + "// /** fake line */\n/** Method */ public int value() { return 1; } }";
        var file = SourceFile.of("Sample.iron", source);
        var ordinary = new Lexer(file);
        var retained = new Lexer(file, true);
        check(ordinary.lex().equals(retained.lex()), "documentation must not change compiler tokens or diagnostics");
        check(ordinary.documentationComments().isEmpty(), "compiler should not retain documentation");
        check(retained.documentationComments().size() == 2, "ignore comment markers in literals and comments");
        var broken = new Lexer(SourceFile.of("Broken.iron", "/** never closed"), true).lex();
        check(broken.diagnostics().getFirst().message().contains("unterminated"), "unterminated documentation diagnostics");
    }

    private static void declarations(Path root) throws Exception {
        Path input = write(root, "model/p/Widget.iron", """
                package p;
                /** Widget stores {@link #read(int) values. More values}.
                 * <p>A paragraph with {@code x < y | z}, {@literal <T> *raw*}, and {@link #read(int) read by index}.
                 * The literal tag {@code <pre>} does not open an HTML block.
                 * <pre>{@code
                 * if (x) { use(); }
                 * @Override
                 * }</pre>
                 * <p>Follow the sequence:
                 * <ol><li>Take a value.</li><li>Return it.</li></ol>
                 * @param <E> element type
                 * @since 1.0
                 * @author The author
                 * @version 2
                 */
                public class Widget<E> {
                    /** A constant. */ public static final String LABEL = "two  spaces";
                    private int hidden;
                    int internal;
                    protected int visible;
                    /** Construct a widget.
                     * @param count initial count
                     */
                    public Widget(int count) {}
                    /** First read.
                     * @param index the index | position
                     * @return the value
                     * @throws IllegalArgumentException for an invalid index
                     * @see #read()
                     */
                    public E read(int index) { return null; }
                    /** Read without an index.
                     * @return the value
                     * @deprecated Use {@link #read(int)}.
                     */
                    public E read() { return null; }
                    /** Old description. */ /** Correct description. */
                    /* ordinary trivia */ public void latest() {}
                    public static class Nested { public void nested() {} }
                    private static class Secret { public void secret() {} }
                }
                public interface Contract {
                    /** Return a value.
                     * @return the value
                     */
                    int value();
                }
                public enum Choice { /** First choice. */ FIRST, SECOND; }
                """);
        Path output = root.resolve("model-out");
        success("-quiet", "-d", output.toString(), input.toString());
        String page = Files.readString(output.resolve("p/Widget.md"));
        check(page.contains("public class Widget<E>"), "generic declaration");
        check(Files.readString(output.resolve("README.md")).contains("[`values. More values`](p/Widget.md#"),
                "summary must preserve inline link labels containing sentence punctuation");
        check(page.contains("1. Take a value.") && page.contains("1. Return it."), "ordered lists");
        check(page.contains("protected int visible") && !page.contains("int internal") && !page.contains("int hidden"), "default visibility");
        check(page.contains("two  spaces"), "preserve initializer string whitespace");
        check(page.contains("Correct description.") && !page.contains("Old description."), "nearest doc comment wins");
        check(page.contains("&lt;T&gt;") && page.contains("\\*raw\\*"), "literal escaping");
        check(page.contains("`<pre>`"), "HTML inside an inline code tag remains literal");
        check(page.contains("x < y | z") && page.contains("index \\| position"), "Markdown table pipes");
        check(page.contains("if (x) { use(); }") && page.contains("@Override"), "preformatted code and nested braces");
        check(page.contains("> [!WARNING]") && page.contains("**Since**"), "metadata and deprecation");
        check(!page.contains("The author") && !page.contains("**Version**"), "author and version opt-in");
        check(Files.exists(output.resolve("p/Widget.Nested.md")) && !Files.exists(output.resolve("p/Widget.Secret.md")), "nested visibility");
        check(Files.readString(output.resolve("p/Contract.md")).contains("public int value()"), "implicit public interface member");
        check(Files.readString(output.resolve("p/Choice.md")).contains("First choice."), "enum constants");
        checkLinks(output);
        var first = snapshot(output);
        success("-quiet", "-d", output.toString(), input.toString());
        check(first.equals(snapshot(output)), "deterministic regeneration");
        success("-private", "-author", "-version", "-d", root.resolve("private-out").toString(), input.toString());
        String privatePage = Files.readString(root.resolve("private-out/p/Widget.md"));
        check(privatePage.contains("int hidden") && privatePage.contains("int internal"), "private visibility");
        check(privatePage.contains("The author") && privatePage.contains("**Version**"), "metadata flags");
        success("-public", "-d", root.resolve("public-out").toString(), input.toString());
        check(!Files.readString(root.resolve("public-out/p/Widget.md")).contains("int visible"), "public visibility");
        success("-package", "-d", root.resolve("package-out").toString(), input.toString());
        String packagePage = Files.readString(root.resolve("package-out/p/Widget.md"));
        check(packagePage.contains("int internal") && !packagePage.contains("int hidden"), "package visibility");
    }

    private static void supertypeLinks(Path root) throws Exception {
        Path sources = root.resolve("supertypes");
        write(sources, "base/Base.iron", "package base; public class Base<E> {}");
        write(sources, "base/Root.iron", "package base; public interface Root<E> {}");
        write(sources, "base/Tag.iron", "package base; public interface Tag {}");
        write(sources, "base/Parent.iron", "package base; public interface Parent<E> extends Root<E>, Tag {}");
        Path child = write(sources, "api/Child.iron", """
                package api;
                import base.Base;
                import base.Parent;
                public class Child<E> extends Base<E> implements Parent<E>, base.Tag, external.Missing {}
                """);
        write(sources, "api/Choice.iron", "package api; public enum Choice implements base.Tag { FIRST; }");
        Path output = root.resolve("supertypes-out");
        success("-quiet", "-sourcepath", sources.toString(), "-subpackages", "base:api", "-d", output.toString());
        String page = Files.readString(output.resolve("api/Child.md"));
        check(page.contains("**Extends:** [`Base<E>`](../base/Base.md)"), "imported generic superclass link");
        check(page.contains("**Implements:** [`Parent<E>`](../base/Parent.md), [`base.Tag`](../base/Tag.md), `external.Missing`"),
                "implemented interfaces keep generic labels, qualified names, and readable unselected types");
        check(Files.readString(output.resolve("base/Parent.md"))
                .contains("**Extends:** [`Root<E>`](Root.md), [`Tag`](Tag.md)"), "multiple parent interface links");
        check(Files.readString(output.resolve("api/Choice.md")).contains("**Implements:** [`base.Tag`](../base/Tag.md)"),
                "enum interface link");
        String rootPage = Files.readString(output.resolve("base/Root.md"));
        check(!rootPage.contains("**Extends:**") && !rootPage.contains("**Implements:**"), "omit empty relationship labels");
        checkLinks(output);
        Path partial = root.resolve("supertypes-partial");
        success("-quiet", "-d", partial.toString(), child.toString());
        String partialPage = Files.readString(partial.resolve("api/Child.md"));
        check(partialPage.contains("**Extends:** `Base<E>`")
                && partialPage.contains("**Implements:** `Parent<E>`, `base.Tag`, `external.Missing`"),
                "partial generation retains readable relationships without broken links");
        checkLinks(partial);
    }

    private static void allocationFailureDocumentation(Path root) throws Exception {
        Path sources = root.resolve("allocation-errors");
        Path factory = write(sources, "api/Factory.iron", """
                package api;
                public class Factory {
                    /** Construct a factory.
                     * @throws OutOfMemoryError constructor allocation failure
                     */
                    public Factory() {}
                    /** Create a value.
                     * @throws OutOfMemoryError short allocation failure
                     * @exception ironwood.lang.OutOfMemoryError qualified allocation failure
                     */
                    public Object create() { return null; }
                }
                """);
        write(sources, "api/Checked.iron", """
                package api;
                public class Checked {
                    /** Read a value.
                     * @throws OutOfMemoryError allocation failure
                     * @throws IllegalArgumentException invalid argument
                     * @exception IllegalStateException invalid state
                     * @throws IOException read failure
                     */
                    public Object read() throws IOException { return null; }
                }
                """);
        write(sources, "ironwood/lang/OutOfMemoryError.iron", """
                package ironwood.lang;
                /** Signals allocation failure. */
                public class OutOfMemoryError extends Error {}
                """);
        write(sources, "custom/OutOfMemoryError.iron", "package custom; public class OutOfMemoryError extends Error {}");
        Path custom = write(sources, "other/Custom.iron", """
                package other;
                import custom.OutOfMemoryError;
                public class Custom {
                    /** Report a custom condition.
                     * @throws OutOfMemoryError custom condition
                     */
                    public void run() {}
                }
                """);
        Path output = root.resolve("allocation-errors-out");
        success("-quiet", "-sourcepath", sources.toString(), "-subpackages", "api:ironwood:custom:other", "-d", output.toString());
        String factoryPage = Files.readString(output.resolve("api/Factory.md"));
        check(!factoryPage.contains("OutOfMemoryError") && !factoryPage.contains("**Throws**"),
                "allocation-only constructor and method tags must not leave empty throws tables");
        String mixed = Files.readString(output.resolve("api/Checked.md"));
        check(!mixed.contains("OutOfMemoryError") && mixed.contains("**Throws**")
                && mixed.contains("IllegalArgumentException") && mixed.contains("IllegalStateException")
                && mixed.contains("IOException") && mixed.contains("read failure"),
                "method-specific checked and unchecked failures remain documented");
        check(Files.readString(output.resolve("ironwood/lang/OutOfMemoryError.md")).contains("Signals allocation failure"),
                "the error type keeps its own reference page");
        check(Files.readString(output.resolve("other/Custom.md")).contains("**Throws**"),
                "a distinct imported error with the same simple name remains documented");
        checkLinks(output);
        Path partial = root.resolve("allocation-errors-partial");
        success("-quiet", "-d", partial.toString(), factory.toString(), custom.toString());
        check(!Files.readString(partial.resolve("api/Factory.md")).contains("**Throws**"),
                "implicit allocation errors are omitted when their type page is not selected");
        check(Files.readString(partial.resolve("other/Custom.md")).contains("**Throws**"),
                "an explicit custom import remains documented without its type page");
    }

    private static void discovery(Path root) throws Exception {
        Path sources = root.resolve("discovery");
        write(sources, "p/A.iron", "package p; /** See {@link q.B}. */ public class A { public void accept(q.B value) {} }");
        write(sources, "p/sub/Child.iron", "package p.sub; public class Child {}");
        write(sources, "p/skip/Skip.iron", "package p.skip; public class Skip {}");
        write(sources, "p/Wrong.iron", "package elsewhere; public class Wrong {}");
        write(sources, "q/B.iron", "package q; import p.A; /** See {@link A#accept(q.B)}. */ public class B {}");
        Path output = root.resolve("discovered");
        success("-sourcepath", sources.toString(), "-d", output.toString(), "-subpackages", "p:q", "-exclude", "p.skip");
        check(Files.exists(output.resolve("p/sub/Child.md")) && !Files.exists(output.resolve("p/skip/Skip.md")), "recursive exclusion");
        check(!Files.exists(output.resolve("elsewhere/Wrong.md")), "ignore mismatched packages");
        checkLinks(output);
        Path all = root.resolve("sourcepath-all");
        success("-quiet", "-sourcepath", sources.toString(), "-d", all.toString());
        check(Files.exists(all.resolve("p/A.md")) && Files.exists(all.resolve("p/sub/Child.md"))
                && Files.exists(all.resolve("p/skip/Skip.md")) && Files.exists(all.resolve("q/B.md"))
                && Files.exists(all.resolve("elsewhere/Wrong.md")), "sourcepath-only recursive discovery");
        checkLinks(all);
        success("--source-path=" + sources, "-d", root.resolve("direct-package").toString(), "p");
        check(!Files.exists(root.resolve("direct-package/p/sub/Child.md")), "package operand is nonrecursive");
        failure("no source files", "-sourcepath", sources.toString(), "missing");
        Path duplicate = write(root, "duplicate.iron", "package p; public class A {}");
        failure("duplicate documented type", "-sourcepath", sources.toString(), "p", duplicate.toString());
    }

    private static void diagnostics(Path root) throws Exception {
        String[][] malformed = {
                {"/** @unknown text */ public class Broken {}", "unsupported IronDocs tag"},
                {"/** {@inheritDoc} */ public class Broken {}", "unsupported IronDocs inline tag"},
                {"/** {@code unclosed */ public class Broken {}", "unterminated IronDocs inline"},
                {"/** <pre>unclosed */ public class Broken {}", "unterminated IronDocs <pre>"},
                {"/** <script>bad</script> */ public class Broken {}", "unsupported IronDocs HTML"},
                {"/** {@link #missing()} */ public class Broken {}", "unresolved or ambiguous"},
                {"public class Broken { /** @param wrong description */ public void go(int right) {} }", "no declared parameter"},
                {"public class Broken { /** @return invalid */ public void go() {} }", "non-void method"},
                {"public class Broken { /** @param x first\n * @param x second\n */ public void go(int x) {} }", "duplicate IronDocs @param"},
                {"public class Broken { /** @see #go */ public void go(int x) {} public void go() {} }", "ambiguous"},
                {"public class Broken {", "expected '}'"},
                {"/** <ol><li>missing end */ public class Broken {}", "unterminated IronDocs list"},
                {"public class README {}", "collides with the IronDocs index"}
        };
        for (int i = 0; i < malformed.length; i++) {
            Path input = write(root, "bad/" + i + "/Broken.iron", malformed[i][0]);
            Path output = root.resolve("bad-output-" + i);
            Result result = failure(malformed[i][1], "-d", output.toString(), input.toString());
            check(result.err.contains("Broken.iron:"), "source location in diagnostics");
            check(!Files.exists(output), "invalid inputs must not generate partial docs");
        }
        Path source = write(root, "guarded/Good.iron", "public class Good {}");
        Path output = root.resolve("guarded-out");
        write(output, "README.md", "User documentation");
        failure("non-generated", "-d", output.toString(), source.toString());
        check(Files.readString(output.resolve("README.md")).equals("User documentation"), "preserve hand-written output");
        Path symlink = root.resolve("symlink-out");
        Files.createSymbolicLink(symlink, output);
        failure("symbolic-link", "-d", symlink.toString(), source.toString());
    }

    private static void arguments(Path root) throws Exception {
        check(success("--help").out.contains("Usage: irondoc"), "help");
        check(success("--version").out.startsWith("irondoc "), "version");
        check(success("-v").out.equals(success("--version").out), "short version alias");
        failure("unsupported irondoc option", "-stylesheetfile", "style.css");
        failure("missing value", "-d");
        failure("no Ironwood source", "-quiet");
        failure("require UTF-8", "-encoding", "ISO-8859-1");
        failure("source file not found", "missing.iron");
        Path input = write(root, "arg source.iron", "/** UTF-8 café. */ public class Args {}");
        Path output = root.resolve("arg output");
        Path args = write(root, "args.txt", "# generated test options\n-quiet\n-d \"" + output
                + "\"\n-doctitle 'Library API'\n-encoding UTF-8\n\"" + input + "\"\n");
        check(success("@" + args).out.isEmpty(), "quiet argument file");
        check(Files.readString(output.resolve("Args.md")).contains("café"), "UTF-8 docs");
        check(!Files.readString(output.resolve("assets/irondocs.svg")).contains("Version "),
                "no implicit library version");
        Path versioned = root.resolve("versioned");
        success("-d", versioned.toString(), "-doctitle", "Library <API> & docs",
                "--doc-version", "0.1.3-beta & preview", input.toString());
        String banner = Files.readString(versioned.resolve("assets/irondocs.svg"));
        check(banner.contains("Library &lt;API&gt; &amp; docs"), "escape banner title");
        check(banner.contains("Version 0.1.3-beta &amp; preview"), "escape banner version");
        check(Files.readString(versioned.resolve("Args.md")).replace("\\", "").contains("**Version 0.1.3-beta"),
                "version remains visible without the banner image");
        failure("single-line", "--doc-version", "0.1.3\nbeta", input.toString());
        checkLinks(output);
        Path bad = write(root, "bad-args.txt", "-d 'missing");
        failure("unterminated quote", "@" + bad);
    }

    private static void repositoryDocumentation(Path root) throws Exception {
        Path output = root.resolve("stdlib-docs");
        String version = Files.readString(Path.of("VERSION")).strip();
        success("-d", output.toString(), "-doctitle", "Ironwood Standard Library", "--doc-version", version,
                "-sourcepath", "stdlib/src/main/ironwood", "-subpackages", "ironwood");
        // Development source can differ from a frozen release snapshot. Release CI
        // checks that snapshot with update-irondocs.sh --check against the tagged source.
        var first = snapshot(output);
        success("-quiet", "-d", output.toString(), "-doctitle", "Ironwood Standard Library", "--doc-version", version,
                "-sourcepath", "stdlib/src/main/ironwood", "-subpackages", "ironwood");
        check(snapshot(output).equals(first), "standard-library documentation generation must be reproducible");
        checkLinks(output);
        Path wholeLibrary = root.resolve("whole-stdlib");
        success("-quiet", "-d", wholeLibrary.toString(), "-sourcepath", "stdlib/src/main/ironwood", "-subpackages", "ironwood");
        check(Files.exists(wholeLibrary.resolve("ironwood/lang/String.md")), "standard library declaration coverage");
        check(Files.readString(wholeLibrary.resolve("ironwood/pool/ArrayObjectPool.md"))
                .contains("**Implements:** [`ObjectPool<E>`](ObjectPool.md)"), "pool links to its interface");
        checkLinks(wholeLibrary);
    }

    private static TreeMap<String, String> snapshot(Path root) throws Exception {
        TreeMap<String, String> result = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                result.put(root.relativize(path).toString(), Files.readString(path));
            }
        }
        return result;
    }

    private static void checkLinks(Path root) throws Exception {
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".md")).toList()) {
                String content = Files.readString(file);
                var matcher = Pattern.compile("\\]\\(([^)]+)\\)").matcher(content);
                while (matcher.find()) {
                    String[] parts = matcher.group(1).split("#", 2);
                    Path target = parts[0].isEmpty() ? file : file.getParent().resolve(parts[0]).normalize();
                    check(Files.isRegularFile(target), "broken Markdown file link: " + file + " -> " + matcher.group(1));
                    if (parts.length == 2) {
                        String targetText = Files.readString(target);
                        String anchor = parts[1];
                        check(targetText.contains("name=\"" + anchor + "\"")
                                || targetText.lines().anyMatch(line -> line.startsWith("## ")
                                && line.substring(3).toLowerCase(java.util.Locale.ROOT).replace(' ', '-').equals(anchor)),
                                "broken Markdown anchor: " + matcher.group(1));
                    }
                }
            }
        }
    }

    private record Result(int status, String out, String err) {}

    private static Result invoke(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = IronDoc.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Result success(String... args) {
        Result result = invoke(args);
        check(result.status == 0 && result.err.isEmpty(), "irondoc failed: " + result.err);
        return result;
    }

    private static Result failure(String message, String... args) {
        Result result = invoke(args);
        check(result.status != 0 && result.err.contains(message), "expected '" + message + "': " + result.err);
        return result;
    }

    private static Path write(Path root, String relative, String content) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
