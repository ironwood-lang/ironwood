// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.doc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

record DocComment(String description, List<DocComment.Tag> tags) {
    static final DocComment EMPTY = new DocComment("", List.of());
    private static final Set<String> BLOCK_TAGS = Set.of("param", "return", "throws", "exception", "see",
            "since", "deprecated", "author", "version");
    record Tag(String name, String value) {}

    DocComment {
        tags = List.copyOf(tags);
    }

    static DocComment parse(String raw) {
        String body = raw.length() <= 5 ? "" : raw.substring(3, raw.length() - 2);
        StringBuilder description = new StringBuilder();
        List<Tag> tags = new ArrayList<>();
        String tagName = null;
        StringBuilder tagValue = new StringBuilder();
        int inlineDepth = 0;
        boolean preformatted = false;
        for (String rawLine : body.split("\\R", -1)) {
            String line = rawLine.replaceFirst("^\\s*\\* ?", "");
            String trimmed = line.stripLeading();
            if (inlineDepth == 0 && !preformatted && trimmed.startsWith("@")) {
                if (tagName != null) tags.add(new Tag(tagName, tagValue.toString().strip()));
                int end = 1;
                while (end < trimmed.length() && Character.isJavaIdentifierPart(trimmed.charAt(end))) end++;
                tagName = trimmed.substring(1, end);
                if (!BLOCK_TAGS.contains(tagName)) throw new IllegalArgumentException("unsupported IronDocs tag @" + tagName);
                tagValue = new StringBuilder(trimmed.substring(end).stripLeading());
            } else {
                (tagName == null ? description : tagValue).append('\n').append(line);
            }
            // Block tags inside inline code or <pre> examples are literal content.
            for (int i = 0; i < line.length(); i++) {
                if (inlineDepth == 0 && line.startsWith("{@", i)) { inlineDepth = 1; i++; }
                else if (inlineDepth > 0 && line.charAt(i) == '{') inlineDepth++;
                else if (inlineDepth > 0 && line.charAt(i) == '}') inlineDepth--;
                else if (inlineDepth == 0 && line.regionMatches(true, i, "<pre>", 0, 5)) preformatted = true;
                else if (inlineDepth == 0 && line.regionMatches(true, i, "</pre>", 0, 6)) preformatted = false;
            }
        }
        if (tagName != null) tags.add(new Tag(tagName, tagValue.toString().strip()));
        if (inlineDepth != 0) throw new IllegalArgumentException("unterminated IronDocs inline tag");
        if (preformatted) throw new IllegalArgumentException("unterminated IronDocs <pre> block");
        return new DocComment(description.toString().strip(), tags);
    }

    void validate(List<String> parameters, List<String> typeParameters, boolean returnsValue) {
        Set<String> seen = new HashSet<>();
        for (Tag tag : tags) {
            if (tag.value().isBlank()) throw new IllegalArgumentException("empty IronDocs @" + tag.name() + " tag");
            if (tag.name().equals("param")) {
                String name = head(tag.value());
                boolean typeParameter = name.startsWith("<") && name.endsWith(">");
                boolean valid = typeParameter ? typeParameters.contains(name.substring(1, name.length() - 1))
                        : parameters.contains(name);
                if (!valid) throw new IllegalArgumentException("IronDocs @param names no declared parameter: " + name);
                if (!seen.add(name)) throw new IllegalArgumentException("duplicate IronDocs @param " + name);
                if (tail(tag.value()).isEmpty()) throw new IllegalArgumentException("missing IronDocs @param description: " + name);
            }
            if (tag.name().equals("return")) {
                if (!returnsValue) throw new IllegalArgumentException("IronDocs @return requires a non-void method");
                if (!seen.add("@return")) throw new IllegalArgumentException("duplicate IronDocs @return");
            }
            if ((tag.name().equals("throws") || tag.name().equals("exception")) && tail(tag.value()).isEmpty()) {
                throw new IllegalArgumentException("missing IronDocs @" + tag.name() + " description");
            }
        }
    }

    static String head(String value) {
        return value.split("\\s+", 2)[0];
    }

    static String tail(String value) {
        String[] parts = value.split("\\s+", 2);
        return parts.length == 1 ? "" : parts[1];
    }

