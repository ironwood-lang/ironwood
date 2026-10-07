# SPDX-License-Identifier: MIT OR Apache-2.0
"""Classify every M3, M4, M5 or M6 call pattern of the M0 source-backed inventory.

Usage: classify.py PHASE [--markdown OUT]

Each API pattern that docs/self-hosting/m0/deferred assigns to the phase
gets exactly one class:

  A  an existing Ironwood member with the needed semantics; the rule names
     the stdlib or port file and a declaration regex, which must match;
  B  a recorded port convention (decision or record cited), no new helper;
  D  a helper delivered in M3, M4, M5 or M6 (decision cited); its file must exist.

PHASE_RULES holds rules for one phase only, consulted before RULES, so an
M4, M5 or M6 phase can classify a shared Java API for its own consumers
without changing a recorded M3 table. M6.1 also takes the two calls whose
gate reuses the M3.2 digest-text helper at M6.1.

The tool fails if a phase pattern has no rule, a rule matches no pattern,
an A declaration is missing, a D file is missing, or a row is still marked
pending. It prints per-class call and pattern counts.
"""
import gzip
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
STDLIB = ROOT / "stdlib/src/main/ironwood/ironwood"
PORT = ROOT / "compiler/src/main/ironwood/ironwood/compiler/port"

# Conventions already recorded before M3.
D258 = "D258 port conventions"
LOOPS = "B: ordered loops with Java's short-circuiting, encounter order and empty results (D258)"
NULLABLE = "B: nullable reference or presence branch evaluated once (D258)"
INSTANCEOF = "B: ordered instanceof chain or the IrModel/OperationVariants kind (D260, D265)"
ITERATE = ("B: reusable iterator over values with getCurrIteratorKey, or a key list copied once "
           "when traversal must survive mutation or nesting (B1 section 4.3)")
PRIMITIVE = "B: primitive value; no boxed wrapper (D258)"


def a(file, regex):
    return ("A", file, regex)


def b(text):
    return ("B", text)


def d(text, *files):
    return ("D", text, files)


def pending(text):
    return ("P", text)


