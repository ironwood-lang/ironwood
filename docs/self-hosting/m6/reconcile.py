# SPDX-License-Identifier: MIT OR Apache-2.0
"""Reconcile every item of the M0 source-backed inventory for M6.3.

Usage: reconcile.py [--write]

The items are the pinned M0 ledgers in docs/self-hosting/m0/deferred (calls,
syntax sites, captured symbols, hash origins, traversals and contributions,
native consumer gates and the edges excluded from the source-only route) and
the declarations of the frontend and operation models. Each item gets exactly
one disposition: its preparation treatment, taken from its phase's record
(the M1 table, or the classify.py table of M3.1 to M6.1, for a call; a
recorded convention for every other row), and one owner:

  qualified  implemented in the S1 pilot and qualified at G1;
  bounded    qualified at G1 only under the pilot's bounded input; the general
             consumer belongs to a later stage;
  prepared   the preparation contract is delivered; the stage named ports the
             exact consumer and runs the item's M0 fixture;
  driver     delivered by M4 for the optional native-driver route;
  identity   S7's native producer-identity design;
  excluded   an edge the source-only route excludes, owned by a later stage.

Each owner states its reason, first affected stage and blocking effect.

The tool fails when an input differs from its M0 manifest, an item has no
treatment or two, an M1 table row does not account for its calls, a phase
table no longer regenerates as recorded, or a cited record or decision is
missing. With --write it writes RECONCILIATION.md and reconciliation.json.gz;
otherwise it compares them with the recorded files (the ledger by its
decompressed JSON, which does not depend on the zlib build).
"""
import contextlib
import gzip
import hashlib
import importlib.util
import io
import json
import re
import sys
import tempfile
from collections import Counter, defaultdict
from pathlib import Path

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
DOCS = ROOT / "docs/self-hosting"
DEFERRED = DOCS / "m0/deferred"
JAVA = "compiler/src/main/java/"

SPEC = importlib.util.spec_from_file_location("classify", DOCS / "m3/classify.py")
classify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(classify)

PHASES = ["M1.1", "M1.2", "M1.3", "M3.1", "M3.2", "M3.3", "M4.1-M4.2", "M4.3", "M5.1", "M5.2-M5.3", "M5.4", "M6.1"]
TABLES = {
    "M3.1": "m3/CLASSIFICATION_M3.1.md", "M3.2": "m3/CLASSIFICATION_M3.2.md", "M3.3": "m3/CLASSIFICATION_M3.3.md",
    "M4.1-M4.2": "m4/CLASSIFICATION_M4.1-M4.2.md", "M4.3": "m4/CLASSIFICATION_M4.3.md",
    "M5.1": "m5/CLASSIFICATION_M5.1.md", "M5.2-M5.3": "m5/CLASSIFICATION_M5.2-M5.3.md",
    "M5.4": "m5/CLASSIFICATION_M5.4.md", "M6.1": "m6/CLASSIFICATION_M6.1.md",
}
RECORDS = {
    "M1.1": ["m1/CLASSIFICATION.md", "m1/CHECKPOINT.md"],
    "M1.2": ["m1/CLASSIFICATION.md", "m1/CHECKPOINT.md"],
    "M1.3": ["m1/CLASSIFICATION.md", "m1/CHECKPOINT.md"],
    "M3.1": [TABLES["M3.1"], "m3/HANDOFF_M3.1.md"],
    "M3.2": [TABLES["M3.2"], "m3/HANDOFF_M3.2.md"],
    "M3.3": [TABLES["M3.3"], "m3/HANDOFF_M3.3.md"],
    "M4.1-M4.2": [TABLES["M4.1-M4.2"], "m4/HANDOFF_M4.1-M4.2.md"],
    "M4.3": [TABLES["M4.3"], "m4/HANDOFF_M4.3.md"],
    "M5.1": [TABLES["M5.1"], "m5/HANDOFF_M5.md"],
    "M5.2-M5.3": [TABLES["M5.2-M5.3"], "m5/HANDOFF_M5.md"],
    "M5.4": [TABLES["M5.4"], "m5/HANDOFF_M5.md"],
    "M6.1": [TABLES["M6.1"], "m6/CONTRACTS.md", "m6/JAR.md"],
}
STAGES = ["S2", "S3", "S4", "S6", "S7"]
WORK = {
    "S2": "S2's port of the AST and IR representations, their value semantics and walkers",
    "S3": "S3's port of semantic analysis in dependency order",
    "S4": "S4's port of native code generation and the source-only compiler",
    "S6": "S6's port of the command-line, artifact and documentation tools",
    "S7": "S7's port of Bridge production",
}


