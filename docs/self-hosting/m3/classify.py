# SPDX-License-Identifier: MIT OR Apache-2.0
"""Classify every M3 call pattern of the M0 source-backed inventory.

Usage: classify.py PHASE [--markdown OUT]

Each API pattern that docs/self-hosting/m0/deferred assigns to the phase
gets exactly one class:

  A  an existing Ironwood member with the needed semantics; the rule names
     the stdlib or port file and a declaration regex, which must match;
  B  a recorded port convention (decision or record cited), no new helper;
  D  a helper delivered in M3 (decision cited); its file must exist.

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
    ("java.lang.Byte", r"^toUnsignedInt", pending("M3.3 unsigned widening")),
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
    ("java.util.Arrays", r"^compareUnsigned|^copyOfRange", pending("M3.3 binary helpers")),
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


def load(phase):
    calls = json.load(gzip.open(ROOT / "docs/self-hosting/m0/deferred/calls.json.gz"))
    api = {row["id"]: row for row in json.load(gzip.open(ROOT / "docs/self-hosting/m0/deferred/api.json.gz"))}
    patterns = defaultdict(list)
    for call in calls:
        if call["gate"]["preparation"].split(" ")[0] == phase:
            patterns[call["pattern_id"]].append(call)
    return api, patterns


def member(signature):
    return signature


def classify(owner, signature):
    for rule_owner, regex, verdict in RULES:
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
        rule, verdict = classify(row["owner"], row["signature"])
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