# Rules: (owner, signature regex) -> classification. The first match wins.
RULES = [
    # Language and value types.
    ("Array", r"^length$", a("lang/Object.iron", r"class Object") + ("array length is a language field",)),
    ("Array", r"^clone\(\)$", b("B: explicit copy of the required extent with System.arraycopy into a fresh array")),
    ("java.lang.AssertionError", r".", b("B: the impossible digest-lookup failure disappears; native digests cannot fail lookup")),
    ("java.lang.Boolean", r"^(TRUE|FALSE)$", b(PRIMITIVE)),
    ("java.lang.Byte", r"^(MAX_VALUE|MIN_VALUE)$", a("lang/Byte.iron", r"public static final byte (MAX|MIN)_VALUE")),
    ("java.lang.Byte", r"^SIZE$", b("B: the literal 8")),
    ("java.lang.Byte", r"^toUnsignedInt", d("Bytes.toUnsignedInt (D268)", "Bytes.iron")),
    ("java.lang.Class", r".", b(INSTANCEOF + "; getSimpleName is IrModel.javaName (D266); reflection is the D265 walk contract")),
    ("java.lang.reflect.", r".", b("B: explicit walkers following IrModel.walked (D265)")),
    ("java.lang.Enum", r"^name\(\)$", a("lang/Enum.iron", r"abstract String name\(")),
    ("java.lang.Enum", r"^ordinal\(\)$", a("lang/Enum.iron", r"abstract int ordinal\(")),
    ("java.lang.IllegalArgumentException", r"^IllegalArgumentException\(\)$", a("lang/IllegalArgumentException.iron", r"public IllegalArgumentException\(\)")),
    ("java.lang.IllegalArgumentException", r"^IllegalArgumentException\(java.lang.String\)$", a("lang/IllegalArgumentException.iron", r"public IllegalArgumentException\(String message\)")),
    ("java.lang.IllegalStateException", r"^IllegalStateException\(\)$", a("lang/IllegalStateException.iron", r"public IllegalStateException\(\)")),
    ("java.lang.IllegalStateException", r"^IllegalStateException\(java.lang.String\)$", a("lang/IllegalStateException.iron", r"public IllegalStateException\(String message\)")),
    ("java.lang.IllegalStateException", r"^IllegalStateException\(java.lang.String,java.lang.Throwable\)$", a("lang/IllegalStateException.iron", r"public IllegalStateException\(String message, Throwable cause\)")),
    ("java.lang.IllegalStateException", r"^IllegalStateException\(java.lang.Throwable\)$", a("lang/IllegalStateException.iron", r"public IllegalStateException\(Throwable cause\)")),
    ("java.lang.Integer", r"^(MAX_VALUE|MIN_VALUE)$", a("lang/Integer.iron", r"public static final int (MAX|MIN)_VALUE")),
    ("java.lang.Integer", r"^toString\(int\)$", a("lang/Integer.iron", r"public static String toString\(int")),
    ("java.lang.Integer", r"^(valueOf|equals|sum)", b(PRIMITIVE + "; Integer::sum is +")),
    ("java.lang.Iterable", r"^forEach", b(LOOPS)),
    ("java.lang.Long", r"^(MAX_VALUE|MIN_VALUE)$", a("lang/Long.iron", r"public static final long (MAX|MIN)_VALUE")),
    ("java.lang.Long", r"^toString\(long\)$", a("lang/Long.iron", r"public static String toString\(long")),
    ("java.lang.Long", r"^valueOf", b(PRIMITIVE)),
    ("java.lang.Math", r"^max\(int,int\)$", a("lang/Math.iron", r"public static int max\(int")),
    ("java.lang.Math", r"^min\(int,int\)$", a("lang/Math.iron", r"public static int min\(int")),
    ("java.lang.Object", r"^equals\(java.lang.Object\)$", a("lang/Object.iron", r"public boolean equals\(Object")),
    ("java.lang.Object", r"^toString\(\)$", a("lang/Object.iron", r"public String toString\(")),
    ("java.lang.Object", r"^getClass\(\)$", b(INSTANCEOF)),
    ("java.lang.Runnable", r"^run\(\)$", a("lang/Runnable.iron", r"void run\(")),
    ("java.lang.Short", r"^(MAX_VALUE|MIN_VALUE)$", a("lang/Short.iron", r"public static final short (MAX|MIN)_VALUE")),
    ("java.lang.Character", r"^(MAX_VALUE|MIN_VALUE)$", a("lang/Character.iron", r"public static final char (MAX|MIN)_VALUE")),
    ("java.lang.Character", r"^isHighSurrogate", a("lang/Character.iron", r"public static boolean isHighSurrogate\(")),
    ("java.lang.Character", r"^isLowSurrogate", a("lang/Character.iron", r"public static boolean isLowSurrogate\(")),
    ("java.lang.Character", r"^isSurrogate\(", a("lang/Character.iron", r"public static boolean isSurrogate\(")),
    ("java.lang.Character", r"^toCodePoint", d("Texts.codePoint (D267)", "Texts.iron")),
    ("java.lang.Character", r"^toString\(char\)$", a("lang/String.iron", r"public static String valueOf\(char") + ("String.valueOf(char)",)),
    ("java.lang.Double", r"^NaN$", a("lang/Double.iron", r"public static final double NaN")),
    ("java.lang.Double", r"^isNaN", a("lang/Double.iron", r"public static boolean isNaN\(")),
    ("java.lang.Double", r"^parseDouble", a("lang/Double.iron", r"public static double parseDouble\(") + ("equal to Java 21 (D267)",)),
    ("java.lang.Double", r"^toString\(double\)$", a("lang/Double.iron", r"public static String toString\(double") + ("equal to Java 21 (D267)",)),
    ("java.lang.Double", r"^valueOf", b(PRIMITIVE)),
    ("java.lang.Float", r"^parseFloat", a("lang/Float.iron", r"public static float parseFloat\(") + ("equal to Java 21 (D267)",)),
    ("java.lang.Float", r"^toString\(float\)$", a("lang/Float.iron", r"public static String toString\(float") + ("equal to Java 21 (D267)",)),
    ("java.lang.Float", r"^valueOf", b(PRIMITIVE)),
    ("java.lang.Number", r".", b("B: an explicit constant kind with a primitive payload (S2 representation) instead of a Number")),
    ("java.math.BigInteger", r".", d("IntegerLiterals for literal decoding and IntegralConstants for wrapping folds (D267)", "IntegerLiterals.iron", "IntegralConstants.iron")),
    ("java.nio.charset.StandardCharsets", r"^UTF_8$", b("B: UTF-8 is the only charset: String.getBytes() or Sha256.updateUtf8 ('?' for unpaired surrogates, as Java)")),
    ("java.nio.charset.StandardCharsets", r"^US_ASCII$", b("B: the admitted inputs are ASCII section names, compared byte by byte")),
    ("java.security.MessageDigest", r".", d("Sha256 (D267); Md5 for MD5 (D268)", "Sha256.iron", "Md5.iron")),
    ("java.util.HexFormat", r".", d("Sha256.hexDigest: every site formats a whole SHA-256 result (D267)", "Sha256.iron")),
    ("java.lang.String", r"^charAt", a("lang/String.iron", r"public char charAt\(")),
    ("java.lang.String", r"^compareTo", a("lang/String.iron", r"public int compareTo\(String") + ("UTF-16 order (D264)",)),
    ("java.lang.String", r"^getBytes\(java.nio.charset.Charset\)$", a("lang/String.iron", r"public byte\[\] getBytes\(\)") + ("UTF-8 with '?' for unpaired surrogates, as Java",)),
    ("java.lang.String", r"^indexOf", a("lang/String.iron", r"public int indexOf\(")),
    ("java.lang.String", r"^join", d("Texts.join over lists (D267)", "Texts.iron")),
    ("java.lang.String", r"^lastIndexOf", a("lang/String.iron", r"public int lastIndexOf\(int")),
    ("java.lang.String", r"^length", a("lang/String.iron", r"public int length\(")),
    ("java.lang.String", r"^replace\(char,char\)$", a("lang/String.iron", r"public String replace\(char oldChar")),
    ("java.lang.String", r"^replace\(java.lang.CharSequence", a("lang/String.iron", r"public String replace\(CharSequence target")),
    ("java.lang.String", r"^replaceFirst", d("Texts.replaceFirst for the literal joinNotes pattern (D267)", "Texts.iron")),
    ("java.lang.String", r"^split\(java.lang.String,int\)$", d("D266 Splits.bounds with the call's limit", "Splits.iron")),
    ("java.lang.String", r"^startsWith", a("lang/String.iron", r"public boolean startsWith\(")),
    ("java.lang.String", r"^valueOf\(java.lang.Object\)$", a("lang/String.iron", r"public static String valueOf\(Object")),
    ("java.lang.String", r"^chars\(\)$", b("B: charAt loop over UTF-16 units")),
    ("java.lang.String", r"^endsWith", a("lang/String.iron", r"public boolean endsWith\(")),
    ("java.lang.String", r"^equals", a("lang/String.iron", r"public boolean equals\(")),
    ("java.lang.String", r"^isBlank", a("lang/String.iron", r"public boolean isBlank\(")),
    ("java.lang.String", r"^isEmpty", a("lang/String.iron", r"public boolean isEmpty\(")),
    ("java.lang.String", r"^lastIndexOf\(int\)$", a("lang/String.iron", r"public int lastIndexOf\(int")),
    ("java.lang.String", r"^split\(java.lang.String\)$", d("D266 Splits.bounds with limit 0", "Splits.iron")),
    ("java.lang.String", r"^substring", a("lang/String.iron", r"public String substring\(int beginIndex")),
    ("java.lang.String", r"^toLowerCase\(\)$", a("lang/String.iron", r"public String toLowerCase\(\)") + ("ASCII enum names",)),
    ("java.lang.String", r"^toLowerCase\(java.util.Locale\)$", a("lang/String.iron", r"public String toLowerCase\(\)") + ("ASCII enum names: equal to Locale.ROOT",)),
    ("java.lang.StringBuilder", r"^StringBuilder\(\)$", a("lang/StringBuilder.iron", r"public StringBuilder\(\)")),
    ("java.lang.StringBuilder", r"^append\(char\)$", a("lang/StringBuilder.iron", r"public StringBuilder append\(char")),
    ("java.lang.StringBuilder", r"^append\(java.lang.String\)$", a("lang/StringBuilder.iron", r"public StringBuilder append\(String")),
    ("java.lang.StringBuilder", r"^toString\(\)$", a("lang/StringBuilder.iron", r"public String toString\(")),
    ("java.lang.Throwable", r"^getMessage\(\)$", a("lang/Throwable.iron", r"public String getMessage\(")),
    ("java.lang.ref.", r".", b("B: snapshot-owned saved evidence; no weak references (D259)")),
    ("java.nio.file.Path", r"^getFileName|^toString", a("compiler:source/SourceFile.iron", r"public String fileName\(") + ("SourceFile.fileName/path strings",)),
    ("java.nio.file.Path", r"^equals", a("compiler:source/SourceFile.iron", r"public String path\(") + ("String equality of SourceFile.path",)),
    # M3.3 backend, driver and installation inputs (D268, D269).
    ("java.io.ByteArrayOutputStream", r"^ByteArrayOutputStream\(int\)$", a("io/ByteArrayOutputStream.iron", r"public ByteArrayOutputStream\(int")),
    ("java.io.ByteArrayOutputStream", r"^write\(int\)$", a("io/ByteArrayOutputStream.iron", r"public void write\(int")),
    ("java.io.ByteArrayOutputStream", r"^toString\(java.nio.charset.Charset\)$", a("io/ByteArrayOutputStream.iron", r"public String toString\(\)") + ("the fixed UTF-8 default with Java's U+FFFD replacement",)),
    ("java.io.ByteArrayOutputStream", r"^writeBytes", d("LlvmText.decodeSymbol writes each unit's UTF-8 bytes directly (D268)", "LlvmText.iron")),
    ("java.io.File", r"^pathSeparator$", b("B: ':' on every supported host, as System.getProperty(\"path.separator\") reports; the list is split by D266 Splits.bounds with limit -1")),
    ("java.io.IOException", r"^IOException\(java.lang.String\)$", a("io/IOException.iron", r"public IOException\(String message\)")),
    ("java.io.InputStream", r"^read\(byte\[\]\)$", a("io/InputStream.iron", r"public int read\(byte\[\] buffer\)")),
    ("java.io.InputStream", r"^readAllBytes", b("B: CompilerVersion's resource becomes the generated BuildIdentity, and xcrun's output the shell driver's SDKROOT (D269)")),
    ("java.io.PrintStream", r"^println\(java.lang.String\)$", a("io/PrintStream.iron", r"public void println\(String")),
    ("java.lang.Double", r"^(POSITIVE|NEGATIVE)_INFINITY$", a("lang/Double.iron", r"public static final double (POSITIVE|NEGATIVE)_INFINITY")),
    ("java.lang.Double", r"^doubleToRawLongBits", d("Double.doubleToRawLongBits, added with NaN payloads kept (D268)", "stdlib:lang/Double.iron")),
    ("java.lang.Integer", r"^parseInt\(java.lang.String,int\)$", a("lang/Integer.iron", r"public static int parseInt\(String text, int radix")),
    ("java.lang.Integer", r"^toHexString", d("LlvmText.escapeBytes spells two uppercase digits (D268)", "LlvmText.iron")),
    ("java.lang.Integer", r"^toUnsignedLong", d("Bytes.toUnsignedLong (D268)", "Bytes.iron")),
    ("java.lang.Long", r"^BYTES$", a("lang/Long.iron", r"public static final int BYTES")),
    ("java.lang.Long", r"^compareUnsigned", a("lang/Long.iron", r"public static int compareUnsigned\(") + ("Java's -1, 0 and 1",)),
    ("java.lang.NumberFormatException", r"^NumberFormatException\(\)$", a("lang/NumberFormatException.iron", r"public NumberFormatException\(\)")),
    ("java.lang.Short", r"^toUnsignedInt", d("Bytes.toUnsignedInt (D268)", "Bytes.iron")),
    ("java.lang.String", r"^String\(byte\[\],int,int,java.nio.charset.Charset\)$", d("Bytes.asciiEquals: the one US-ASCII decode is compared with a section name (D268)", "Bytes.iron")),
    ("java.lang.String", r"^String\(byte\[\],java.nio.charset.Charset\)$", b("B: CompilerVersion's resource becomes the generated BuildIdentity, and xcrun's output the shell driver's SDKROOT (D269)")),
    ("java.lang.String", r"^String\(char\[\]\)$", a("lang/String.iron", r"public String\(char\[\] value\)")),
    ("java.lang.String", r"^contains", a("lang/String.iron", r"public boolean contains\(")),
    ("java.lang.String", r"^format\(java.util.Locale", d("LlvmText.hexBits for 0x%016X and LlvmText.scientific for %.17e (D268)", "LlvmText.iron")),
    ("java.lang.String", r"^matches", d("HeaderScan.defines for the two features.h patterns (D268)", "HeaderScan.iron")),
    ("java.lang.String", r"^repeat", a("lang/String.iron", r"public String repeat\(")),
    ("java.lang.String", r"^strip\(\)$", a("lang/String.iron", r"public String strip\(")),
    ("java.lang.String", r"^toCharArray", a("lang/String.iron", r"public char\[\] toCharArray\(")),
    ("java.lang.String", r"^toUpperCase\(java.util.Locale\)$", d("LlvmText.escapeBytes spells two uppercase digits (D268)", "LlvmText.iron")),
    ("java.lang.String", r"^trim\(\)$", a("lang/String.iron", r"public String trim\(") + ("BuildIdentity applies build.sh's whitespace removal at generation (D269)",)),
    ("java.lang.String", r"^valueOf\(char\)$", a("lang/String.iron", r"public static String valueOf\(char value")),
    ("java.lang.StringBuilder", r"^StringBuilder\(java.lang.String\)$", a("lang/StringBuilder.iron", r"public StringBuilder\(String initial")),
    ("java.lang.StringBuilder", r"^append\(int\)$", a("lang/StringBuilder.iron", r"public StringBuilder append\(int")),
    ("java.lang.StringBuilder", r"^append\(long\)$", a("lang/StringBuilder.iron", r"public StringBuilder append\(long")),
    ("java.lang.StringBuilder", r"^append\(java.lang.CharSequence\)$", a("lang/StringBuilder.iron", r"public StringBuilder append\(CharSequence text\)")),
    ("java.lang.StringBuilder", r"^append\(java.lang.Object\)$", a("lang/StringBuilder.iron", r"public StringBuilder append\(Object") + ("Integer actuals become append(int) (D258)",)),
    ("java.lang.System", r"^arraycopy", a("lang/System.iron", r"public static void arraycopy\(")),
    ("java.lang.System", r"^(out|err)$", a("lang/System.iron", r"public static final PrintStream (out|err)")),
    ("java.lang.System", r"^exit", a("lang/System.iron", r"public static void exit\(")),
    ("java.lang.System", r"^getProperty", a("lang/System.iron", r"public static String getProperty\(") + ("os.name and os.arch as each caller tests them; a fresh result",)),
    ("java.lang.System", r"^getenv", a("lang/System.iron", r"public static String getenv\(") + ("a fresh result",)),
    ("java.lang.System", r"^lineSeparator", a("lang/System.iron", r"public static String lineSeparator\(")),
    ("java.lang.Thread", r".", b("B: no interruption: the shell driver supplies SDKROOT in place of the xcrun probe (D269), and a native launch is M4.3's synchronous B4 adapter")),
    ("java.net.URL", r".", d("Installation with the launcher's canonical location (D269)", "Installation.iron", "LibraryRoots.iron")),
    ("java.security.CodeSource", r".", d("Installation with the launcher's canonical location (D269)", "Installation.iron", "LibraryRoots.iron")),
    ("java.security.ProtectionDomain", r".", d("Installation with the launcher's canonical location (D269)", "Installation.iron", "LibraryRoots.iron")),
    ("java.nio.ByteBuffer", r".", d("Bytes.littleShort, littleInt and littleLong: a wrapping buffer would keep the object bytes from being freed (D268)", "Bytes.iron")),
    ("java.nio.ByteOrder", r".", d("Bytes.littleShort, littleInt and littleLong: a wrapping buffer would keep the object bytes from being freed (D268)", "Bytes.iron")),
    ("java.nio.file.Files", r"^(copy|move|walk|createTempDirectory|delete)\(", b("B: Linux Bridge support delivery after a shared link, reached only from Bridge production (S7); its staging and publication need M4.1/M4.2")),
    ("java.nio.file.Files", r"^(createTempFile|deleteIfExists)\(", b("B: a link's temporary LLVM file: in the source-only route the shell driver passes --emit-llvm and owns the file (D269); native temporaries are M4.1")),
    ("java.nio.file.Files", r"^createDirectories", a("nio/file/Files.iron", r"public static Path createDirectories\(")),
    ("java.nio.file.Files", r"^exists", a("nio/file/Files.iron", r"public static boolean exists\(")),
    ("java.nio.file.Files", r"^isDirectory", a("nio/file/Files.iron", r"public static boolean isDirectory\(")),
    ("java.nio.file.Files", r"^isRegularFile", a("nio/file/Files.iron", r"public static boolean isRegularFile\(")),
    ("java.nio.file.Files", r"^isSameFile", a("nio/file/Files.iron", r"public static boolean isSameFile\(")),
    ("java.nio.file.Files", r"^isSymbolicLink", a("nio/file/Files.iron", r"public static boolean isSymbolicLink\(")),
    ("java.nio.file.Files", r"^newBufferedReader", d("Files.readString, as strict as the reader, then PropertiesText.parse (D268)", "PropertiesText.iron")),
    ("java.nio.file.Files", r"^newInputStream", a("nio/file/Files.iron", r"public static InputStream newInputStream\(")),
    ("java.nio.file.Files", r"^readString", a("nio/file/Files.iron", r"public static String readString\(Path path\)") + ("UTF-8 with Java's strict malformed-input failure",)),
    ("java.nio.file.Files", r"^writeString", a("nio/file/Files.iron", r"public static Path writeString\(Path path, CharSequence") + ("UTF-8",)),
    ("java.nio.file.Path", r"^toRealPath", b("B: a link's output alias check: in the source-only route the shell driver owns output paths (D269); real paths are M4.1")),
    ("java.nio.file.Path", r"^of\(java.net.URI\)$", d("Installation with the launcher's canonical location (D269)", "Installation.iron", "LibraryRoots.iron")),
    ("java.nio.file.Path", r"^getParent", a("nio/file/Path.iron", r"Path getParent\(\);")),
    ("java.nio.file.Path", r"^isAbsolute", a("nio/file/Path.iron", r"boolean isAbsolute\(\);")),
    ("java.nio.file.Path", r"^normalize", a("nio/file/Path.iron", r"Path normalize\(\);")),
    ("java.nio.file.Path", r"^of\(java.lang.String", a("nio/file/Path.iron", r"static Path of\(String first") + ("fixed arity: Path.of(first) or Path.of(first, more)",)),
    ("java.nio.file.Path", r"^resolve\(java.lang.String\)$", a("nio/file/Path.iron", r"Path resolve\(String other\);")),
    ("java.nio.file.Path", r"^resolve\(java.nio.file.Path\)$", a("nio/file/Path.iron", r"Path resolve\(Path other\);")),
    ("java.nio.file.Path", r"^resolveSibling\(java.lang.String\)$", a("nio/file/Path.iron", r"Path resolveSibling\(String other\);")),
    ("java.nio.file.Path", r"^startsWith\(java.lang.String\)$", a("nio/file/Path.iron", r"boolean startsWith\(String other\);")),
    ("java.nio.file.Path", r"^startsWith\(java.nio.file.Path\)$", a("nio/file/Path.iron", r"boolean startsWith\(Path other\);")),
    ("java.nio.file.Path", r"^toAbsolutePath", a("nio/file/Path.iron", r"Path toAbsolutePath\(\);")),
    ("java.util.Properties", r".", d("PropertiesText for the admitted key=value format; a backslash fails closed (D268)", "PropertiesText.iron")),
    ("java.util.regex.Pattern", r"^quote", b("B: the literal ':' delimiter of D266 Splits.bounds, the host path separator")),
    ("java.util.regex.", r".", d("LlvmScan: purpose-specific scanners with java.util.regex's multiline matches (D268)", "LlvmScan.iron")),
    # Collections (B1).
    ("java.util.AbstractCollection", r".", b(LOOPS)),
    ("java.util.ArrayDeque", r".", d("D255 WorkQueue for FIFO, D264 ScopeStack for LIFO; seeding is an ordered add loop", "WorkQueue.iron", "ScopeStack.iron")),
    ("java.util.Deque", r".", d("D264 ScopeStack (LIFO) or D255 WorkQueue (FIFO)", "ScopeStack.iron", "WorkQueue.iron")),
    ("java.util.ArrayList", r"^ArrayList\(\)$", a("ds/ArrayList.iron", r"public ArrayList\(\)")),
    ("java.util.ArrayList", r"^ArrayList\(int\)$", a("ds/ArrayList.iron", r"public ArrayList\(int initialCapacity\)")),
    ("java.util.ArrayList", r"^ArrayList\(java.util.Collection", d("D248 ArrayList.copy, or an ordered add loop from another container", "stdlib:ds/ArrayList.iron")),
    ("java.util.ArrayList", r"^add\(E\)$", a("ds/ArrayList.iron", r"public void add\(E element\)")),
    ("java.util.ArrayList", r"^addAll", b(LOOPS)),
    ("java.util.ArrayList", r"^get\(int\)$", a("ds/ArrayList.iron", r"public E get\(int index\)")),
    ("java.util.ArrayList", r"^isEmpty", a("ds/ArrayList.iron", r"public boolean isEmpty\(")),
    ("java.util.ArrayList", r"^size", a("ds/ArrayList.iron", r"public int size\(")),
    ("java.util.ArrayList", r"^sort", d("D263 sortWithComparator", "stdlib:ds/ArrayList.iron")),
    ("java.util.List", r"^sort", d("D263 sortWithComparator", "stdlib:ds/ArrayList.iron")),
    ("java.util.Arrays", r"^<T>stream", b(LOOPS)),
    ("java.util.Arrays", r"^<T>asList", b("B: fixed-arity Lists.of or a builder (D264)")),
    ("java.util.Arrays", r"^compareUnsigned", d("Bytes.compareUnsigned (D268)", "Bytes.iron")),
    ("java.util.Arrays", r"^copyOfRange", d("Bytes.slice: every caller copies an in-bounds range (D268)", "Bytes.iron")),
    ("java.util.BitSet", r"^BitSet\(\)$", a("util/BitSet.iron", r"public BitSet\(\)")),
    ("java.util.BitSet", r"^clear\(int\)$", a("util/BitSet.iron", r"public void clear\(int")),
    ("java.util.BitSet", r"^get\(int\)$", a("util/BitSet.iron", r"public boolean get\(int")),
    ("java.util.BitSet", r"^isEmpty", a("util/BitSet.iron", r"public boolean isEmpty\(")),
    ("java.util.BitSet", r"^nextSetBit", a("util/BitSet.iron", r"public int nextSetBit\(")),
    ("java.util.BitSet", r"^or\(", a("util/BitSet.iron", r"public void or\(")),
    ("java.util.BitSet", r"^set\(int\)$", a("util/BitSet.iron", r"public void set\(int")),
    ("java.util.Collection", r"^clear", a("ds/ArrayList.iron", r"public void clear\(") + ("concrete ds container",)),
    ("java.util.Collection", r"^isEmpty", a("ds/ArrayList.iron", r"public boolean isEmpty\(") + ("concrete ds container",)),
    ("java.util.Collection", r"^iterator", a("ds/ArrayList.iron", r"public Iterator<E> iterator\(") + ("reusable iterator (B1 section 4.3)",)),
    ("java.util.Collection", r"^removeIf", b("B: iterator remove with the reusable iterator (D255) or a filtered rebuild")),
    ("java.util.Collection", r"^stream", b(LOOPS)),
    ("java.util.Collections", r"newSetFromMap", a("ds/IdentityHashSet.iron", r"public IdentityHashSet\(\)") + ("an identity set",)),
    ("java.util.Collections", r"unmodifiableList", a("ds/Collections.iron", r"unmodifiableList\(") + ("live read-only view",)),
    ("java.util.Collections", r"unmodifiable", d("keyed and list snapshots for immutable copies, D249/D254/D264", "SnapshotList.iron", "SnapshotMap.iron", "SnapshotSet.iron")),
    ("java.util.Collections", r"nCopies|reverse", b("B: builder loop in the required order")),
    ("java.util.Comparator", r".", b("B: one named comparator class whose compare keeps the composed key order, with D263 sortWithComparator; never subtraction")),
    ("java.util.HashMap", r"^HashMap\(\)$", a("ds/HashMap.iron", r"public HashMap\(\)")),
    ("java.util.HashMap", r"^containsKey", a("ds/HashMap.iron", r"public boolean containsKey\(")),
    ("java.util.HashMap", r"^put\(K,V\)$", a("ds/HashMap.iron", r"public E put\(")),
    ("java.util.HashMap", r"^isEmpty", a("ds/HashMap.iron", r"public boolean isEmpty\(")),
    ("java.util.HashMap", r"^remove", a("ds/HashMap.iron", r"public E remove\(")),
    ("java.util.HashMap", r"^putAll|^putIfAbsent|^computeIfAbsent", b("B: get, presence test and put; putAll is an iterator loop")),
    ("java.util.HashSet", r"^HashSet\(\)$", a("ds/HashSet.iron", r"public HashSet\(\)")),
    ("java.util.HashSet", r"^HashSet\(java.util.Collection", d("D251 HashSet.copy, or an ordered add loop", "stdlib:ds/HashSet.iron")),
    ("java.util.HashSet", r"^add", a("ds/HashSet.iron", r"public boolean add\(")),
    ("java.util.HashSet", r"^contains", a("ds/HashSet.iron", r"public boolean contains\(")),
    ("java.util.HashSet", r"^isEmpty", a("ds/HashSet.iron", r"public boolean isEmpty\(")),
    ("java.util.HashSet", r"^iterator", a("ds/HashSet.iron", r"public Iterator<E> iterator\(")),
    ("java.util.HashSet", r"^remove", a("ds/HashSet.iron", r"public boolean remove\(")),
    ("java.util.HashSet", r"^size", a("ds/HashSet.iron", r"public int size\(")),
    ("java.util.IdentityHashMap", r"^IdentityHashMap\(\)$", a("ds/IdentityHashMap.iron", r"public IdentityHashMap\(\)")),
    ("java.util.IdentityHashMap", r"^IdentityHashMap\(java.util.Map", d("D250 IdentityHashMap.copy", "stdlib:ds/IdentityHashMap.iron")),
    ("java.util.IdentityHashMap", r"^clear", a("ds/IdentityHashMap.iron", r"public void clear\(")),
    ("java.util.IdentityHashMap", r"^containsKey", a("ds/IdentityHashMap.iron", r"public boolean containsKey\(")),
    ("java.util.IdentityHashMap", r"^get", a("ds/IdentityHashMap.iron", r"public E get\(")),
    ("java.util.IdentityHashMap", r"^put", a("ds/IdentityHashMap.iron", r"public E put\(")),
    ("java.util.IdentityHashMap", r"^remove", a("ds/IdentityHashMap.iron", r"public E remove\(")),
    ("java.util.Iterator", r"^hasNext", a("util/Iterator.iron", r"boolean hasNext\(")),
    ("java.util.Iterator", r"^next", a("util/Iterator.iron", r"E next\(")),
    ("java.util.Iterator", r"^remove", a("util/Iterator.iron", r"void remove\(")),
    ("java.util.LinkedHashMap", r"^LinkedHashMap\(\)$", a("ds/LinkedHashMap.iron", r"public LinkedHashMap\(\)")),
    ("java.util.LinkedHashMap", r"^LinkedHashMap\(java.util.Map", d("D250 LinkedHashMap.copy", "stdlib:ds/LinkedHashMap.iron")),
    ("java.util.LinkedHashMap", r"^clear", a("ds/LinkedHashMap.iron", r"public void clear\(")),
    ("java.util.LinkedHashMap", r"^get\(", a("ds/LinkedHashMap.iron", r"public E get\(")),
    ("java.util.LinkedHashMap", r"^getOrDefault", b("B: get with a null test; values are never null")),
    ("java.util.LinkedHashMap", r"^(entrySet|keySet|values|forEach)", b(ITERATE)),
    ("java.util.LinkedHashSet", r"^LinkedHashSet\(\)$", a("ds/LinkedHashSet.iron", r"public LinkedHashSet\(\)")),
    ("java.util.LinkedHashSet", r"^LinkedHashSet\(java.util.Collection", d("D251 LinkedHashSet.copy, or an ordered add loop", "stdlib:ds/LinkedHashSet.iron")),
    ("java.util.List", r"^<E>copyOf", d("D249 SnapshotList (AstLists freezes AST lists, D261)", "SnapshotList.iron")),
    ("java.util.List", r"^<E>of\(\)$", d("per-type process-lived Lists.empty constant (D258)", "Lists.iron")),
    ("java.util.List", r"^<E>of\(E\)$", d("Lists.single (D258)", "Lists.iron")),
    ("java.util.List", r"^<E>of\(E,E(,E){0,2}\)$", d("Lists.of (D264)", "Lists.iron")),
    ("java.util.List", r"^<E>of\(", b("B: builder filled in argument order, frozen to a SnapshotList (D261)")),
    ("java.util.List", r"^add\(E\)$", a("ds/ArrayList.iron", r"public void add\(E element\)")),
    ("java.util.List", r"^addAll", b(LOOPS)),
    ("java.util.List", r"^clear", a("ds/ArrayList.iron", r"public void clear\(")),
    ("java.util.List", r"^contains\(", a("ds/ArrayList.iron", r"public boolean contains\(")),
    ("java.util.List", r"^containsAll", b(LOOPS)),
    ("java.util.List", r"^equals", d("ArrayList.equals, or Lists.equal for snapshots (D264)", "Lists.iron")),
    ("java.util.List", r"^get\(int\)$", a("ds/ArrayList.iron", r"public E get\(int index\)")),
    ("java.util.List", r"^getFirst|^getLast", b("B: get(0) and get(size() - 1) after the source's emptiness guard")),
    ("java.util.List", r"^indexOf", a("ds/ArrayList.iron", r"public int indexOf\(")),
    ("java.util.List", r"^isEmpty", a("ds/ArrayList.iron", r"public boolean isEmpty\(")),
    ("java.util.List", r"^remove\(java.lang.Object\)$", a("ds/ArrayList.iron", r"public boolean remove\(E element\)")),
    ("java.util.List", r"^removeLast", a("ds/ArrayList.iron", r"public E removeLast\(")),
    ("java.util.List", r"^reversed", b("B: reverse index loop")),
    ("java.util.List", r"^set\(int,E\)$", a("ds/ArrayList.iron", r"public E set\(int index, E element\)")),
    ("java.util.List", r"^size", a("ds/ArrayList.iron", r"public int size\(")),
    ("java.util.List", r"^subList", d("index-range loops, or Lists.prefix when the range becomes a record component (D266)", "Lists.iron")),
    ("java.util.Locale", r"^ROOT$", b("B: ASCII case mapping at the admitted sites; no locale subsystem")),
    ("java.util.Map", r"^<K,V>copyOf", d("D254 SnapshotMap/SnapshotIdentityMap/SnapshotLinkedMap with a D257 key list where traversed", "SnapshotMap.iron", "SnapshotIdentityMap.iron", "SnapshotLinkedMap.iron")),
    ("java.util.Map", r"^<K,V>of\(\)$", b("B: per-type process-lived empty snapshot constant (D258)")),
    ("java.util.Map", r"^<K,V>of\(", b("B: builder put in argument order, then a keyed snapshot")),
    ("java.util.Map", r"^clear", a("ds/HashMap.iron", r"public void clear\(")),
    ("java.util.Map", r"^containsKey", a("ds/HashMap.iron", r"public boolean containsKey\(")),
    ("java.util.Map", r"^containsValue", a("ds/HashMap.iron", r"public boolean containsValue\(")),
    ("java.util.Map", r"^equals", d("map equals, or Maps.equal over snapshots (D264)", "Maps.iron")),
    ("java.util.Map", r"^get\(", a("ds/HashMap.iron", r"public E get\(")),
    ("java.util.Map", r"^isEmpty", a("ds/HashMap.iron", r"public boolean isEmpty\(")),
    ("java.util.Map", r"^put\(K,V\)$", a("ds/HashMap.iron", r"public E put\(")),
    ("java.util.Map", r"^remove", a("ds/HashMap.iron", r"public E remove\(")),
    ("java.util.Map", r"^size", a("ds/HashMap.iron", r"public int size\(")),
    ("java.util.Map", r"^(getOrDefault|putIfAbsent|computeIfAbsent|compute|merge|putAll|replaceAll)", b("B: get, presence test and put in Java's evaluation order; values are never null; bulk forms are iterator loops")),
    ("java.util.Map", r"^(entrySet|keySet|values|forEach)", b(ITERATE)),
    ("java.util.Map.Entry", r".", b(ITERATE)),
    ("java.util.Objects", r"^<T>requireNonNull", a("util/Objects.iron", r"requireNonNull\(")),
    ("java.util.Objects", r"^equals", a("util/Objects.iron", r"public static boolean equals\(")),
    ("java.util.Objects", r"^(isNull|nonNull)", b("B: == null / != null tests")),
    ("java.util.Optional", r".", b(NULLABLE)),
    ("java.util.OptionalInt", r".", b("B: an index with -1 for absent (D258)")),
    ("java.util.Set", r"^<E>copyOf", d("D264 SnapshotSet or D254 SnapshotIdentitySet, with an ordered member list where traversed", "SnapshotSet.iron", "SnapshotIdentitySet.iron")),
    ("java.util.Set", r"^<E>of\(", b("B: per-type empty constant or a builder frozen to a set snapshot")),
    ("java.util.Set", r"^add\(", a("ds/HashSet.iron", r"public boolean add\(")),
    ("java.util.Set", r"^addAll", b(LOOPS)),
    ("java.util.Set", r"^clear", a("ds/HashSet.iron", r"public void clear\(")),
    ("java.util.Set", r"^contains\(", a("ds/HashSet.iron", r"public boolean contains\(")),
    ("java.util.Set", r"^containsAll", b(LOOPS)),
    ("java.util.Set", r"^equals", a("ds/HashSet.iron", r"public boolean equals\(")),
    ("java.util.Set", r"^isEmpty", a("ds/HashSet.iron", r"public boolean isEmpty\(")),
    ("java.util.Set", r"^iterator", a("ds/HashSet.iron", r"public Iterator<E> iterator\(")),
    ("java.util.Set", r"^remove\(", a("ds/HashSet.iron", r"public boolean remove\(")),
    ("java.util.Set", r"^(removeAll|retainAll)", b("B: iterator remove (D255) driven by the other set's contains")),
    ("java.util.Set", r"^size", a("ds/HashSet.iron", r"public int size\(")),
    ("java.util.TreeMap", r".", d("hash or linked container plus StringOrder-sorted key list at each observation (D264)", "StringOrder.iron")),
    ("java.util.TreeSet", r".", d("hash or linked set plus StringOrder-sorted member list at each observation (D264)", "StringOrder.iron")),
    ("java.util.function.BooleanSupplier", r".", d("BooleanSource (D264)", "BooleanSource.iron")),
    ("java.util.function.Consumer", r".", d("Action (D264)", "Action.iron")),
    ("java.util.function.Function", r"^apply", d("Mapper (D264)", "Mapper.iron")),
    ("java.util.function.Function", r"identity", d("IdentityMapper (D264)", "IdentityMapper.iron")),
    ("java.util.function.Predicate", r".", d("Condition (D264)", "Condition.iron")),
    ("java.util.function.Supplier", r".", d("Source (D264)", "Source.iron")),
    ("java.util.function.UnaryOperator", r".", d("IdentityMapper (D264)", "IdentityMapper.iron")),
    ("java.util.function.BiConsumer", r".", d("PairAction (D264)", "PairAction.iron")),
    ("java.util.stream.Stream", r"^sorted", d("materialize by indexed add, then D263 sortWithComparator with a named comparator or StringOrder", "StringOrder.iron")),
    ("java.util.stream.Stream", r"^(min|max)\(", d("Extremes, first equal candidate wins (D264)", "Extremes.iron")),
    ("java.util.stream.", r".", b(LOOPS)),
]


