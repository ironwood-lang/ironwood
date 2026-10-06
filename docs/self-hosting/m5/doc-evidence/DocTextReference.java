// SPDX-License-Identifier: MIT OR Apache-2.0

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java 21 reference for integration-tests/cases/compiler_doc_text.iron. Run
 * with the compiler's classes on the class path: it writes the corpus to the
 * file named by its argument (one input per line, UTF-16 units in hex) and
 * prints, per input, what the documentation tools compute from it: the
 * baseline's own DocComment head, tail, entities, longestTicks and parse (tag
 * boundaries and diagnostics), IronDoc.validatePackage and
 * DocModel.Member.anchor, called by reflection, and the exact expressions of
 * the other call sites (\R and \s splits and replacements, the code-tag and
 * comment-star prefixes, stripLeading, regionMatches and the identifier-run
 * matcher). Finally it prints Character.isJavaIdentifierPart's ranges for
 * chars and code points. Text results are UTF-16 hex.
 */
public final class DocTextReference {
    private static Method head;
    private static Method tail;
    private static Method entities;
    private static Method longestTicks;
    private static Method parse;
    private static Method validatePackage;
    private static Constructor<?> member;
    private static Method anchor;
    private static Method tags;
    private static Method tagName;

    private DocTextReference() { }

    static String hex(String text) {
        StringBuilder out = new StringBuilder();
        for (char unit : text.toCharArray()) out.append(String.format("%04x", (int) unit));
        return out.length() == 0 ? "-" : out.toString();
    }