def owner_table():
    owners = {
        "qualified": {
            "status": "implemented and qualified",
            "reason": "the S1 pilot ported it and G1 qualified it (equivalence, ownership pairs, missing-free"
                      " classification and budgets)",
            "first_stage": "none", "blocking": "none",
            "evidence": ["m2/CHECKPOINT.md", "m2/FRONTEND.md", "m2/OWNERSHIP.md"],
        },
        "driver": {
            "status": "delivered for the optional native-driver route",
            "reason": "M4 delivered the host services and driver adapters; the source-only S4 route keeps its"
                      " qualified shell orchestration",
            "first_stage": "S4 if it elects the native driver, otherwise S6",
            "blocking": "none for the shell-driver route; a native driver is admitted only after its adapter"
                        " fixture passes",
            "evidence": ["m4/HANDOFF_M4.1-M4.2.md", "m4/HANDOFF_M4.3.md"],
        },
        "identity S7": {
            "status": "design owned by S7",
            "reason": "the Java producer identifies itself by its own class or jar inventory; S7 designs the"
                      " versioned native producer manifest, and no synthetic Main.class or fabricated compiler"
                      " inventory stands in for it",
            "first_stage": "S7",
            "blocking": "native Bridge generation and assembly identities cannot be produced until the design"
                        " exists",
            "evidence": ["m6/JAR.md"],
        },
    }
    for stage in STAGES:
        owners[f"prepared {stage}"] = {
            "status": "preparation delivered; consumer port pending",
            "reason": f"its preparation contract is delivered; porting the exact consumer and running the item's"
                      f" M0 fixture is {WORK[stage]}",
            "first_stage": stage,
            "blocking": f"blocks native admission of the named consumer until its fixture passes in {stage};"
                        " blocks no earlier stage",
            "evidence": [],
        }
        owners[f"bounded {stage}"] = {
            "status": "qualified for the pilot's bounded input only",
            "reason": f"G1 qualified it under the pilot's finite input model; the general consumer is"
                      f" {WORK[stage]}",
            "first_stage": stage,
            "blocking": f"the general {stage} consumer cannot rely on the pilot's conditional proof and must"
                        " establish its own order, lifetime and variant coverage",
            "evidence": ["m2/CHECKPOINT.md", "m2/OWNERSHIP.md"],
        }
        owners[f"excluded {stage}"] = {
            "status": "excluded from the source-only route",
            "reason": f"the source-only S4 adapter (D269) never executes this edge; it belongs to {WORK[stage]}",
            "first_stage": stage,
            "blocking": f"blocks only the {stage} route through the edge; the source-only S4 compiler does not"
                        " depend on it",
            "evidence": ["m0/DEFERRED_CONTRACTS.md", "m3/HANDOFF_M3.3.md"],
        }
    del owners["excluded S2"], owners["excluded S3"], owners["excluded S4"]
    return owners


OWNERS = owner_table()