# M4 rules (D270, D271): the native driver's consumers (NativeBackend,
# LlvmToolchain, MacNativeTools, ToolchainDiscovery) and the filesystem
# services delivered for them.
OMITTED_CACHE = "B: a runtime-object cache key field; the native port omits the cache (D269)"
PROCESS_OUTPUT = ("B: B4's runToFile writes a tool's merged output to a log that the probe adapter reads back"
                  " with Files.readString (M4.3)")

# M5 rules (D275, D276): IronClass, IronJar, ClassPayload, StandardLibrary's
# discovery, SourceSetLoader.locateClass and Main.writeClassOutputs, on the
# archive services delivered for them.
ZIP_READ = ("ZipArchive models IronJar's central directory and ZipStream IronClass's local headers; both verify sizes"
            " and CRC and return fresh entry bytes (D275, D276)")
ZIP_WRITE = "ZipWriter spells STORED entries in Java's layout, with Java's fixed entry time (D275)"
WHOLE_ARCHIVE = ("ZipWriter.finish assembles the archive in memory and Files.write stores it: directly for a class"
                 " artifact, through IronJarArchive's stage for an archive (D275, D276)")
STAGE = ("IronJarArchive.publish stages with Files.createTempFile in the destination's parent and deletes the stage"
         " with deleteIfExists on every exit (D270, D276)")
