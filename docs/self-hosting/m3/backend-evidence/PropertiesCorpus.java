// SPDX-License-Identifier: MIT OR Apache-2.0

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the Properties corpus for compiler_properties_text.iron into OUTDIR
 * and prints the file paths in order. Arguments: OUTDIR, then real property
 * files (the pinned packaging inventories). Each real file is kept and
 * rewritten with CRLF and CR line endings; a generated build.properties in
 * prepare-tls.py's sorted key=value form, separator, whitespace, comment,
 * duplicate and terminator cases, backslash texts outside the admitted
 * format, and 400 seeded texts follow.
 */
public final class PropertiesCorpus {
    private static long seed = 223606L;
    static final String[] TOKENS = {"key", "a.b", "x", "format", "1", "=", ":", " ", "\t", "\f", "\n", "\r", "\r\n",
        "#", "!", "\u00e9", "\u2028", "\u0085", "\u000b", "sha256.lib/libssl.a", "0f", "=="};

    private PropertiesCorpus() { }

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
            texts.add(text.replace("\n", "\r\n"));
            texts.add(text.replace("\n", "\r"));
        }
        StringBuilder build = new StringBuilder();
        String[][] metadata = {{"ca.sha256", "8f3b"}, {"configuration", "no-shared no-threads"}, {"deployment", "13.0"},
            {"format", "1"}, {"glibc", "2.17"}, {"link.flags", ""}, {"llvm.version", "23.1.0"},
            {"pins.sha256", "0c1d"}, {"platform", "macos-arm64"}, {"sha256.include/openssl/ssl.h", "aa"},
            {"sha256.lib/libssl.a", "bb"}, {"sysroot.relative", "sysroots/glibc-2.17"}};
        for (String[] entry : metadata) build.append(entry[0]).append('=').append(entry[1]).append('\n');
        texts.add(build.toString());
        texts.addAll(List.of("a=b\n", "a = b \n", "a:b", "a b", "a\tb", "a\fb", "a  =  = b", "a=:b", "a:=b", "a :b",
                " \t a=b", "#c=d\n!e=f\n  # g=h\n\t!i\nk=v", "a=1\na=2\nb=3\na=4", "=v", "k", "k=", "k =", " =x",
                "a=b\r\nc=d\re=f\n", "\n\n\r\r\r\n", "", "\u00e9=\u00fc", "a=b # not a comment", "a=b\u2028c=d",
                "a=\u0085b", "a=b\u000bc", "k\f=\fv\f", "#only", "a=b\n#\nc=d", "a\u3000b=c", "x=1\ny=2\n",
                "y=2\nx=1\n", "x=1\ny=3\n", "x=1\n", "a=b\\\nc", "a\\=b=c", "a=\\u0041", "# c\\\nk=v"));
        for (int index = 0; index < 400; index++) {
            StringBuilder text = new StringBuilder();
            int count = next() % 30;
            for (int token = 0; token < count; token++) text.append(TOKENS[next() % TOKENS.length]);
            texts.add(text.toString());
        }
        StringBuilder listing = new StringBuilder();
        for (int index = 0; index < texts.size(); index++) {
            Path file = out.resolve("corpus-" + index + ".properties");
            Files.write(file, texts.get(index).getBytes(StandardCharsets.UTF_8));
            listing.append(file).append('\n');
        }
        System.out.print(listing);
    }
}
