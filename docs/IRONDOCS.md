# IronDocs

IronDocs turns Javadoc-style comments in `.iron` source into a browsable Markdown
API reference. The command is **`irondoc`**. Start with the generated
[versioned standard-library reference](api/README.md), which links to the
authored `ArrayObjectPool` page for the current version.

This is an initial compatibility implementation, independently written against
the Javadoc comment and command specifications. Supported comments and options
keep familiar spellings and meanings. It is not a complete port of the JDK tool,
and contains no OpenJDK implementation source. D098 supersedes the earlier
blanket exclusion of Feature 79.

## Generate, commit, and push the standard-library reference

Choose how far the script should go:

```sh
./scripts/update-irondocs.sh                 # Generate only
./scripts/update-irondocs.sh --commit        # Generate and commit
./scripts/update-irondocs.sh --commitpush    # Generate, commit, and push
```

The script needs Git, Python 3, and the bootstrap Java toolchain. It reads the
repository's `VERSION`, generates all public and protected declarations under
`ironwood`, and writes `docs/api/<version>/`. With `VERSION` set to `0.1.3-beta`,
it updates `docs/api/0.1.3-beta/`. No option means generation only, including
uncommitted source changes. `--no-commit` remains an alias for this default.

Before replacing versioned output, the script compares the source-file count
and the generated total and per-package type counts with the numeric inventory
at the top of `STDLIB.md`. Any mismatch fails generation. The package-purpose
descriptions in that inventory are used to introduce the generated package pages.

`--commit` commits only `docs/api` and does not push. `--commitpush` makes the same
documentation commit, then pushes the current branch to the same branch name on
`origin`. On `main`, this publishes to `origin/main`. A branch push includes all
earlier local commits, including the source changes described by the new docs.
It does not push other branches or tags, or create a GitHub release.

Both commit options require source, `STDLIB.md`, generator, launcher, and version
inputs to be committed first. They preserve unrelated staged and unstaged changes.
Identical output creates no commit, but `--commitpush` still attempts the push.
If a push fails, local commits remain available: resolve the reported Git error
and rerun the same command. The script does not force a push or merge remote
changes automatically.

`--check` regenerates into temporary storage and fails if the working reference
is out of date; it does not replace or commit documentation. The generation,
commit, commit-and-push, and check modes cannot be combined.

The lower-level tool is still available for custom selections:

```sh
./bin/irondoc -d workspace/pool-api -doctitle 'Ironwood Standard Library' \
  --doc-version "$(cat VERSION)" \
  stdlib/src/main/ironwood/ironwood/pool/ArrayObjectPool.iron
```

`./scripts/irondoc` is an equivalent launcher. Add the repository's `bin` directory
to `PATH` to invoke `irondoc` directly. Like the other bootstrap tools, the
launcher builds a missing or stale compiler JAR with Java 21. Documentation
generation itself needs only the bootstrap Java runtime; it never invokes LLVM,
links a native program, or executes the documented source. The initial command
is available from the checkout; inclusion in release packages is deferred.

## Version lifecycle

The version describes the **documented source**, not the newest published binary.
For example, `0.1.3-beta` source produces `0.1.3-beta` documentation.
A reference labeled `0.1.2` must describe the `v0.1.2` release source. We do not
create a historical snapshot by relabeling newer code.

- During development, rerun the script to update the current prerelease folder.
  A new prerelease replaces the previous development folder. Only stable release
  snapshots accumulate.
- To release `0.1.3`, run `./scripts/release.sh 0.1.3` from clean, synchronized
  `main`. It updates the stable version, generates `docs/api/0.1.3/`, removes the
  corresponding prerelease folder, and commits the source version and reference
  together before tagging. It then commits `0.1.4-beta` and its development
  reference on `main`, and atomically pushes `main` with the stable tag.
  See [Releasing Ironwood](RELEASING.md).
  For documentation-only preparation, the lower-level
  `./scripts/update-irondocs.sh --release --commit` remains available after
  committing a stable `VERSION`; it does not tag or push a release. Omitting
  `--commit` generates the stable reference without committing it.
- The next development cycle starts automatically. Normal documentation updates
  now go to `0.1.4-beta`; the `0.1.3` snapshot remains untouched. The next version
  increments the released patch number and adds `-beta`.

`--release` requires a stable version. A stable version requires `--release`
unless only checking existing output. Repeating a release generation with
identical output is a no-op; changing an existing stable snapshot is rejected.
The script also refuses local edits or deletions inside committed stable
snapshots. There is no force-overwrite option.

This is a generation policy, not a GitHub file lock. Release notes link to the
reference at its release tag, so later edits on `main` do not alter that tagged
reference. The release script does not move tags or configure GitHub permission
rules.