COLLECTOR = "FileCollector: B3's walkFileTree selection of regular files, in Path order (D276)"
OWNED_ARTIFACT = ("IronClassArtifact and IronJarArchive own their parsed names and entries in TextList owners;"
                  " accessors lend or copy them (D276)")
OWNED_ENTRIES = "ArchiveEntries: names and contents by index, written in String order by writeSorted (D276)"
STRICT_TEXT = "TextList.wellFormed checks strict UTF-8 before new String decodes it, IronJar.readText's REPORT (D276)"
LIBRARY = "LibraryTypes owns StandardLibrary's discovered types; LibraryRoots owns its roots (D276)"
SEPARATOR = "B: '/' on every supported host, as java.io.File.separatorChar reports there"
INDEXED_PATHS = "IronJarArchive binary-searches its sorted indexed entry paths (TextList.find, D276)"
M5_ARCHIVE_RULES = [
    ("Array", r"^clone\(\)$", b("B: ClassPayload's defensive copies disappear: ArchiveEntries.add copies each payload once,"
                                 " and an archive's payloads are fresh results (D276)")),
    ("java.io.ByteArrayInputStream", r".", d(ZIP_READ, "ZipStream.iron")),
    ("java.io.ByteArrayOutputStream", r".", d(ZIP_READ, "ZipStream.iron")),
    ("java.io.File", r"^separatorChar$", b(SEPARATOR + "; relative names come from FileCollector.relative (D276)")),
    ("java.io.FilterOutputStream", r"^write\(byte\[\]\)$", d(ZIP_WRITE, "ZipWriter.iron")),
    ("java.io.IOException", r"^IOException\(java.lang.String,java.lang.Throwable\)$",
     b("B: the message alone: the strict UTF-8 check is TextList.wellFormed, so no decoder exception exists to chain"
       " (D276)")),
    ("java.io.InputStream", r"^readAllBytes", d(ZIP_READ, "ZipArchive.iron")),
    ("java.lang.Class", r"^getProtectionDomain", d("Installation with the launcher's canonical location (D269); its"
                                                    " library roots (D276)", "Installation.iron", "LibraryRoots.iron")),
    ("java.lang.String", r"^String\(byte\[\],java.nio.charset.Charset\)$",
     a("lang/String.iron", r"public String\(byte\[\] bytes\)") + ("UTF-8 with U+FFFD replacement, as Java's"
                                                               " new String(bytes, UTF_8)",)),
    ("java.lang.String", r"^lines\(\)$", b("B: IronClassArtifact's String.lines() loop: LF, CR or CRLF end a line and"
                                          " no line follows a final terminator (D276)")),
    ("java.lang.String", r"^trim\(\)$", a("lang/String.iron", r"public String trim\(")),
    ("java.nio.ByteBuffer", r"^wrap\(byte\[\]\)$", d(STRICT_TEXT, "TextList.iron", "IronJarArchive.iron")),
    ("java.nio.CharBuffer", r".", d(STRICT_TEXT, "TextList.iron", "IronJarArchive.iron")),
    ("java.nio.charset.Charset", r"^newDecoder", d(STRICT_TEXT, "TextList.iron", "IronJarArchive.iron")),
    ("java.nio.charset.CharsetDecoder", r".", d(STRICT_TEXT, "TextList.iron", "IronJarArchive.iron")),
    ("java.nio.charset.CodingErrorAction", r".", d(STRICT_TEXT, "TextList.iron", "IronJarArchive.iron")),
    ("java.nio.charset.StandardCharsets", r"^UTF_8$", b("B: UTF-8 is the only charset: String.getBytes() encodes, and"
                                                       " decoding is new String or TextList.wellFormed's strict check"
                                                       " (D276)")),
    ("java.nio.file.Files", r"^(createTempFile|deleteIfExists)\(", d(STAGE, "stdlib:nio/file/Files.iron",
                                                                    "IronJarArchive.iron")),
    ("java.nio.file.Files", r"^move\(", d("IronJar.write's atomic attempt with a replacing fallback is"
                                         " Files.moveReplacing (D271, D276)", "stdlib:nio/file/Files.iron",
                                         "IronJarArchive.iron")),
    ("java.nio.file.StandardCopyOption", r"^(ATOMIC_MOVE|REPLACE_EXISTING)$",
     d("Files.moveReplacing's policy (D271)", "stdlib:nio/file/Files.iron")),
    ("java.nio.file.Files", r"^newOutputStream\(", d(WHOLE_ARCHIVE, "ZipWriter.iron", "IronClassArtifact.iron",
                                                    "IronJarArchive.iron")),
    ("java.nio.file.Files", r"^readAllBytes", a("nio/file/Files.iron", r"public static byte\[\] readAllBytes\(Path path\)")),
    ("java.nio.file.Files", r"^walk\(", d(COLLECTOR, "FileCollector.iron")),
    ("java.nio.file.Path", r"^relativize", d("FileCollector.relative (D276)", "FileCollector.iron")),
    ("java.nio.file.Path", r"^toFile", d("ZipArchive parses the archive's bytes read with Files.readAllBytes (D276)",
                                        "ZipArchive.iron", "IronJarArchive.iron")),
    ("java.nio.file.Path", r"^getFileName", a("nio/file/Path.iron", r"Path getFileName\(\);")),
    ("java.nio.file.Path", r"^toString", a("nio/file/Path.iron", r"String toString\(\);") + ("lent by the path; not"
                                                                                           " freed",)),
    ("java.util.AbstractMap", r"^isEmpty", d(OWNED_ENTRIES, "ArchiveEntries.iron")),
    ("java.util.HashSet", r"^HashSet\(java.util.Collection", d(INDEXED_PATHS, "TextList.iron", "IronJarArchive.iron")),
    ("java.util.Map", r"^values\(\)$", d(INDEXED_PATHS, "TextList.iron", "IronJarArchive.iron")),
    ("java.util.List", r"^<E>of\(\)$", b("B: the caller passes an empty TextList of licenses (D276)")),
    ("java.util.LinkedHashSet", r"^LinkedHashSet\(java.util.Collection", d(LIBRARY, "LibraryTypes.iron")),
    ("java.util.Set", r"^<E>copyOf", d(LIBRARY, "LibraryTypes.iron")),
    ("java.util.List", r"^<E>copyOf", d(OWNED_ARTIFACT + "; " + LIBRARY, "IronJarArchive.iron", "LibraryRoots.iron")),
    ("java.util.Map", r"^<K,V>copyOf", d(OWNED_ARTIFACT, "IronClassArtifact.iron", "IronJarArchive.iron")),
    ("java.util.Map", r"^keySet", d(OWNED_ARTIFACT, "IronClassArtifact.iron", "IronJarArchive.iron")),
    ("java.util.Map", r"^entrySet", d(OWNED_ENTRIES, "ArchiveEntries.iron")),
    ("java.util.Map.Entry", r".", d(OWNED_ENTRIES, "ArchiveEntries.iron")),
    ("java.util.TreeMap", r".", d(OWNED_ENTRIES, "ArchiveEntries.iron")),
    ("java.util.stream.Stream", r"^sorted\(\)$", d("FileCollector and the license list sort Paths in TextList code point"
                                                  " order; collect's single declared-type match needs no order (D276)",
                                                  "FileCollector.iron", "TextList.iron")),
    ("java.util.zip.Checksum", r"^update\(byte\[\]\)$", d(ZIP_WRITE + " and CRC32 (D274)", "ZipWriter.iron",
                                                          "stdlib:util/zip/CRC32.iron")),
    ("java.util.zip.ZipEntry", r"^(getName|isDirectory)\(", d(ZIP_READ + "; a name ending in '/' is a directory, as"
                                                               " ZipEntry.isDirectory", "ZipArchive.iron", "ZipStream.iron")),
    ("java.util.zip.ZipEntry", r".", d(ZIP_WRITE, "ZipWriter.iron")),
    ("java.util.zip.ZipFile", r".", d(ZIP_READ, "ZipArchive.iron", "IronJarArchive.iron")),
    ("java.util.zip.ZipInputStream", r".", d(ZIP_READ, "ZipStream.iron", "IronClassArtifact.iron")),
    ("java.util.zip.ZipOutputStream", r".", d(ZIP_WRITE, "ZipWriter.iron")),
]