    /** Render the supported Javadoc text vocabulary without executing arbitrary HTML. */
    static String render(String input, BiFunction<String, Boolean, String> link) {
        StringBuilder output = new StringBuilder();
        java.util.ArrayDeque<String> lists = new java.util.ArrayDeque<>();
        for (int i = 0; i < input.length();) {
            if (input.startsWith("{@", i)) {
                int end = inlineEnd(input, i);
                String tag = input.substring(i + 2, end).strip();
                String name = head(tag);
                String value = tail(tag);
                output.append(switch (name) {
                    case "code" -> code(value);
                    case "literal" -> literal(value);
                    case "link" -> link.apply(value, true);
                    case "linkplain" -> link.apply(value, false);
                    default -> throw new IllegalArgumentException("unsupported IronDocs inline tag {@" + name + "}");
                });
                i = end + 1;
            } else if (input.regionMatches(true, i, "<pre>", 0, 5)) {
                int end = input.toLowerCase(java.util.Locale.ROOT).indexOf("</pre>", i + 5);
                if (end < 0) throw new IllegalArgumentException("unterminated IronDocs <pre> block");
                String code = input.substring(i + 5, end).strip();
                if (code.startsWith("{@code") && inlineEnd(code, 0) == code.length() - 1) {
                    code = code.substring(6, code.length() - 1).strip();
                } else {
                    code = code.replaceFirst("(?i)^<code>", "").replaceFirst("(?i)</code>$", "");
                    code = entities(code);
                }
                output.append("\n\n").append(fence(code)).append('\n');
                i = end + 6;
            } else if (input.regionMatches(true, i, "<code>", 0, 6)) {
                int end = input.toLowerCase(java.util.Locale.ROOT).indexOf("</code>", i + 6);
                if (end < 0) throw new IllegalArgumentException("unterminated IronDocs <code> tag");
                output.append(code(entities(input.substring(i + 6, end))));
                i = end + 7;
            } else if (input.charAt(i) == '<') {
                int end = input.indexOf('>', i);
                if (end < 0) throw new IllegalArgumentException("unescaped '<' in IronDocs text; use {@literal ...}");
                String tag = input.substring(i + 1, end).strip().toLowerCase(java.util.Locale.ROOT);
                output.append(switch (tag) {
                    case "p", "/p" -> "\n\n";
                    case "ul", "ol" -> { lists.push(tag); yield "\n\n"; }
                    case "/ul", "/ol" -> {
                        if (lists.isEmpty() || !lists.pop().equals(tag.substring(1))) {
                            throw new IllegalArgumentException("unbalanced IronDocs list tag <" + tag + ">");
                        }
                        yield "\n\n";
                    }
                    case "br", "br/", "br /" -> "  \n";
                    case "li" -> {
                        if (lists.isEmpty()) throw new IllegalArgumentException("IronDocs <li> requires a list");
                        yield "\n" + "    ".repeat(lists.size() - 1) + (lists.peek().equals("ol") ? "1. " : "- ");
                    }
                    case "/li" -> "\n";
                    case "b", "/b", "strong", "/strong" -> "**";
                    case "i", "/i", "em", "/em" -> "*";
                    default -> throw new IllegalArgumentException("unsupported IronDocs HTML tag <" + tag + ">");
                });
                i = end + 1;
            } else if (input.charAt(i) == '&') {
                int end = input.indexOf(';', i);
                if (end > i && end - i < 16) {
                    String entity = input.substring(i, end + 1);
                    output.append(literal(entities(entity)));
                    i = end + 1;
                } else output.append(input.charAt(i++));
            } else output.append(input.charAt(i++));
        }
        if (!lists.isEmpty()) throw new IllegalArgumentException("unterminated IronDocs list");
        return output.toString().strip();
    }

    static String firstSentence(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.startsWith("{@", i)) {
                i = inlineEnd(text, i);
            } else if (text.charAt(i) == '<') {
                int end = text.indexOf('>', i);
                if (end < 0) break; // Rendering produces the source diagnostic.
                String tag = text.substring(i + 1, end).toLowerCase(java.util.Locale.ROOT);
                if (Set.of("p", "pre", "ul", "ol").contains(tag)) return text.substring(0, i).strip();
                if (Set.of("code", "b", "strong", "i", "em").contains(tag)) {
                    int close = text.toLowerCase(java.util.Locale.ROOT).indexOf("</" + tag + ">", end);
                    if (close >= 0) i = close + tag.length() + 2;
                }
            } else if (text.charAt(i) == '.' && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1)))) {
                return text.substring(0, i + 1);
            } else if (text.startsWith("\n\n", i)) return text.substring(0, i);
        }
        return text;
    }

    private static int inlineEnd(String text, int start) {
        int depth = 1;
        for (int i = start + 2; i < text.length(); i++) {
            if (text.charAt(i) == '{') depth++;
            else if (text.charAt(i) == '}' && --depth == 0) return i;
        }
        throw new IllegalArgumentException("unterminated IronDocs inline tag");
    }

    static String code(String text) {
        String fence = "`".repeat(longestTicks(text) + 1);
        String padding = text.startsWith("`") || text.endsWith("`") || text.startsWith(" ") || text.endsWith(" ") ? " " : "";
        return fence + padding + text.replace('\n', ' ') + padding + fence;
    }

    static String fence(String text) {
        String fence = "`".repeat(Math.max(3, longestTicks(text) + 1));
        // GitHub's Java highlighter understands Ironwood's familiar declaration syntax.
        return fence + "java\n" + text + "\n" + fence + "\n";
    }

    private static int longestTicks(String text) {
        int longest = 0;
        var matcher = Pattern.compile("`+").matcher(text);
        while (matcher.find()) longest = Math.max(longest, matcher.group().length());
        return longest;
    }

    static String literal(String text) {
        StringBuilder result = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c == '<') result.append("&lt;");
            else if (c == '>') result.append("&gt;");
            else if (c == '&') result.append("&amp;");
            else {
                if ("\\`*_{}[]()#+-.!|".indexOf(c) >= 0) result.append('\\');
                result.append(c);
            }
        }
        return result.toString();
    }

    private static String entities(String text) {
        var matcher = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|lt|gt|amp|quot|apos|nbsp);").matcher(text);
        return matcher.replaceAll(match -> {
            String entity = match.group(1);
            String value = switch (entity) {
                case "lt" -> "<";
                case "gt" -> ">";
                case "amp" -> "&";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "nbsp" -> " ";
                default -> {
                    try {
                        int point = entity.startsWith("#x") ? Integer.parseInt(entity.substring(2), 16)
                                : Integer.parseInt(entity.substring(1));
                        yield new String(Character.toChars(point));
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalArgumentException("invalid IronDocs character entity: &" + entity + ";");
                    }
                }
            };
            return java.util.regex.Matcher.quoteReplacement(value);
        });
    }
}
