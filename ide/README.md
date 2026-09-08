# Ironwood IDE support

Editor and IDE integration for Ironwood. The work is split into two pieces so
that the language intelligence is written once and reused by every editor:

- `langserver/`: an Ironwood language server that links against the bootstrap
  compiler and speaks the Language Server Protocol. This is where diagnostics,
  outline, hover, and completion live.
- `eclipse/`: a small Eclipse plugin that teaches Eclipse what a `.iron` file
  is, colors it, and connects it to the language server. It contains as little
  Eclipse-specific logic as the job allows.

An Eclipse-only plugin would have to reimplement coloring, content assist,
outlines, and navigation in raw JFace, and none of it would carry over to
another editor. Splitting the work this way means a future VS Code, IntelliJ,
or Neovim integration reuses the same server.

## Status

| Phase | Scope | State |
| --- | --- | --- |
| 1 | Content type, syntax coloring, bracket and comment handling | Done |
| 2 | Live diagnostics from the compiler front end | Done |
| 3 | Outline, hover, and go to definition | Done |
| 4 | Project nature, incremental builder, run configuration | Done |
| - | Completion and expression hover | Blocked on a compiler semantic API |
| - | New project wizard | Done |
| 5 | p2 update site | Done |

## How diagnostics work

Errors come from `CompilerPipeline.analyze`, the same front end `ironwoodc`
runs, stopped before code generation. There is no second, IDE-specific
implementation of Ironwood's rules, so an editor squiggle and a command-line
error are always the same analysis, including ownership and reclamation errors
that only whole-program analysis can find.

The server analyzes a whole source set rather than a single file, because
Ironwood's rules are not decidable one file at a time. It recovers the source
root the way a programmer does, by stripping the declared package from the
file's directory, and analyzes every `.iron` file beneath it. An open editor
buffer is used in place of the file on disk, which is what makes errors appear
while typing rather than only after a save.

Diagnostics are published only for documents the editor has open, plus any
document that previously had some so that fixed errors clear. Publishing for a
file nobody has opened gains nothing visible and makes the host go looking for
an editor that does not exist. Project-wide problems belong to the builder in
phase 4, which owns real workspace markers.

## Navigation and documentation

The Outline view, hover, and go to definition are built from the syntax tree
rather than from semantic analysis. That keeps them fast and keeps them working
in a file that does not compile yet, which is when an outline is most useful.

Hover on a declaration's own name reports its signature, its enclosing type, and
the IronDocs comment written above it. Hover or go to definition on any other
name resolves it against the type names declared in the source set.

That last part is a deliberate limitation worth knowing about: a name is matched
by spelling, so an identifier that happens to share a type's name resolves to
that type. Resolving names the way the compiler does would need the semantic
binder to expose its bindings with source spans, which `SemanticResult` does not
do today: it returns only the typed IR and diagnostics. Completion and
expression-level hover need the same thing, which is why they are listed
separately above rather than as part of phase 3.

### Changing the Ironwood home

Changing the setting rebuilds every Ironwood project. Without that, a project
would keep the problems from its last build, including the marker saying the
home was never set, which reads as the setting having had no effect.

### Configuration

The server needs to find the Ironwood compiler, which in turn discovers the
standard library relative to itself. Set **Preferences > Ironwood > Ironwood
home** to an extracted IDK or a source checkout. The `IRONWOOD_HOME`
environment variable also works, but Eclipse started from the Dock or Finder
inherits no shell environment, so the preference is the setting to rely on.

## Creating a project

**File > New > Project > Ironwood > Ironwood Project.** The wizard asks for a
name and a package, then creates the source and test folders, attaches the
Ironwood nature, and writes a sample program and test suite that build and run
as created.

An existing project gets the same treatment through Configure > Add Ironwood
Nature in its context menu.

`ide/eclipse/tools/VerifyTemplates.java` compiles the generated sample with the
real compiler on every build, because a wizard that produces a project full of
errors is worse than no wizard.

## Building and running a project

An Ironwood project is recognized by the **Ironwood nature**, which attaches the
Ironwood builder.

The builder runs `ironwoodc` over the whole project and turns each error into a
workspace marker, so problems appear in the Problems view and in the file's
ruler. It rebuilds everything on every build rather than tracking one changed
file, because Ironwood's analysis is whole-program: an edit in one file can make
a `free` in another provable or unprovable.

Run a program with Run As > Ironwood Application, from either a `.iron` file or
the project itself. On a project it finds every runnable type, both classes
declaring `main` and `TestSuite` subclasses, and asks which one when there is
more than one. That links
the compiled classes into a native executable with `--main-class` and then runs
it, both attached to the Console. Ironwood produces a native binary rather than
something a virtual machine runs, so launching is genuinely two steps.

### Project layout

The builder reads the layout by convention rather than from a settings page, so
a project imported from disk builds without configuring anything:

| Path | Meaning |
| --- | --- |
| `src/main/ironwood` | source root, falling back to the project root |
| `src/test/ironwood` | test source root, when present |
| `target/classes` | compiled `.ironclass` output |
| `target/<project>` | the linked native executable |