    static Object call(Method method, Object... arguments) throws Exception {
        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            throw (Exception) failure.getCause();
        }
    }

    static List<String> corpus() {
        List<String> inputs = new ArrayList<>(List.of("", " ", "\t", "\n", "\r\n", "\r", "\r\r\n\n", "a\u0085b", " ",
                " x", "\u000Bx\f", "  \n  ", "x \r\n y", "a\n\nb", " ", "　", "\u0085 ", " \u0085", "x  y",
                "<code>", "</code>", "<code></code>", "<code>x</code>", "</CODE>\u0085", "<CoDe>x</cOdE>", "x</code>\r\n",
                "x</code>\n", "x</code>\r", "x</code> ", "</code> ", "<code></code>\u0085", "<CODE>x</code>z",
                "&lt;&gt;&amp;&quot;&apos;&nbsp;", "&#65;&#x41;&#X41;&#x;&#;&#0;&#x10FFFF;", "&#x110000;", "&#xD800;",
                "&#2147483647;", "&#2147483648;", "&#xFFFFFFFF;", "&#x7FFFFFFF;", "&#00000000065;", "&#x0000000000041;",
                "&lt", "&&lt;;", "&nbsp", "&am;", "&#x1F600;", "&#55296;&#56320;", "&LT;", "&#x41", "x&#59;y",
                "   * text", "*text", " *  text", "\t* x", " ** x", "*", "x * y", " *", " * x", " * x",
                "``", "a`b``c```", "`", "a.b.c", "A1.b_2.$c", "1a", "a..b", "a.", ".a", "a$.b_", "é", "ab́",
                "  lead", " 　x", " x", "\u0085x", "x y\tz", "  x  y  ", "x\u000Cy", "p.Type#member(int,String)",
                "Map<String, List<T>>", "a.b.C#d(e.F)"));
        char[] alphabet = {'a', 'Z', '0', '_', '$', '.', ' ', '\t', '\n', '\r', '\u000B', '\f', '\u0085', ' ',
            ' ', ' ', '　', '*', '<', '>', '/', '&', '#', ';', 'x', 'c', 'o', 'd', 'e', 'C', 'O', 'D', 'E',
            '`', 'é', 'İ', 'ı', 'ſ', 'K', '\uD83D', '\uDE00', '\uD800', '-', 'l', 't', 'p', 'r',
            '1', '9', 'F'};
        Random random = new Random(20261006);
        for (int index = 0; index < 3000; index++) {
            char[] units = new char[random.nextInt(25)];
            for (int unit = 0; unit < units.length; unit++) units[unit] = alphabet[random.nextInt(alphabet.length)];
            inputs.add(new String(units));
        }
        return inputs;
    }

    // Tag-name suffixes: letters, digits, marks, ignorable and format units,
    // currency, connecting punctuation, letter numbers, a supplementary letter,
    // isolated surrogates and separators.
    static List<String> tags() {
        List<String> suffixes = new ArrayList<>(List.of("param", "return", "throws", "exception", "see", "since",
                "deprecated", "author", "version", "Param", "params", "param1", "param$", "param_", "paraḿ",
                "param­", "param​", "param﻿", "param\u0007", "par€am", "p‿q", "Ⅻ",
                "𝒜", "param𝒜", "\uD800", "param\uDC00", "1", "", "-x", "param-x", "param.x",
                "param:x", "١", "a١", "été", "中", "versioǹ", "see\u0000x"));
        char[] alphabet = {'a', 'm', 'p', 'r', '1', '_', '$', '́', '­', '​', '€', '‿', 'Ⅻ',
            '\uD835', '\uDC9C', '\uD800', '-', '.', '١', 'é', '中', '\u0007', ' '};
        Random random = new Random(7);
        for (int index = 0; index < 400; index++) {
            char[] units = new char[1 + random.nextInt(8)];
            for (int unit = 0; unit < units.length; unit++) units[unit] = alphabet[random.nextInt(alphabet.length)];
            suffixes.add(new String(units));
        }
        return suffixes;
    }

    static void ranges(StringBuilder out, String label, int limit, boolean chars) {
        out.append(label).append('\n');
        int code = 0;
        while (code <= limit) {
            boolean part = chars ? Character.isJavaIdentifierPart((char) code) : Character.isJavaIdentifierPart(code);
            if (!part) {
                code++;
                continue;
            }
            int start = code;
            while (code <= limit && (chars ? Character.isJavaIdentifierPart((char) code)
                    : Character.isJavaIdentifierPart(code))) code++;
            out.append(Integer.toHexString(start)).append(' ').append(Integer.toHexString(code - 1)).append('\n');
        }
    }

    public static void main(String[] args) throws Exception {
        Class<?> comment = Class.forName("ironwood.compiler.doc.DocComment");
        head = comment.getDeclaredMethod("head", String.class);
        tail = comment.getDeclaredMethod("tail", String.class);
        entities = comment.getDeclaredMethod("entities", String.class);
        longestTicks = comment.getDeclaredMethod("longestTicks", String.class);
        parse = comment.getDeclaredMethod("parse", String.class);
        Class<?> doc = Class.forName("ironwood.compiler.doc.IronDoc");
        validatePackage = doc.getDeclaredMethod("validatePackage", String.class);
        Class<?> memberClass = Class.forName("ironwood.compiler.doc.DocModel$Member");
        member = memberClass.getDeclaredConstructors()[0];
        anchor = memberClass.getDeclaredMethod("anchor");
        tags = comment.getDeclaredMethod("tags");
        tagName = Class.forName("ironwood.compiler.doc.DocComment$Tag").getDeclaredMethod("name");
        for (Method method : new Method[]{head, tail, entities, longestTicks, parse, validatePackage, anchor, tags, tagName}) {
            method.setAccessible(true);
        }
        member.setAccessible(true);

        List<String> inputs = corpus();
        StringBuilder file = new StringBuilder();
        for (String input : inputs) file.append(hex(input)).append('\n');
        file.append("tags\n");
        List<String> suffixes = tags();
        for (String suffix : suffixes) file.append(hex(suffix)).append('\n');
        Files.writeString(Path.of(args[0]), file, StandardCharsets.UTF_8);

        StringBuilder out = new StringBuilder();
        Pattern ticks = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.]*");
        for (String input : inputs) {
            out.append("in ").append(hex(input)).append('\n');
            out.append("lines");
            for (String field : input.split("\\R", -1)) out.append(' ').append(hex(field));
            out.append('\n');
            out.append("head ").append(hex((String) call(head, input))).append('\n');
            out.append("tail ").append(hex((String) call(tail, input))).append('\n');
            out.append("star ").append(hex(input.replaceFirst("^\\s*\\* ?", ""))).append('\n');
            out.append("code ").append(hex(input.replaceFirst("(?i)^<code>", "").replaceFirst("(?i)</code>$", ""))).append('\n');
            try {
                String decoded = (String) call(entities, input);
                out.append("entities ").append(hex(decoded)).append('\n');
            } catch (IllegalArgumentException failure) {
                out.append("entities error ").append(hex(failure.getMessage())).append('\n');
            }
            out.append("ticks ").append(call(longestTicks, input)).append('\n');
            out.append("nospace ").append(hex(input.replaceAll("\\s+", ""))).append('\n');
            out.append("collapse ").append(hex(input.replaceAll("\\s*\\R\\s*", " "))).append('\n');
            Matcher matcher = ticks.matcher(input);
            StringBuilder runs = new StringBuilder();
            int last = 0;
            while (matcher.find()) {
                runs.append(input, last, matcher.start()).append('<').append(matcher.group()).append('>');
                last = matcher.end();
            }
            runs.append(input.substring(last));
            out.append("runs ").append(hex(runs.toString())).append('\n');
            boolean valid;
            try {
                call(validatePackage, input);
                valid = true;
            } catch (IllegalArgumentException failure) {
                valid = false;
            }
            out.append("package ").append(valid).append('\n');
            out.append("strip ").append(hex(input.stripLeading())).append('\n');
            out.append("region");
            for (String literal : new String[]{"<pre>", "</pre>", "<code>"}) {
                for (int offset = -1; offset <= input.length(); offset++) {
                    out.append(input.regionMatches(true, offset, literal, 0, literal.length()) ? '1' : '0');
                }
                out.append(' ');
            }
            out.append('\n');
            Object value = member.newInstance(null, null, input, null, null, null, false, null, null);
            out.append("anchor ").append(hex((String) anchor.invoke(value))).append('\n');
        }
        for (String suffix : suffixes) {
            String raw = "/**\n * @" + suffix + " value\n */";
            out.append("tag ").append(hex(suffix)).append(' ');
            try {
                List<?> found = (List<?>) tags.invoke(call(parse, raw));
                out.append(found.isEmpty() ? "none" : "name " + hex((String) tagName.invoke(found.getFirst())));
            } catch (IllegalArgumentException failure) {
                out.append("error ").append(hex(failure.getMessage()));
            }
            out.append('\n');
        }
        ranges(out, "char ranges", 0xFFFF, true);
        ranges(out, "code point ranges", Character.MAX_CODE_POINT, false);
        System.out.print(out);
    }
}