# M5.4 rules (D277): DocComment, DocModel, MarkdownDoclet, IronDoc,
# IronDocOptions and IronJarMain on the documentation helpers.
DOC_REGEX = ("DocText's purpose-specific scans: entities, longestBackticks, identifierStart and identifierEnd;"
             " IronDocOptions' path list is Splits.bounds(value, ':', 0) (D277)")
VERSION_RESOURCE = "B: IronDoc's VERSION resource is the generated BuildIdentity (D269, D277)"
FIXED_CASE = "the fixed en_US casing (D117) equals Locale.ROOT for every input (D277)"
DOC_SOURCES = ("FileCollector at depth 1 or unbounded, in Path order; the TreeSet keeps its real paths in TextList"
               " code point order (D276, D277)")
M5_DOC_RULES = [
    ("java.io.File", r"^pathSeparator$", b("B: ':' on every supported host, as System.getProperty(\"path.separator\")"
                                          " reports; IronDocOptions' path list is Splits.bounds(value, ':', 0) (D277)")),
    ("java.io.File", r"^separatorChar$", b(SEPARATOR)),
    ("java.io.InputStream", r"^readAllBytes", b(VERSION_RESOURCE)),
    ("java.lang.Class", r"^getResourceAsStream", b(VERSION_RESOURCE)),
    ("java.lang.String", r"^String\(byte\[\],java.nio.charset.Charset\)$", b(VERSION_RESOURCE)),
    ("java.io.PrintStream", r"^print\(java.lang.String\)$", a("io/PrintStream.iron", r"public void print\(String")),
    ("java.lang.AbstractStringBuilder", r"^setLength\(int\)$", a("lang/StringBuilder.iron",
                                                                 r"public StringBuilder setLength\(int")),
    ("java.lang.Character", r"^isJavaIdentifierPart", d("JavaIdentifiers.isJavaIdentifierPart, Java 21's ranges (D277)",
                                                        "JavaIdentifiers.iron")),
    ("java.lang.Character", r"^isWhitespace\(char\)$", a("lang/Character.iron", r"public static boolean isWhitespace\(char")
     + ("Unicode 15.0, as Java 21",)),
    ("java.lang.Character", r"^toChars", d("DocText.entities (D277)", "DocText.iron")),
    ("java.lang.Integer", r"^parseInt", d("DocText.entities with parseInt's range and diagnostic (D277)", "DocText.iron")),
    ("java.lang.Integer", r"^toHexString", d("DocText.anchor (D277)", "DocText.iron")),
    ("java.lang.String", r"^codePoints\(\)$", d("DocText.anchor (D277)", "DocText.iron")),
    ("java.lang.StringBuilder", r"^appendCodePoint", d("DocText.anchor (D277)", "DocText.iron")),
    ("java.util.stream.IntStream", r"^forEach", d("DocText.anchor (D277)", "DocText.iron")),
    ("java.lang.String", r"^formatted\(", b("B: MarkdownDoclet's banner concatenates its literal pieces (D277)")),
    ("java.lang.String", r"^matches\(", d("DocText.isPackageName, IronDoc's package-name regex (D277)", "DocText.iron")),
    ("java.lang.String", r"^regionMatches\(boolean", d("DocText.regionMatchesIgnoreCase (D277)", "DocText.iron")),
    ("java.lang.String", r"^replaceAll\(", d("DocText.removeSpace and collapseLineBreaks (D277)", "DocText.iron")),
    ("java.lang.String", r"^replaceFirst\(", d("DocText.stripCommentStar and stripCodeTags (D277)", "DocText.iron")),
    ("java.lang.String", r"^split\(", d("DocText.lineFields, head and tail; IronDocOptions' ':' lists are D266"
                                       " Splits.bounds with limit -1 (D277)", "DocText.iron", "Splits.iron")),
    ("java.lang.String", r"^stripLeading\(\)$", d("DocText.stripLeading (D277)", "DocText.iron")),
    ("java.lang.String", r"^toLowerCase\(java.util.Locale\)$", a("lang/String.iron", r"public String toLowerCase\(\)")
     + (FIXED_CASE,)),
    ("java.lang.String", r"^toUpperCase\(java.util.Locale\)$", a("lang/String.iron", r"public String toUpperCase\(\)")
     + (FIXED_CASE,)),
    ("java.util.Locale", r"^ROOT$", b("B: " + FIXED_CASE + "; no locale subsystem")),
    ("java.lang.StringBuilder", r"^StringBuilder\(java.lang.String\)$",
     b("B: new StringBuilder() then append: a builder made from a String cannot be freed (M5 known limit)")),
    ("java.lang.StringBuilder", r"^append\(java.lang.CharSequence,int,int\)$",
     a("lang/StringBuilder.iron", r"public StringBuilder append\(CharSequence text, int start, int end\)")),
    ("java.nio.charset.StandardCharsets", r"^UTF_8$", b("B: UTF-8 is the only charset: Files.readString and"
                                                       " writeString are UTF-8, and the version text is BuildIdentity"
                                                       " (D269)")),
    ("java.nio.file.Files", r"^walk\(", d(DOC_SOURCES, "FileCollector.iron", "TextList.iron")),
    ("java.util.TreeSet", r".", d(DOC_SOURCES, "FileCollector.iron", "TextList.iron")),
    ("java.util.stream.Stream", r"^sorted\(\)$", d(DOC_SOURCES, "FileCollector.iron", "TextList.iron")),
    ("java.util.TreeMap", r".", d("String keys sorted with StringOrder at each observation (D264); MarkdownDoclet's"
                                  " Path-keyed pages in TextList code point order (D276, D277)", "StringOrder.iron",
                                  "TextList.iron")),
    ("java.nio.file.Path", r"^equals", a("nio/file/UnixPath.iron", r"public boolean equals\(Object other\)")),
    ("java.nio.file.Path", r"^getFileName", a("nio/file/Path.iron", r"Path getFileName\(\);")
     + ("DocModel.parse reads SourceFile.fileName",)),
    ("java.nio.file.Path", r"^relativize", a("nio/file/Path.iron", r"Path relativize\(Path other\);")),
    ("java.nio.file.Path", r"^toRealPath", d("Path.toRealPath (D270)", "stdlib:nio/file/Path.iron")),
    ("java.nio.file.Path", r"^toString", a("nio/file/Path.iron", r"String toString\(\);") + ("lent by the path; not"
                                                                                           " freed",)),
    ("java.util.ArrayDeque", r".", d("ScopeStack, DocComment.render's list stack (D264, D277)", "ScopeStack.iron")),
    ("java.util.List", r"^add\(int,E\)$", a("ds/ArrayList.iron", r"public void insert\(int index, E element\)")
     + ("ArrayList.insert",)),
    ("java.util.function.BiFunction", r"^apply", d("LinkRenderer.link (D277)", "LinkRenderer.iron")),
    ("java.util.regex.Pattern", r"^quote", b("B: the literal ':' delimiter of D266 Splits.bounds, the host path"
                                            " separator")),
    ("java.util.regex.", r".", d(DOC_REGEX, "DocText.iron", "Splits.iron")),
]