The permanent entry point is `docs/api/README.md`. For example, after releasing
`0.1.3` and beginning the next development cycle:

```text
docs/api/README.md                      Version index
docs/api/0.1.3/                         Frozen stable reference
docs/api/0.1.4-beta/                    Changing development reference
```

Each version contains its own package/type pages, banner, and `snapshot.json`
with the version and a deterministic SHA-256 fingerprint of the standard-library
source. A stable snapshot can also store the custom status supplied by
`release.sh VERSION --status "TEXT"`. The version index preserves that message
on regeneration; otherwise stable versions display **Stable release**. Beta
versions always display **Development (updated in place)**. See
[Custom release status](RELEASING.md#customize-the-irondocs-status).

The metadata avoids timestamps and documentation commits that change just because
the previous documentation commit changed Git's HEAD. All version-specific links
stay inside that snapshot. Git history retains earlier beta states even after
their folder is retired from the current branch.

The wrapper generates and validates a complete replacement before installing it,
so removed declarations do not leave stale pages. Generation errors preserve the
published documentation. Hand-written files and symbolic-link output are rejected.
Stable history is preserved. The lower-level `irondoc -d` command still writes only
its selected pages and does not remove files from earlier selections.

Run `./scripts/test-irondocs.sh` for focused comment, CLI, banner, generated-link,
reproducibility, and Git lifecycle checks. These do not run the full compiler
suite or execute the documented source. The separate compiler suite retains the
native test of the complete `ArrayObjectPool` example.

## GitHub presentation

Markdown files render directly when browsed on GitHub. Package indexes, relative
links, signatures, examples, tables, and deprecation alerts use GitHub's own
layout and theme. The bundled SVG provides an accent of color without external
services. GitHub removes custom CSS and scripts, so a repository Markdown view
cannot offer arbitrary fonts, layouts, or an interactive search interface. No
GitHub Pages site or deployment is required.

Code fences use GitHub's Java syntax highlighting for Ironwood's Java-shaped
syntax. The examples remain Ironwood code. Links to selected declarations are
relative and overload-specific. References to types outside the current output
remain readable text, without invented URLs. A missing or ambiguous member of a
selected type is an error.

## Command options

```text
irondoc [options] [packagenames] [sourcefiles.iron] [@files]
```

| Option | Initial behavior |
| --- | --- |
| `-d directory` | Output directory; defaults to the working directory. Use a dedicated directory. |
| `-sourcepath path`, `--source-path path` | Source roots separated by the platform path separator. When explicitly supplied without package or file selectors, every `.iron` file below the roots is selected recursively. Defaults to the working directory for package lookup. |
| `-subpackages pkg:pkg` | Select packages recursively, using colon-separated package names. |
| `-exclude pkg:pkg` | Exclude these packages and descendants from recursive selection. Explicit inputs are unaffected. |
| `-public`, `-protected`, `-package`, `-private` | Select declaration visibility. Default is public and protected. Enclosing-type visibility also applies. |
| `-doctitle text` | Single-line plain-text reference title; default `Ironwood API`. HTML titles are not interpreted. |
| `--doc-version text` | IronDocs extension: single-line version of the documented API, displayed in the banner and Markdown. Omitted by default, independently of the tool version. |
| `-encoding UTF-8` | Source encoding; Ironwood requires UTF-8. |
| `-docencoding UTF-8`, `-charset UTF-8` | Output is always UTF-8. |
| `-author`, `-version` | Include the corresponding comment tags; `-version` is not the tool version flag. |
| `-quiet` | Suppress successful-generation messages. |
| `--version`, `-v` | Print the tool version. |
| `--help`, `-help`, `-h`, `-?` | Show supported options. |
| `@file` | Read whitespace-separated arguments, single/double-quoted values, and `#` comment lines. Paths are relative to the working directory; nested argument files and line continuations are not supported. |

Package operands select only their direct `.iron` files. `-subpackages` includes
descendants. Sources whose declared package differs from their discovered source
path are ignored. Multiple explicit files and packages can be combined. For
ordinary project documentation, the source path alone selects the complete source
tree. For example, inspect declarations throughout the current library in scratch
output:

```sh
irondoc -d workspace/stdlib-api -sourcepath stdlib/src/main/ironwood \
  -doctitle 'Ironwood Standard Library'
```

Undocumented declarations still receive signatures and navigation. This does not
imply that the whole standard library has authored IronDocs comments.

Unknown options, unsupported documentation tags, invalid parameter tags,
malformed comments, invalid source syntax, and unresolved links to members of
selected types produce diagnostics and a nonzero exit status. This limited
validation is not Javadoc DocLint. There is no full type checking or ownership
analysis: compile source normally to validate executable behavior.

## Write comments

Put `/** ... */` immediately before a declaration, including before its
`@Override` directive. Whitespace and ordinary comments may intervene. If several
documentation comments precede a declaration, the last one applies. Markers in
strings, characters, text blocks, ordinary line comments, and method-body
comments do not document another declaration.

```java
/**
 * Returns a value at the requested position.
 *
 * <p>The returned value keeps its existing state.
 *
 * @param index the position to read
 * @return the retained value
 * @throws IllegalArgumentException if the position is invalid
 * @see #size()
 */
public E get(int index) { /* implementation */ }
```

| Syntax | Rendering |
| --- | --- |
| Main description | Prose, with the first sentence used in summaries. |
| `@param name description`, `@param <T> description` | Parameter table, checked against declared parameter names. |
| `@return description` | Return description for a non-void method. |
| `@throws Type description`, `@exception Type description` | Exception conditions; unchecked exceptions need not appear in a `throws` clause. |
| `@see Type`, `@see Type#method(int)`, `@see "text"` | Reference or quoted description. |
| `@since`, `@deprecated`, `@author`, `@version` | Metadata; deprecation uses a GitHub warning alert. |
| `{@code text}`, `{@literal text}` | Escaped code or literal prose. |
| `{@link Type#method(int) label}`, `{@linkplain Type label}` | Linked reference, with optional label and code/plain styling. |
| `<p>`, `<br>`, `<b>`, `<strong>`, `<i>`, `<em>` | Paragraphs, line breaks, and emphasis. |
| `<ul>`, `<ol>`, `<li>` | Lists. |
| `<pre>{@code ...}</pre>`, `<pre><code>...</code></pre>` | Fenced Ironwood example using Java highlighting. |
| `<code>...</code>` | Inline code. |
| `&lt;`, `&gt;`, `&amp;`, `&quot;`, `&apos;`, `&nbsp;`, numeric entities | Escaped characters. |

Keep `@throws` and `@exception` focused on method-specific failures. IronDocs
omits tags for `ironwood.lang.OutOfMemoryError`, including its implicit short
name, and leaves no empty Throws table when it is the only tagged failure.
Allocation exhaustion is documented once in the [memory model](MEMORY.md);
the error type still has its own API reference page. Other checked and unchecked
exceptions remain eligible for Throws sections. This presentation rule does
not change runtime behavior or the declared signatures shown in the reference.

Member links accept parameter types, including raw generic type names and
whitespace between arguments; parameter names in links are not supported.
Overloaded members should include their parameter types. Link lookup covers
selected types by qualified name, the current package, direct nested types,
explicit imports, wildcard imports, and implicit `ironwood.lang` imports. It
is deliberately smaller than compiler member lookup; inherited members and
inherited documentation are not synthesized.

Type pages include **Extends** and **Implements** navigation immediately below
the declaration, linking directly declared superclasses and interfaces while
preserving generic type arguments. Interfaces link to their declared parent
interfaces as well. A parent outside the selected documentation remains readable
text instead of a broken link. Implicit parents and inherited interface lists
are not synthesized.

## Initial boundary

IronDocs documents explicitly declared classes, interfaces, enums, named member
types, fields, constructors, methods, and enum constants. It preserves generic
parameters and bounds, visibility, declared throws clauses, and static-final
initializers as written. It does not evaluate constants or synthesize implicit
constructors and enum helper methods. An unnamed-package type named `README`
is rejected because its page would collide with the library index. Initializer
blocks, destructors, local
types, and anonymous types are not reference entries.

Deferred Javadoc facilities include `///` Markdown comments, package/module
comments and overview files, inherited member tables, `{@inheritDoc}`, `{@value}`,
snippets, summary tags, external documentation indexes, arbitrary HTML, doc-files,
search, HTML/CSS output, DocLint, plugins/custom doclets, modules, and remaining
command flags. Unsupported constructs are not presented as implemented. Ordinary
compilation continues treating all documentation comments as trivia.

## Compatibility references

- [Javadoc command specification (JDK 21)](https://docs.oracle.com/en/java/javase/21/docs/specs/man/javadoc.html)
- [Traditional documentation comment specification (JDK 21)](https://docs.oracle.com/en/java/javase/21/docs/specs/javadoc/doc-comment-spec.html)
- [Java SE 26 documentation comment specification](https://docs.oracle.com/en/java/javase/26/docs/specs/javadoc/doc-comment-spec.html), including the broader surface tracked under Feature 79
- [GitHub markup rendering and sanitization](https://github.com/github/markup)
- [GitHub Markdown formatting](https://docs.github.com/en/get-started/writing-on-github/getting-started-with-writing-and-formatting-on-github/basic-writing-and-formatting-syntax)