# Syntax treatments by kind and context; the first match wins.
SYNTAX = [
    ("NULL", r".", "A: the null literal is a language feature; presence, equality and dereference stay with the"
                   " caller, and Optional presence becomes a nullable reference evaluated once (D258)"),
    ("TYPE_PATTERN", r"^INSTANCE_OF$", "A: named reifiable reference patterns in instanceof are a language feature"),
    ("TYPE_PATTERN", r"^PATTERN_CASE_LABEL$", classify.INSTANCEOF + ", with the selector evaluated once"),
    ("ENHANCED_FOR_VARIABLE", r".", "A: enhanced for over arrays and Iterable is a language feature; over an"
                                    " ironwood.ds map it visits values, with the key from getCurrIteratorKey or a key"
                                    " list copied once when traversal must survive mutation or nesting (B1 section"
                                    " 4.3)"),
    ("TEXT_BLOCK", r".", "A: cooked text blocks are a language feature; stripIndent and formatting calls are"
                         " separate sites (D256)"),
    ("LAMBDA", r".", "B: an ordered loop with Java's short-circuiting (D258), or a compiler-local callback whose"
                     " captured state stays invocation-lived (D264)"),
    ("METHOD_REFERENCE", r".", "B: a direct call in an ordered loop (D258), or a compiler-local callback with the"
                               " receiver evaluated at binding (D264)"),
    ("VAR", r".", "B: the explicit declared type with unchanged initializer timing (D258)"),
    ("RECORD", r".", "B: a final class with ordered final fields and accessors, compact-constructor checks in the"
                     " same order, and hand-written equals and hashCode where value semantics are observed (D258)"),
    ("UNINITIALIZED", r".", "B: an explicit initial value with unchanged guarded assignments (D258)"),
    ("VARARGS", r".", "B: fixed-arity overloads or a caller-owned array (D258)"),
    ("INTERFACE", r".", "B: an ordinary interface with a variant enum and exhaustive switches (D260, D265)"),
    ("RESOURCE_TRY", r".", "B: try/finally or block-scoped defer closing on every exit in Java's order (D168)"),
    ("SYNCHRONIZED_METHOD", r".", classify.OMITTED_CACHE),
]
MISATTRIBUTED = {"Y01568", "Y03357", "Y03904", "Y05086", "Y06015", "Y06021", "Y10076", "Y12131"}
MISATTRIBUTED_RECORD = ("B: a final class with ordered final fields (D258); a coinciding simple name attributed"
                        " the row to the pilot, which excluded it at the M1 checkpoint")
CAPTURES = {
    "outer local/parameter value retained by callback":
        "B: an ordered loop reads the local directly (D258), or a compiler-local callback holds it as"
        " invocation-lived captured state, retired after its holder (D264)",
    "receiver member access; retain receiver identity and read member at invocation, not an assumed eager field"
    " copy": "B: the loop or compiler-local callback keeps the receiver and reads the member at invocation"
             " (D258, D264); copying the field's current value needs its own proof",
}
ORDER = ("B0: a lookup-only container stays an ironwood.ds hash container; a traversal that reaches an observable"
         " choice uses a linked container or sorts with StringOrder or ArrayList.sortWithComparator before that"
         " choice, with deterministic tie-breakers (D263, D264); the consumer's fixture varies collisions,"
         " resizes and insertion order")
LOOKUP = "B0: lookup and membership only; the container stays hashed"
SCOPED = "B0: an order-independent contribution under its scoped source proof"
BOUNDED = ("B0: resolved under the pilot's bounded operation restrictions (one conflict, empty join paths,"
           " a pre-stopped budget and keyed output)")
FRONTEND_MODEL = ("B: final classes with ordered fields and D258 value semantics, enums with the same constants,"
                  " sealed roots as an interface with a variant enum and exhaustive switches (D260, D261)")
IR_MODEL = "D: IrModel lists it with its root, components, walked components and constants (D265)"
IR_ROOT = "D: IrModel.Root names the sealed root; consumers switch exhaustively over its records (D260, D265)"
RENAMER = "B: a direct operand walk following IrModel.walked (D265), or a D264 Mapper with invocation-lived state"
# IR records the Java model gained after the M0 inventory: IrModel lists them for
# S2's walkers, but they are not M0 items. Any other IrModel name outside the
# operation model fails.
POST_M0_IR = {"IR_PROCESS_INSTRUCTION": "IrProcessInstruction, added by D272 for ProcessRunner.runToFile"}
SEMANTIC_MODEL = ("B: final classes and enums with D258 value semantics where observed; sealed roots as an"
                  " interface with a variant enum and exhaustive switches (D260); D262's invocation-lived"
                  " analyzer payloads")


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load():
    manifest = json.loads((DEFERRED / "manifest.json").read_text(encoding="utf-8"))
    data, inputs, failures = {}, {}, []
    for name, artifact in sorted(manifest["artifacts"].items()):
        path = DEFERRED / f"{name}.json.gz"
        digest = sha256(path)
        inputs[str(path.relative_to(ROOT))] = digest
        if digest != artifact["sha256"]:
            failures.append(f"{path.name} differs from the M0 manifest")
        data[name] = json.load(gzip.open(path))
    for name in ["frontend-model-schema.json", "operation-model-schema.json", "../m1/CLASSIFICATION.md"]:
        path = (DOCS / "m0" / name).resolve()
        inputs[str(path.relative_to(ROOT))] = sha256(path)
    counts = manifest["counts"]
    hashes = data["hash"]
    observed = {"api": len(data["api"]), "calls": len(data["calls"]), "syntax": len(data["syntax"]),
                "captures": len(data["captures"]), "origins": len(hashes["origins"]),
                "traversals": len(hashes["traversals"]), "contributions": len(hashes["contributions"])}
    if observed != counts:
        failures.append(f"counts {observed} differ from the M0 manifest {counts}")
    return data, inputs, failures