# M6.1 rules (D291-D293): the Bridge's standalone producer, packaging,
# assembly, distribution and generator consumers (S7) on the helpers
# delivered for them and on the M3-M5 services.
PRODUCER = ("B: the Java compiler's own class or jar inventory is the producer identity; S7 replaces it with its"
            " separate native producer-manifest design (tracked in M6.2), never a synthetic Main.class entry")
JDK = ("B: the running JVM is the Java producer's JDK (D239); the native producer runs the selected JDK's tools by"
       " absolute path through runToFile (D272) and takes its home, version and vendor from M6.2's JDK selection")
TREE = ("TextMap for String-to-String inventories (D292), ArchiveEntries for named contents (D276), otherwise D264's"
        " container with a StringOrder key list")
M6_RULES = [
    ("Array", r"^Array\(int\)$", b("B: javac and javadoc arguments are a Command (D273) passed to runToFile; no array is built")),
    ("Array", r"^clone\(\)$", b("B: ArchiveEntries.add copies each entry's contents once; readers return fresh bytes (D276)")),
    ("java.io.ByteArrayInputStream", r".", b("B: the readers take byte ranges: BridgeProperties.load, new JarManifest(bytes, offset,"
                                              " length) and ZipStream over the values companion (D276, D292)")),
    ("java.io.ByteArrayOutputStream", r".", d("JarManifest.write returns the manifest bytes (D292)", "JarManifest.iron")),
    ("java.io.File", r"^separatorChar$", b(SEPARATOR)),
    ("java.io.FilterOutputStream", r"^write\(", d("ZipWriter.add writes each STORED entry for BridgeJar (D275, D294)",
                                                  "ZipWriter.iron", "BridgeJar.iron")),
    ("java.io.IOException", r"^IOException\(java.lang.String,java.lang.Throwable\)$",
     a("io/IOException.iron", r"public IOException\(String message, Throwable cause\)")
     + ("the interrupted-tool and producer-identity causes disappear with their callers' Java mechanisms",)),
    ("java.io.InputStream", r"^readAllBytes\(\)$", d("ZipArchive.read and ZipStream.next return each entry's fresh, verified bytes (D276)",
                                                     "ZipArchive.iron", "ZipStream.iron")),
    ("java.lang.Boolean", r"^toString\(boolean\)$", a("lang/Boolean.iron", r"public static String toString\(boolean")),
    ("java.lang.CharSequence", r"^isEmpty\(\)$", a("lang/StringBuilder.iron", r"public boolean isEmpty\(")
     + ("the receivers are cleanup StringBuilders, read without mutation",)),
    ("java.lang.Character", r"^toUpperCase\(char\)$", a("lang/Character.iron", r"public static char toUpperCase\(char")
     + ("only a primitive type name is capitalized, for JNI's Get<Kind>ArrayRegion (D293)",)),
    ("java.lang.Class", r"^getProtectionDomain\(\)$", b(PRODUCER)),
    ("java.lang.Double", r"^toHexString\(double\)$", d("BridgeText.doubleHex (D293)", "BridgeText.iron")),
    ("java.lang.Enum", r"^toString\(\)$", a("lang/Enum.iron", r"public String toString\(") + ("the constant's name",)),
    ("java.lang.Float", r"^(NEGATIVE|POSITIVE)_INFINITY$", a("lang/Float.iron", r"public static final float (NEGATIVE|POSITIVE)_INFINITY")),
    ("java.lang.Float", r"^floatToRawIntBits\(float\)$", b("B: the native constant keeps a float constant's raw bits as an int payload,"
                                                            " as D268 kept double payloads; no public Float method is added (D293)")),
    ("java.lang.Float", r"^isNaN\(float\)$", a("lang/Float.iron", r"public static boolean isNaN\(float")),
    ("java.lang.Float", r"^toHexString\(float\)$", d("BridgeText.floatHex (D293)", "BridgeText.iron")),
    ("java.lang.IllegalArgumentException", r"^IllegalArgumentException\(java.lang.String,java.lang.Throwable\)$",
     a("lang/IllegalArgumentException.iron", r"public IllegalArgumentException\(String message, Throwable cause\)")),
    ("java.lang.Integer", r"^BYTES$", a("lang/Integer.iron", r"public static final int BYTES")),
    ("java.lang.Integer", r"^parseInt\(java.lang.String\)$", a("lang/Integer.iron", r"public static int parseInt\(String text\)")
     + ("the parsed parts are ReadelfScan's ASCII digit runs, and an empty part fails in both",)),
    ("java.lang.Integer", r"^toHexString\(int\)$", a("lang/Integer.iron", r"public static String toHexString\(int")
     + ("unsigned lowercase digits, as Java",)),
    ("java.lang.Long", r"^toHexString\(long\)$", a("lang/Long.iron", r"public static String toHexString\(long")
     + ("unsigned lowercase digits, as Java",)),
    ("java.lang.Runtime", r".", b(JDK)),
    ("java.lang.Runtime.Version", r".", b(JDK)),
    ("java.lang.String", r"^String\(byte\[\],int,int,java.nio.charset.Charset\)$",
     d("BridgeMacPayload's dependency names: TextList.wellFormed is the UTF-8 round trip, then new String of a Bytes.slice"
       " (D268, D276)", "TextList.iron", "Bytes.iron")),
    ("java.lang.String", r"^String\(byte\[\],java.nio.charset.Charset\)$", b(PRODUCER)),
    ("java.lang.String", r"^format\(", d("BridgeText.appendOctalEscape and appendUnicodeEscape for \\%03o and \\u%04x;"
                                         " Locale.ROOT does not change digits (D293)", "BridgeText.iron")),
    ("java.lang.String", r"^matches\(", d("BridgePatterns' scans and BridgeIdentity.isHash, one per pattern (D292, D293)",
                                          "BridgePatterns.iron", "BridgeIdentity.iron")),
    ("java.lang.String", r"^stripTrailing\(\)$", d("BridgeText.stripTrailing (D293)", "BridgeText.iron")),
    ("java.lang.String", r"^trim\(\)$", b(PRODUCER)),
    ("java.lang.StringBuilder", r"^StringBuilder\(java.lang.String\)$",
     b("B: new StringBuilder() then append: a builder made from a String cannot be freed (M5 known limit)")),
    ("java.lang.String", r"^toLowerCase\(java.util.Locale\)$", a("lang/String.iron", r"public String toLowerCase\(\)")
     + ("a primitive JNI descriptor letter for its jvalue member: ASCII, where the fixed casing equals Locale.ROOT",)),
    ("java.lang.StringBuilder", r"^insert\(int,java.lang.String\)$", a("lang/StringBuilder.iron", r"public StringBuilder insert\(int offset, String text\)")
     + ("the five cleanup prepends at offset 0 pass non-null text",)),
    ("java.lang.System", r"^getProperty\(", b("B: os.name and os.arch through System.getProperty, as each caller tests them;"
                                              " java.home, java.runtime.version and java.vendor are the JDK selection's (M6.2)")),
    ("java.lang.System", r"^getenv\(\)$", a("lang/System.iron", r"public static String getenv\(String name\)")
     + ("getenv(name) with null for absence; getOrDefault's default is the absence branch",)),
    ("java.lang.Thread", r".", b("B: no interruption: a JDK tool runs through runToFile, which waits and always reaps; a terminal"
                                 " interrupt reaches the tool through its process group (D272)")),
    ("java.net.URL", r".", b(PRODUCER)),
    ("java.nio.ByteBuffer", r"^(allocate|array|putInt)", d("BridgeIdentity frames each count as four big-endian bytes (D292)",
                                                           "BridgeIdentity.iron")),
    ("java.nio.charset.StandardCharsets", r"^US_ASCII$", d("BridgeProperties.serialize writes its escaped ASCII text byte for byte (D292)",
                                                         "BridgeProperties.iron")),
    ("java.nio.file.Files", r"^copy\(", a("nio/file/Files.iron", r"public static Path copy\(Path source, Path target\)")
     + ("refuses an existing target, as Java without options",)),
    ("java.nio.file.Files", r"^createTempDirectory\(", d("Files.createTempDirectory in the destination's parent (D270)",
                                                         "stdlib:nio/file/Files.iron")),
    ("java.nio.file.Files", r"^createTempFile\(", d("Files.createTempFile(parent, \".ironwood-bridge-\", \".jar\") (D270)",
                                                    "stdlib:nio/file/Files.iron")),
    ("java.nio.file.Files", r"^delete\(", d("TreeDeletion's post-order staging cleanup (D270)", "TreeDeletion.iron")),
    ("java.nio.file.Files", r"^deleteIfExists\(", d("Files.deleteIfExists removes the stage on every exit (D270)",
                                                    "stdlib:nio/file/Files.iron")),
    ("java.nio.file.Files", r"^(exists|isRegularFile)\(", b("B: Files.exists and isRegularFile; with NOFOLLOW_LINKS,"
                                                            " Files.isSymbolicLink adds the final link: exists becomes"
                                                            " isSymbolicLink or exists, isRegularFile not isSymbolicLink and"
                                                            " isRegularFile, as BridgeJar checks its destination (D294)")),
    ("java.nio.file.Files", r"^isReadable\(", a("nio/file/Files.iron", r"public static boolean isReadable\(Path path\)")),
    ("java.nio.file.Files", r"^list\(", d("FileCollector at depth one, in Path order (D276, D277, D293)", "FileCollector.iron")),
    ("java.nio.file.Files", r"^move\(", d("moveAtomicReplacing publishes a verified Bridge jar; moveAtomicNoReplace publishes a new"
                                          " distribution directory (D271)", "stdlib:nio/file/Files.iron")),
    ("java.nio.file.Files", r"^newOutputStream\(", d("BridgeJar stages the jar ZipWriter.finish assembles with Files.write"
                                                     " (D275, D294)", "BridgeJar.iron")),
    ("java.nio.file.Files", r"^readAllBytes\(", a("nio/file/Files.iron", r"public static byte\[\] readAllBytes\(Path path\)")),
    ("java.nio.file.Files", r"^walk\(", d("SourceFiles and FileCollector for inventories, TreeDeletion for cleanups (D270, D276,"
                                          " D293)", "SourceFiles.iron", "FileCollector.iron", "TreeDeletion.iron")),
    ("java.nio.file.Files", r"^write\(", a("nio/file/Files.iron", r"public static Path write\(Path path, byte\[\] bytes\)")),
    ("java.nio.file.LinkOption", r".", b("B: Files.isSymbolicLink tests the final component without following it (D294)")),
    ("java.nio.file.Path", r"^getFileName\(\)$", a("nio/file/Path.iron", r"Path getFileName\(\);")),
    ("java.nio.file.Path", r"^of\(java.net.URI\)$", b(PRODUCER)),
    ("java.nio.file.Path", r"^relativize\(", d("FileCollector.relative and SourceFiles.relative (D276, D293)", "FileCollector.iron",
                                               "SourceFiles.iron")),
    ("java.nio.file.Path", r"^toFile\(\)$", b("B: runToFile takes Paths (D272) and ZipArchive reads bytes from Files.readAllBytes"
                                              " (D276)")),
    ("java.nio.file.Path", r"^toString\(\)$", a("nio/file/Path.iron", r"String toString\(\);") + ("lent, as M5 recorded",)),
    ("java.nio.file.StandardCopyOption", r".", d("BridgeJar publishes with Files.moveAtomicReplacing, without fallback (D271,"
                                                 " D294)", "stdlib:nio/file/Files.iron", "BridgeJar.iron")),
    ("java.security.CodeSource", r".", b(PRODUCER)),
    ("java.security.MessageDigest", r".", d("BridgeIdentity over Sha256 (D267, D292)", "BridgeIdentity.iron", "Sha256.iron")),
    ("java.security.ProtectionDomain", r".", b(PRODUCER)),
    ("java.util.AbstractMap", r"^isEmpty\(\)$", d("TextMap.size() == 0 (D292)", "TextMap.iron")),
    ("java.util.AbstractSet", r"^equals\(", a("ds/HashSet.iron", r"public boolean equals\(Object")),
    ("java.util.ArrayList", r"^addFirst\(", a("ds/ArrayList.iron", r"public void addFirst\(E element\)")),
    ("java.util.ArrayList", r"^contains\(", a("ds/ArrayList.iron", r"public boolean contains\(E element\)")),
    ("java.util.ArrayList", r"^forEach\(", b(LOOPS)),
    ("java.util.ArrayList", r"^getFirst\(\)$", b("B: get(0) after the source's emptiness guard")),
    ("java.util.Arrays", r"^equals\(byte\[\],byte\[\]\)$", a("util/Arrays.iron", r"public static boolean equals\(byte\[\] first")),
    ("java.util.Collection", r"^<T>toArray\(", b("B: javac and javadoc arguments are a Command (D273) passed to runToFile")),
    ("java.util.Comparator", r"^<T>reverseOrder\(\)$", d("TreeDeletion: the reverse-ordered deletes are post-order cleanup (D270)",
                                                         "TreeDeletion.iron")),
    ("java.util.HashMap", r"^get\(", a("ds/HashMap.iron", r"public E get\(")),
    ("java.util.HashMap", r"^size\(\)$", a("ds/HashMap.iron", r"public int size\(")),
    ("java.util.HexFormat", r".", d("Sha256.hexDigest through BridgeIdentity.digest: every site formats a whole SHA-256 result"
                                    " (D267, D292)", "Sha256.iron", "BridgeIdentity.iron")),
    ("java.util.List", r"^iterator\(\)$", a("ds/ArrayList.iron", r"public Iterator<E> iterator\(") + ("reusable iterator (B1 section 4.3)",)),
    ("java.util.Objects", r"^hash\(", b("B: Java's Objects.hash formula, 31 * result + each component's hash from 1, in argument"
                                        " order (D264's value helpers for the components)")),
    ("java.util.Properties", r".", d("BridgeProperties.load into a TextMap, Properties.load's semantics with the canonical check (D292)",
                                     "BridgeProperties.iron", "TextMap.iron")),
    ("java.util.TreeMap", r".", d(TREE, "TextMap.iron", "ArchiveEntries.iron", "StringOrder.iron")),
    ("java.util.jar.Attributes", r".", d("JarManifest.putValue and getValue (D292)", "JarManifest.iron")),
    ("java.util.jar.JarInputStream", r".", d("JarStreams.manifest: the first entry, or the second after META-INF/, read by"
                                             " ZipStream and parsed by JarManifest (D294)", "JarStreams.iron", "ZipStream.iron",
                                             "JarManifest.iron")),
    ("java.util.jar.Manifest", r".", d("JarManifest: Manifest.write's bytes and Java's reading verdicts (D292)", "JarManifest.iron")),
    ("java.util.regex.", r".", d("ReadelfScan: the four llvm-readelf searches with Matcher.find's semantics (D293)", "ReadelfScan.iron")),
    ("java.util.zip.ZipEntry", r".", d("ZipWriter entries with Java's fixed time; ZipArchive and ZipStream names, sizes and"
                                       " directory flags (D275, D276)", "ZipWriter.iron", "ZipArchive.iron", "ZipStream.iron")),
    ("java.util.zip.ZipFile", r".", d("ZipArchive over the paired jar's bytes, and BridgeJar's read-back verification of"
                                      " its stage (D276, D294)", "ZipArchive.iron", "BridgeJar.iron")),
    ("java.util.zip.ZipInputStream", r".", d("ZipStream (D276)", "ZipStream.iron")),
    ("java.util.zip.ZipOutputStream", r".", d("BridgeJar: the manifest first, then String order, STORED in Java's spelling"
                                              " through ZipWriter (D275, D294)", "BridgeJar.iron", "ZipWriter.iron")),
    ("javax.lang.model.SourceVersion", r".", d("JavaNames.isName for Java 21 and BridgeExports' two export loops (D291)",
                                               "JavaNames.iron", "BridgeExports.iron")),
]

