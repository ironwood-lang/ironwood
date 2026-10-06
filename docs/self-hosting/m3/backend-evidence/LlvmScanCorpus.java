// SPDX-License-Identifier: MIT OR Apache-2.0

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the LLVM scan corpus for compiler_llvm_scan.iron into OUTDIR and
 * prints the file paths in order. Arguments: OUTDIR, then real LLVM inputs
 * (a Clang target probe, emitted and optimized modules). Each real input is
 * kept and rewritten with CRLF, CR, U+0085, U+2028 and U+2029 line
 * terminators; adversarial target, declaration and symbol texts follow, then
 * 600 seeded texts over the tokens the three patterns distinguish.
 */
public final class LlvmScanCorpus {
    private static long seed = 173205L;
    static final String[] TOKENS = {"define", "define ", " ", "\t", "\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029",
        "\u000b", "\f", "@", "\"", "\\", "(", ")", "a", "5", "C", "g", "$", "-", "@f(", "@\"x\"(", "\\22", "\\5C",
        "target triple = \"", "target datalayout = \"", "e-m:o", "\" ", "\"\t", "\"x", "triple",
        "declare void @ironwood_trace_register_current(ptr, i32)", "declare void @ironwood_trace_register_current",
        " #0", "}\n", "\u00e9", "\ud83d\ude00"};

    private LlvmScanCorpus() { }

    static int next() {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return (int) (seed >>> 33);
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        List<String> texts = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            String text = Files.readString(Path.of(args[index]));
            texts.add(text);
            for (String terminator : new String[] {"\r\n", "\r", "\u0085", "\u2028", "\u2029"}) {
                texts.add(text.replace("\n", terminator));
            }
        }
        String target = "target datalayout = \"e-m:o-i64:64-i128:128-n32:64-S128-Fn32\"\n"
                + "target triple = \"arm64-apple-macosx15.0.0\"\n";
        texts.addAll(List.of(target, target + target, target.replace("\n", "\t \n"), target.replace("\n", "x\n"),
                "target triple = \"\"\n", "target triple = \"a\"", "target triple = \"a\rb\"\n",
                "target triple = \"a\"\r\n", "target triple = \"a\" \t", " target triple = \"a\"\n",
                "x\ntarget triple = \"a\"\ntarget triple = \"a\"\n", "target triple = \"arm64\u2028\"\n",
                "\u2028target triple = \"b\"\u2029", "target triple =  \"a\"\n", "target triple = \"x86_64-unknown-linux-gnu\"\n",
                "declare void @ironwood_trace_register_current(ptr, i32)\n",
                "declare void @ironwood_trace_register_current(ptr, i32) #3\r\nrest\n",
                "x declare void @ironwood_trace_register_current(ptr, i32)\n",
                "declare void @ironwood_trace_register_current(ptr, i32)",
                "a\rdeclare void @ironwood_trace_register_current(ptr, i32)\nb\n",
                "a\ndeclare void @ironwood_trace_register_current(ptr, i32)\ndeclare void @ironwood_trace_register_current(ptr, i32)\n",
                "define void @f() {\n}\n", "define\n@f(", "define\t \n  @f(", "define @\"a\\\"b\"(", "define @\"a\\\\\"(\"(",
                "define @\"a\\\\\"x\"(", "define @\"\\22\"(", "define @\"\\\n\"(", "define @\"\\\u2028\"(\"(",
                "define void @\"a\"x @b(", "define void @\"unterminated(\n@c(", "define @1(@a(", "define @-a(",
                "define @a.b$c-d(", "define void @\"\"(", "xdefine @a(", "\rdefine @a(", "\r\ndefine @a(",
                "define@a(", "define @\"\\5C\\22\"(i32) {\n}\n", "define @\"a\"(@\"b\"(", "define @\"a\\\"(\n\"(",
                "define void @\"\ud83d\ude00\"(", "define @\"\\\\\\\"\"(\"("));
        for (int index = 0; index < 600; index++) {
            StringBuilder text = new StringBuilder();
            int count = next() % 40;
            for (int token = 0; token < count; token++) text.append(TOKENS[next() % TOKENS.length]);
            texts.add(text.toString());
        }
        StringBuilder listing = new StringBuilder();
        for (int index = 0; index < texts.size(); index++) {
            Path file = out.resolve("corpus-" + index + ".ll");
            Files.write(file, texts.get(index).getBytes(StandardCharsets.UTF_8));
            listing.append(file).append('\n');
        }
        System.out.print(listing);
    }
}