def phase_of(gate):
    first = gate["preparation"].split(" ")[0]
    return "M6.1" if first == "reuse" else first


def stage_of(gate):
    consumer = gate["migration_consumer"]
    if consumer.startswith("S1 (M2.1"):
        return "S1 frontend"
    if consumer.startswith("S1 (M2.2"):
        return "S1 bounded"
    if consumer.startswith("M4.3 native driver"):
        return "driver"
    return re.match(r"(S\d):", consumer).group(1)


def later_stages(data):
    """The earliest post-pilot stage of every Java source file's inventory rows."""
    stages = defaultdict(set)
    rows = data["calls"] + data["syntax"] + data["captures"] + data["consumers"]
    rows += data["hash"]["origins"] + data["hash"]["traversals"]
    for row in rows:
        if row.get("id") in MISATTRIBUTED:
            continue
        stage = stage_of(row["gate"])
        if stage in STAGES:
            stages[row["file"]].add(stage)
    return {file: min(found, key=STAGES.index) for file, found in stages.items()}


def m1_table(failures):
    """(phase, pattern) -> Counter of calls per class in the M1 classification."""
    rows, section = defaultdict(Counter), None
    for line in (DOCS / "m1/CLASSIFICATION.md").read_text(encoding="utf-8").splitlines():
        heading = re.match(r"## (M1\.[123]) patterns$", line)
        if heading:
            section = heading.group(1)
        elif line.startswith("## "):
            section = None
        elif section and line.startswith("| API"):
            cells = [cell.strip() for cell in line.strip().strip("|").split(" | ")]
            if len(cells) != 7 or cells[5] not in "ABCD":
                failures.append(f"M1 row not understood: {line[:80]}")
                continue
            rows[(section, cells[0].split(" ")[0])][cells[5]] += int(cells[2])
    return rows


def regenerate(phase, data, failures):
    """Rewrite the phase's table with classify.py, on the ledgers already loaded."""
    api = {row["id"]: row for row in data["api"]}
    patterns = defaultdict(list)
    for call in data["calls"]:
        if phase_of(call["gate"]) == phase:
            patterns[call["pattern_id"]].append(call)
    classify.load = lambda requested: (api, patterns)
    with tempfile.TemporaryDirectory() as scratch:
        out = Path(scratch) / "table.md"
        argv, sys.argv = sys.argv, ["classify.py", phase, "--markdown", str(out)]
        try:
            with contextlib.redirect_stdout(io.StringIO()) as printed:
                status = classify.main()
        finally:
            sys.argv = argv
        if status != 0 or out.read_text(encoding="utf-8") != (DOCS / TABLES[phase]).read_text(encoding="utf-8"):
            failures.append(f"{TABLES[phase]} no longer regenerates: {printed.getvalue().strip()}")


class Ledger:

    def __init__(self):
        self.treatments, self.index, self.groups = [], {}, defaultdict(list)

    def add(self, family, item, phase, stage, owner, treatment, pattern=""):
        if owner not in OWNERS:
            raise SystemExit(f"unknown owner {owner}")
        if treatment not in self.index:
            self.index[treatment] = len(self.treatments)
            self.treatments.append(treatment)
        self.groups[(family, phase, stage, owner, self.index[treatment], pattern)].append(item)


def owner_for(stage, file, later, ledger_stage=None):
    if stage == "S1 frontend":
        return "qualified"
    if stage == "S1 bounded":
        return "bounded " + later.get(file, "S3")
    if stage == "driver":
        return "driver"
    return "prepared " + (ledger_stage or stage)