Both source roots go on one source path, because tests reference production
types. When a test source root exists, `ironwood-testing.ironjar` is added to
the compile and link classpaths automatically.

## Running tests

An Ironwood test suite is an ordinary main class: the compiler generates the
dispatch and the native entry point, so there is no test runner to install and
nothing test-specific in the launch. Create an Ironwood Application launch whose
main class is the suite, and its results appear in the Console.

### How the builder reads errors

The builder runs the compiler as a process, so its only channel for errors is
the text the compiler prints. `CompilerOutputParser` recovers each diagnostic
and its position from that output, and `ide/eclipse/tools/VerifyCompilerOutput.java`
runs the real compiler over known-bad source on every build to check that the
recovery still works. A future `--diagnostics-format json` option on `ironwoodc`
would make this sturdier, but it would be a compiler change rather than an IDE
one.

## Requirements

- Eclipse 2026-03 (4.39) or newer. The plugin relies on LSP4E, TM4E, and the
  Generic Editor, all of which ship with the Eclipse IDE for Java Developers
  package, so nothing extra needs installing.
- JDK 21, matching the bootstrap compiler and the JVM Eclipse runs on.

The build compiles against the target Eclipse installation's own plugin jars
rather than a downloaded target platform, so it needs no network access, no
Maven, no Tycho, and no PDE.

## Installing

Quit Eclipse first, then:

```sh
./ide/eclipse/install.sh
```

To remove it again:

```sh
./ide/eclipse/uninstall.sh
```

Set `IRONWOOD_ECLIPSE_HOME` and `IRONWOOD_ECLIPSE_APP` to target a different
Eclipse installation.

After installing, set **Preferences > Ironwood > Ironwood home** so the plugin
can find the compiler.

### Installing from the update site

`./ide/eclipse/package.sh` builds a p2 update site at
`ide/eclipse/target/repository`, plus a zip of it for publishing. Anyone can
install from either through Help > Install New Software, pointing at the
directory or the archive. The feature appears under an **Ironwood** category.

`install.sh` installs from exactly that repository rather than a separate path,
so the distribution route is the one exercised during development.

## Building

```sh
./ide/eclipse/build.sh     # bundle only
./ide/eclipse/package.sh   # bundle, feature, and update site
```

`build.sh` generates the grammar and the icons, verifies the grammar against the
TM4E engine, checks that compiler output still parses, builds the language
server, and packages the bundle. A failure in any of those fails the build.

### Why p2 rather than dropins

A shared Eclipse installation can be owned by another account, which leaves its
`dropins` directory unwritable. Installing through p2 writes into the current
user's configuration area under `~/.eclipse` instead, needs no elevated
privileges, and survives Eclipse updates that rewrite `bundles.info`. The same
publishing step produces the update site that phase 5 will ship.

Each build stamps a fresh timestamp qualifier, so repeated installs leave
superseded jars behind in the user bundle pool. They are inactive and can be
deleted, or reclaimed by an Eclipse p2 garbage collection.

## The grammar is generated, not hand-written

`eclipse/plugin/syntaxes/ironwood.tmLanguage.json` is generated by
`eclipse/tools/GenerateGrammar.java` and should not be edited. Edit
`ironwood.tmLanguage.template.json` and rebuild instead.

The generator does not keep its own list of keywords. It asks the real
compiler lexer which words are reserved, by lexing each `TokenKind` name and
keeping the ones that lex back to themselves. The categories in the generator
only decide which TextMate scope each keyword receives, and a keyword the lexer
knows but no category claims fails the build. Adding a keyword to the compiler
without coloring it is therefore a build error rather than a silent omission.

`eclipse/tools/VerifyGrammar.java` then tokenizes
`eclipse/tools/fixtures/Highlighting.iron` with the same TM4E engine Eclipse
uses and asserts the resulting scopes, so a regression in the grammar fails the
build. The fixture is also valid Ironwood and compiles with `ironwoodc`.

The launch configuration type's icon is generated the same way, by
`eclipse/tools/GenerateIcons.java`. It is required rather than decorative:
Eclipse cannot render a launch configuration in Quick Access at all when its
type has no image.

All three steps run as part of `build.sh`.

## Testing the language server

`ide/langserver/test-langserver.py` drives the server over the protocol itself,
with no workbench and no display. It checks that real compiler errors reach the
client with correct messages and ranges, that editing a buffer refreshes them
without touching disk, that a type declared in a file nobody opened still
resolves, and that unopened files receive no diagnostics.

```sh
./ide/langserver/build.sh
python3 ide/langserver/test-langserver.py
```

## Scopes worth knowing

`free` and `destructor` carry `keyword.control.reclamation.ironwood`. The extra
scope segment lets a theme single out Ironwood's reclamation keywords while
still inheriting ordinary `keyword.control` coloring from any theme that does
not know about Ironwood.

Cooked text blocks highlight escape sequences and raw `r"""` text blocks do not,
matching what the lexer actually decodes.

Ironwood has no general annotations, so `@Override` and `@Test` are colored as
directives and any other `@Name` is marked invalid.