PHASE_RULES = {
    "M4.1-M4.2": [
        ("java.io.InputStream", r"^readAllBytes", b(PROCESS_OUTPUT)),
        ("java.lang.String", r"^String\(byte\[\],java.nio.charset.Charset\)$", b(PROCESS_OUTPUT)),
        ("java.lang.Thread", r".", b("B: no interruption: a launch is B4's synchronous runner (M4.3), and a signal"
                                     " reaches the tool through the compiler's process group")),
        ("java.lang.Integer", r"^parseInt\(java.lang.String\)$", a("lang/Integer.iron", r"public static int parseInt\(String text\)")),
        ("java.lang.String", r"^lines\(\)$", b("B: the first line ends at the first '\\n' or '\\r', String.lines' terminators"
                                              " (indexOf and substring)")),
        ("java.nio.file.Files", r"^createTempDirectory", d("Files.createTempDirectory, explicit and default directory (D270)",
                                                          "stdlib:nio/file/Files.iron")),
        ("java.nio.file.Files", r"^deleteIfExists", d("Files.deleteIfExists (D270)", "stdlib:nio/file/Files.iron")),
        ("java.nio.file.Files", r"^isExecutable", d("Files.isExecutable (D270)", "stdlib:nio/file/Files.iron")),
        ("java.nio.file.Files", r"^isReadable", d("Files.isReadable (D270)", "stdlib:nio/file/Files.iron")),
        ("java.nio.file.Files", r"^walk\(", d("TreeDeletion replaces deleteTree's reverse-sorted walk; the runtime-header"
                                             " walk belongs to the omitted runtime-object cache (D269, D270)",
                                             "TreeDeletion.iron")),
        ("java.nio.file.Files", r"^(getLastModifiedTime|size)\(", b(OMITTED_CACHE)),
        ("java.nio.file.attribute.FileTime", r"^toMillis", b(OMITTED_CACHE)),
        ("java.nio.file.Files", r"^readAllBytes", a("nio/file/Files.iron", r"public static byte\[\] readAllBytes\(Path path\)")),
        ("java.nio.file.Files", r"^write\(java.nio.file.Path,byte\[\]", a("nio/file/Files.iron",
                                                                           r"public static Path write\(Path path, byte\[\] bytes\)")),
        ("java.nio.file.Path", r"^toRealPath", d("Path.toRealPath (D270)", "stdlib:nio/file/Path.iron")),
        ("java.nio.file.Path", r"^getFileName", a("nio/file/Path.iron", r"Path getFileName\(\);")),
        ("java.nio.file.Path", r"^toString", a("nio/file/Path.iron", r"String toString\(\);")),
        ("java.util.Comparator", r"^<T>reverseOrder", d("TreeDeletion's post-order walk replaces deleteTree's reverse sort"
                                                       " (D270)", "TreeDeletion.iron")),
        ("java.util.stream.Stream", r"^sorted\(java.util.Comparator", d("TreeDeletion's post-order walk replaces deleteTree's"
                                                                       " reverse sort (D270)", "TreeDeletion.iron")),
        ("java.util.stream.Stream", r"^sorted\(\)$", b("B: the sorted runtime-header walk is a cache key field; the native port"
                                                     " omits the cache (D269)")),
    ],
    # M4.3 (D272, D273): the synchronous process facility and the port's adapters.
    "M4.3": [
        ("java.lang.ProcessBuilder", r"^ProcessBuilder\(java.util.List", d("Command owns the argument vector (D273) and"
                                                                          " ProcessRunner.runToFile launches it by absolute"
                                                                          " path (D272)", "Command.iron",
                                                                          "stdlib:process/ProcessRunner.iron")),
        ("java.lang.ProcessBuilder", r"^directory\(", d("runToFile's directory argument (D272)",
                                                       "stdlib:process/ProcessRunner.iron")),
        ("java.lang.ProcessBuilder", r"^redirectErrorStream\(", d("runToFile merges standard output and error into its"
                                                                 " output file (D272)", "stdlib:process/ProcessRunner.iron")),
        ("java.lang.ProcessBuilder", r"^redirectOutput\(", d("runToFile's output file (D272)",
                                                            "stdlib:process/ProcessRunner.iron")),
        ("java.lang.ProcessBuilder", r"^start\(", d("ProcessRunner.runToFile (D272)", "stdlib:process/ProcessRunner.iron")),
        ("java.lang.Process", r"^waitFor\(", d("runToFile waits and always reaps (D272)",
                                              "stdlib:process/ProcessRunner.iron")),
        ("java.lang.Process", r"^getInputStream\(", d("Probes reads a probe's log back within 1 MiB and LlvmPipeline a"
                                                     " failed stage's (D273)", "Probes.iron", "LlvmPipeline.iron")),
        ("java.lang.Process", r"^destroyForcibly\(", b("B: only BridgeBuildTools' thread interruption kills a tool; the"
                                                      " native producer has no interruption, and a terminal interrupt"
                                                      " reaches the tool through its process group (D272)")),
        ("javax.tools.", r".", b("B: S7's native producer runs the selected JDK's javac and javadoc by absolute path through"
                                 " runToFile, as BridgeBuildTools.run already launches tools; selecting that JDK is M6.2")),
    ],
    # M5.1 (D274): IronJar.write's entry checksums.
    "M5.1": [
        ("java.util.zip.CRC32", r".", d("the public ironwood.util.zip.CRC32 (D274); ZipWriter computes each STORED entry's"
                                        " CRC with it (D275)", "stdlib:util/zip/CRC32.iron", "ZipWriter.iron")),
    ],
    "M5.2-M5.3": M5_ARCHIVE_RULES,
    "M5.4": M5_DOC_RULES,
    "M6.1": M6_RULES,
}