def reconcile_calls(data, later, ledger, failures):
    api = {row["id"]: row for row in data["api"]}
    called = {call["pattern_id"] for call in data["calls"]}
    for pattern in sorted(set(api) - called):
        failures.append(f"{pattern}: an API declaration without a call")
    m1 = m1_table(failures)
    m1_calls = Counter()
    for call in data["calls"]:
        phase, stage = phase_of(call["gate"]), stage_of(call["gate"])
        pattern = call["pattern_id"]
        row = api[pattern]
        if phase.startswith("M1."):
            m1_calls[(phase, pattern)] += 1
            classes = m1[(phase, pattern)]
            if not classes:
                failures.append(f"{call['id']} {pattern}: no {phase} row in m1/CLASSIFICATION.md")
                continue
            merged = Counter()
            for kind, count in classes.items():
                merged["D" if kind == "C" else kind] += count
            treatment = "M1 classes by call: " + ", ".join(f"{kind} {count}" for kind, count in sorted(merged.items()))
            if classes["C"]:
                treatment += f" ({classes['C']} D from C rows closed at the M1 checkpoint)"
            owner = owner_for(stage, call["file"], later)
        else:
            _, verdict = classify.classify(row["owner"], row["signature"], phase)
            problem = classify.check(verdict) if verdict else "no rule"
            if problem:
                failures.append(f"{call['id']} {pattern} {phase}: {problem}")
                continue
            treatment = classify.text(verdict)
            owner = "identity S7" if verdict[0] == "B" and verdict[1] == classify.PRODUCER \
                else owner_for(stage, call["file"], later)
        ledger.add("calls", call["id"], phase, stage, owner, treatment, pattern)
    for key, classes in m1.items():
        if sum(classes.values()) != m1_calls[key]:
            failures.append(f"m1/CLASSIFICATION.md {key}: {sum(classes.values())} calls in its rows,"
                            f" {m1_calls[key]} in the ledger")


def reconcile_syntax(data, later, ledger, failures):
    for row in data["syntax"]:
        phase, stage = phase_of(row["gate"]), stage_of(row["gate"])
        treatment = next((text for kind, context, text in SYNTAX
                          if row["kind"] == kind and re.search(context, row["context"])), None)
        if treatment is None:
            failures.append(f"{row['id']} {row['kind']} {row['context']}: no syntax treatment")
            continue
        if row["id"] in MISATTRIBUTED:
            owner, treatment = "prepared " + later[row["file"]], MISATTRIBUTED_RECORD
        else:
            owner = owner_for(stage, row["file"], later)
        ledger.add("syntax", row["id"], phase, stage, owner, treatment, row["kind"])


def reconcile_captures(data, later, ledger, failures):
    for row in data["captures"]:
        treatment = CAPTURES.get(row["capture_role"])
        if treatment is None:
            failures.append(f"{row['id']}: no capture treatment for {row['capture_role']}")
            continue
        stage = stage_of(row["gate"])
        ledger.add("captures", row["id"], phase_of(row["gate"]), stage, owner_for(stage, row["file"], later),
                   treatment)


def reconcile_hash(data, later, ledger):
    hashes = data["hash"]
    for row in hashes["origins"]:
        stage = stage_of(row["gate"])
        lookup = row["classification"] == "lookup/membership only"
        owner = "qualified" if lookup and stage.startswith("S1") else owner_for(stage, row["file"], later)
        ledger.add("origins", row["id"], phase_of(row["gate"]), stage, owner, LOOKUP if lookup else ORDER)
    files = {}
    for row in hashes["traversals"]:
        files[row["id"]] = row["file"]
        stage = stage_of(row["gate"])
        ledger.add("traversals", row["id"], phase_of(row["gate"]), stage, owner_for(stage, row["file"], later),
                   ORDER)
    for row in hashes["contributions"]:
        stage, file = stage_of(row["gate"]), files[row["traversal"]]
        if row["classification"] == "scoped order-independent contribution":
            treatment = SCOPED
            owner = "qualified" if stage.startswith("S1") else owner_for(stage, file, later)
        elif row["selected_pilot_classification"]:
            treatment, owner = BOUNDED, owner_for(stage, file, later)
        else:
            treatment, owner = ORDER, owner_for(stage, file, later)
        ledger.add("contributions", f"{row['origin']}:{row['traversal']}", phase_of(row["gate"]), stage, owner,
                   treatment)


def reconcile_consumers(data, later, ledger):
    for index, row in enumerate(data["consumers"], 1):
        stage, phase = stage_of(row["gate"]), phase_of(row["gate"])
        treatment = f"the {phase} preparation it needs is delivered ({', '.join(RECORDS[phase])})"
        ledger.add("consumers", f"K{index:05d}", phase, stage, owner_for(stage, row["file"], later), treatment)