def load(phase):
    calls = json.load(gzip.open(ROOT / "docs/self-hosting/m0/deferred/calls.json.gz"))
    api = {row["id"]: row for row in json.load(gzip.open(ROOT / "docs/self-hosting/m0/deferred/api.json.gz"))}
    patterns = defaultdict(list)
    for call in calls:
        first = call["gate"]["preparation"].split(" ")[0]
        if first == phase or phase == "M6.1" and first == "reuse":
            patterns[call["pattern_id"]].append(call)
    return api, patterns


def member(signature):
    return signature


def classify(owner, signature, phase=None):
    for rule_owner, regex, verdict in PHASE_RULES.get(phase, []) + RULES:
        if (owner == rule_owner or rule_owner.endswith(".") and owner.startswith(rule_owner)) and re.search(regex, signature):
            return (rule_owner, regex), verdict
    return None, None


COMPILER = ROOT / "compiler/src/main/ironwood/ironwood/compiler"


def resolve(file, default):
    for prefix, base in (("stdlib:", STDLIB), ("port:", PORT), ("compiler:", COMPILER)):
        if file.startswith(prefix):
            return base / file[len(prefix):]
    return default / file


def check(verdict):
    kind = verdict[0]
    if kind == "A":
        path = resolve(verdict[1], STDLIB)
        if not path.exists() or not re.search(verdict[2], path.read_text(encoding="utf-8")):
            return f"A declaration /{verdict[2]}/ missing in {path}"
    if kind == "D":
        for file in verdict[2]:
            if not resolve(file, PORT).exists():
                return f"D file {file} missing"
    if kind == "P":
        return f"pending: {verdict[1]}"
    return None


def text(verdict):
    kind = verdict[0]
    if kind == "A":
        note = f"; {verdict[3]}" if len(verdict) > 3 else ""
        return f"A: `{verdict[1]}`{note}"
    if kind == "B":
        return verdict[1]
    if kind == "D":
        return "D: " + verdict[1]
    return "pending: " + verdict[1]


def main():
    phase = sys.argv[1]
    api, patterns = load(phase)
    rows, failures, used = [], [], set()
    counts, pattern_counts = Counter(), Counter()
    for pattern_id, calls in sorted(patterns.items(), key=lambda item: (api[item[0]]["owner"], api[item[0]]["signature"])):
        row = api[pattern_id]
        rule, verdict = classify(row["owner"], row["signature"], phase)
        if verdict is None:
            failures.append(f"{pattern_id} {row['owner']} {row['signature']}: no rule")
            continue
        used.add(rule)
        problem = check(verdict)
        if problem:
            failures.append(f"{pattern_id} {row['owner']} {row['signature']}: {problem}")
        consumers = sorted({call["consumer"].split("::")[0].split(".")[-1] for call in calls})
        counts[verdict[0]] += len(calls)
        pattern_counts[verdict[0]] += 1
        rows.append(f"| {pattern_id} | `{row['owner'].replace('java.util.', '').replace('java.lang.', '')}.{row['signature']}` "
                    f"| {len(calls)} | {', '.join(consumers[:4])}{' ...' if len(consumers) > 4 else ''} | {text(verdict)} |")
    total = sum(counts.values())
    summary = (f"{phase}: {total} calls in {len(patterns)} patterns; "
               + ", ".join(f"{kind} {counts[kind]} calls ({pattern_counts[kind]} patterns)" for kind in "ABDP"))
    print(summary)
    if "--markdown" in sys.argv:
        out = Path(sys.argv[sys.argv.index("--markdown") + 1])
        header = ("| Pattern | Java API | Calls | Consumers | Class and treatment |\n"
                  "| --- | --- | --- | --- | --- |\n")
        out.write_text("<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->\n"
                       f"<!-- Generated by docs/self-hosting/m3/classify.py {phase}; do not edit. -->\n\n"
                       f"{summary}\n\n" + header + "\n".join(rows) + "\n", encoding="utf-8")
    for failure in failures:
        print("FAIL", failure)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