def reconcile_edges(data, ledger):
    for index, row in enumerate(data["excluded-edges"], 1):
        names = (row["declaring_owner"].split(".")[-1], row["consumer"].split("::")[0].split(".")[-1])
        bridge = row["source_only_exclusion"].startswith("Bridge") or any(name.startswith("Bridge") for name in names)
        stage = "S7" if bridge else "S6"
        treatment = row["source_only_exclusion"]
        if stage == "S6" and "Main CLI" in treatment:
            treatment += "; S6 ports Main's dispatch on the M4.3 driver adapters and the M5 artifact services"
        ledger.add("excluded-edges", f"X{index:04d}", "source-only S4 route", "excluded", "excluded " + stage,
                   treatment)


def java_file(name):
    return JAVA + name.split("$")[0].replace(".", "/") + ".java"


def model_stage(name, gate, later):
    stage = later.get(java_file(name))
    if stage:
        return stage
    stages = [f"S{digit}" for digit in re.findall(r"\bS(\d)", gate) if f"S{digit}" in STAGES]
    return min(stages, key=STAGES.index) if stages else "S3"


def ir_name(name):
    simple = name[len("ironwood.compiler.ir."):]
    return "__".join(re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", part).upper() for part in simple.split("$"))


def reconcile_models(later, ledger, failures):
    frontend = json.loads((DOCS / "m0/frontend-model-schema.json").read_text(encoding="utf-8"))
    for name, gate in sorted(frontend["consumers"].items()):
        if gate.startswith("M1.3/M2.1"):
            ledger.add("frontend-model", name, "M1.3", "S1 frontend", "qualified", FRONTEND_MODEL)
        else:
            ledger.add("frontend-model", name, "M3.1", "later-only", "prepared " + model_stage(name, gate, later),
                       FRONTEND_MODEL)
    operation = json.loads((DOCS / "m0/operation-model-schema.json").read_text(encoding="utf-8"))
    source = (ROOT / "compiler/src/main/ironwood/ironwood/compiler/port/IrModel.iron").read_text(encoding="utf-8")
    listed = set()
    for enum in ("Record", "Enumeration"):
        body = re.search(r"public enum " + enum + r" \{(.*?)\}", source, re.S).group(1)
        listed |= {item.strip() for item in body.split(",") if item.strip()}
    for name, role in sorted(operation["consumers"].items()):
        if name.startswith("ironwood.compiler.ir."):
            if ir_name(name) in listed:
                treatment = IR_MODEL
            elif name.split(".")[-1] in ("IrInstruction", "IrOperand", "IrTerminator"):
                treatment = IR_ROOT
            elif name.endswith(".IrCfgRenamer"):
                treatment = RENAMER
            else:
                failures.append(f"{name}: no operation-model treatment")
                continue
        else:
            treatment = SEMANTIC_MODEL
        if role["role"] == "selected":
            ledger.add("operation-model", name, "M1.1", "S1 bounded", "qualified", treatment)
        elif role["role"] == "restricted":
            ledger.add("operation-model", name, "M1.1", "S1 bounded",
                       "bounded " + model_stage(name, role["gate"], later), treatment)
        else:
            ledger.add("operation-model", name, "M3.1", "later-only",
                       "prepared " + model_stage(name, role["gate"], later), treatment)
    names = {ir_name(name) for name in operation["consumers"] if name.startswith("ironwood.compiler.ir.")}
    if listed - names != set(POST_M0_IR):
        failures.append(f"IrModel names outside the operation model: {sorted(listed - names)},"
                        f" recorded {sorted(POST_M0_IR)}")


def check_citations(ledger, failures):
    decisions = set(re.findall(r"^## (D\d{3}) ", (ROOT / "docs/DECISIONS.md").read_text(encoding="utf-8"), re.M))
    texts = ledger.treatments + [owner["reason"] for owner in OWNERS.values()]
    for text in texts:
        for decision in re.findall(r"\bD\d{3}\b", text):
            if decision not in decisions:
                failures.append(f"decision {decision} is not recorded")
    files = {path for paths in RECORDS.values() for path in paths}
    files |= {path for owner in OWNERS.values() for path in owner["evidence"]}
    for path in sorted(files):
        if not (DOCS / path).exists():
            failures.append(f"record {path} is missing")


def ledger_json(ledger, inputs):
    owners = {}
    for key, owner in OWNERS.items():
        owners[key] = dict(owner)
        if key.startswith("prepared "):
            owners[key]["evidence"] = ["the phase records"]
    groups = []
    for (family, phase, stage, owner, treatment, pattern), ids in sorted(ledger.groups.items()):
        group = {"family": family, "phase": phase, "m0_stage": stage, "owner": owner, "treatment": treatment}
        if pattern:
            group["pattern"] = pattern
        group["ids"] = sorted(ids)
        groups.append(group)
    document = {
        "schema": 1,
        "scope": "M6.3 disposition of every M0 inventory item: preparation treatment, phase records and one owner",
        "inputs": inputs,
        "records": RECORDS,
        "owners": owners,
        "treatments": ledger.treatments,
        "groups": groups,
    }
    raw = (json.dumps(document, indent=None, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8")
    out = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=out, mtime=0, compresslevel=9) as stream:
        stream.write(raw)
    return out.getvalue()


FAMILIES = [("calls", "calls"), ("syntax", "syntax sites"), ("captures", "captured symbols"),
            ("origins", "hash origins"), ("traversals", "hash traversals"), ("contributions", "hash contributions"),
            ("consumers", "consumer gates"), ("excluded-edges", "excluded edges"),
            ("frontend-model", "frontend model declarations"), ("operation-model", "operation model declarations")]
OWNER_ORDER = (["qualified"] + [f"bounded {stage}" for stage in STAGES] + ["driver"]
               + [f"prepared {stage}" for stage in STAGES] + ["identity S7", "excluded S6", "excluded S7"])


def number(value):
    return f"{value:,}"


def markdown(ledger, failures):
    by_family = defaultdict(Counter)
    by_phase = defaultdict(Counter)
    syntax, other = defaultdict(Counter), defaultdict(Counter)
    for (family, phase, stage, owner, treatment, pattern), ids in ledger.groups.items():
        by_family[family][owner] += len(ids)
        if family == "calls":
            by_phase[phase][owner] += len(ids)
        if family == "syntax":
            syntax[(pattern, ledger.treatments[treatment])][owner] += len(ids)
        elif family not in ("calls", "consumers"):
            other[(family, ledger.treatments[treatment])][owner] += len(ids)
    owners = [owner for owner in OWNER_ORDER if any(by_family[family][owner] for family in by_family)]
    total = sum(sum(counter.values()) for counter in by_family.values())
    lines = [
        "<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->",
        "<!-- Generated by docs/self-hosting/m6/reconcile.py; do not edit. -->",
        "",
        "# M6.3 inventory reconciliation",
        "",
        f"Every one of the {number(total)} items of the M0 source-backed inventory has one preparation treatment"
        " and one owner. The per-item ledger is [reconciliation.json.gz](reconciliation.json.gz): each group"
        " names its family, phase, M0 stage, owner, treatment and item IDs (call, syntax, capture, origin and"
        " traversal IDs; `origin:traversal` for contributions; `K` and `X` numbers are row positions in the pinned"
        " consumer and excluded-edge ledgers; model declarations by name). Its inputs are pinned by SHA-256.",
        "",
        "## Owners",
        "",
        "| Owner | Status | Reason | First affected stage | Blocking effect | Evidence |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for owner in owners:
        entry = OWNERS[owner]
        evidence = ", ".join(f"[{Path(path).name}](../{path})" for path in entry["evidence"]) or "the phase records"
        lines.append(f"| {owner} | {entry['status']} | {entry['reason']} | {entry['first_stage']} | {entry['blocking']}"
                     f" | {evidence} |")
    lines += ["", "## Items by family and owner", "",
              "| Family | Items | " + " | ".join(owners) + " |",
              "| --- | --- | " + " | ".join("---" for _ in owners) + " |"]
    for family, label in FAMILIES:
        counter = by_family[family]
        lines.append(f"| {label} | {number(sum(counter.values()))} | "
                     + " | ".join(number(counter[owner]) if counter[owner] else "" for owner in owners) + " |")
    call_owners = [owner for owner in owners if any(by_phase[phase][owner] for phase in by_phase)]
    lines += ["", "## Calls by phase and owner", "",
              "Calls take their class from their phase's table: [M1](../m1/CLASSIFICATION.md) (the C rows closed"
              " at the [M1 checkpoint](../m1/CHECKPOINT.md)) and classify.py's tables from M3.1 to M6.1, each"
              " regenerated unchanged. The 712 API declarations are covered through their calls; none has no call.",
              "",
              "| Phase | Calls | Record | " + " | ".join(call_owners) + " |",
              "| --- | --- | --- | " + " | ".join("---" for _ in call_owners) + " |"]
    for phase in PHASES:
        counter = by_phase[phase]
        record = RECORDS[phase][0]
        lines.append(f"| {phase} | {number(sum(counter.values()))} | [{Path(record).name}](../{record}) | "
                     + " | ".join(number(counter[owner]) if counter[owner] else "" for owner in call_owners) + " |")
    def spread(counter):
        return ", ".join(f"{owner} {number(counter[owner])}" for owner in OWNER_ORDER if counter[owner])

    lines += ["", "## Syntax treatments", "", "| Kind | Treatment | Sites | Owners |", "| --- | --- | --- | --- |"]
    for (kind, treatment), counter in sorted(syntax.items()):
        lines.append(f"| {kind} | {treatment} | {number(sum(counter.values()))} | {spread(counter)} |")
    lines += ["", "## Other treatments", "", "| Family | Treatment | Items | Owners |", "| --- | --- | --- | --- |"]
    order = [family for family, _ in FAMILIES]
    for (family, treatment), counter in sorted(other.items(), key=lambda item: (order.index(item[0][0]), item[0][1])):
        lines.append(f"| {dict(FAMILIES)[family]} | {treatment} | {number(sum(counter.values()))} | {spread(counter)} |")
    lines += ["", "Each consumer gate's treatment is its phase's delivered preparation, recorded in the phase"
              " records named in the ledger.", ""]
    lines += ["IrModel also lists IR records the Java model gained after the M0 inventory; they are not M0"
              " items, and S2's walkers cover them through IrModel: "
              + "; ".join(f"`{name}` ({reason})" for name, reason in sorted(POST_M0_IR.items())) + ".", ""]
    if failures:
        lines += ["## Failures", ""] + [f"- {failure}" for failure in failures] + [""]
    return "\n".join(lines)


def main():
    data, inputs, failures = load()
    for phase in TABLES:
        regenerate(phase, data, failures)
    later = later_stages(data)
    ledger = Ledger()
    reconcile_calls(data, later, ledger, failures)
    reconcile_syntax(data, later, ledger, failures)
    reconcile_captures(data, later, ledger, failures)
    reconcile_hash(data, later, ledger)
    reconcile_consumers(data, later, ledger)
    reconcile_edges(data, ledger)
    reconcile_models(later, ledger, failures)
    check_citations(ledger, failures)
    expected = {"calls": len(data["calls"]), "syntax": len(data["syntax"]), "captures": len(data["captures"]),
                "origins": len(data["hash"]["origins"]), "traversals": len(data["hash"]["traversals"]),
                "contributions": len(data["hash"]["contributions"]), "consumers": len(data["consumers"]),
                "excluded-edges": len(data["excluded-edges"]), "frontend-model": 119, "operation-model": 233}
    placed = defaultdict(list)
    for (family, *_), ids in ledger.groups.items():
        placed[family].extend(ids)
    for family, count in expected.items():
        if len(placed[family]) != count or len(set(placed[family])) != count:
            failures.append(f"{family}: {len(set(placed[family]))} distinct of {len(placed[family])} placed,"
                            f" {count} expected")
    text = markdown(ledger, failures)
    blob = ledger_json(ledger, inputs)
    if "--write" in sys.argv:
        (HERE / "RECONCILIATION.md").write_text(text, encoding="utf-8")
        (HERE / "reconciliation.json.gz").write_bytes(blob)
    else:
        if (HERE / "RECONCILIATION.md").read_text(encoding="utf-8") != text:
            failures.append("RECONCILIATION.md differs from the regenerated table")
        recorded = gzip.decompress((HERE / "reconciliation.json.gz").read_bytes())
        if recorded != gzip.decompress(blob):
            failures.append("reconciliation.json.gz differs from the regenerated ledger")
    totals = Counter()
    for (family, phase, stage, owner, *_), ids in ledger.groups.items():
        totals[owner] += len(ids)
    print(f"M6.3: {sum(totals.values())} items; "
          + ", ".join(f"{owner} {totals[owner]}" for owner in OWNER_ORDER if totals[owner]))
    for failure in failures:
        print("FAIL", failure)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
