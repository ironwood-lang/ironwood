# Ironjar

`ironjar` packages compiled Ironwood classes into one `.ironjar` file. This is
useful for distributing a library or reusing it in another Ironwood project.

An `.ironjar` is a compile-time archive. It is not an executable JAR and is not
loaded at runtime. `ironwoodc` reads it while building the final native program.

## Create an archive

First, compile the library into a class directory:

```sh
./bin/ironwoodc --source-path src/main/ironwood \
  -d target/classes \
  src/main/ironwood/com/example/Counter.iron
```

Then package the compiled classes:

```sh
./bin/ironjar --create --file target/example.ironjar target/classes
```

The inputs may be class directories, individual `.ironclass` files, or a mix of
both:

```sh
./bin/ironjar --create --file target/example.ironjar \
  target/classes target/extra/Helper.ironclass
```

## List an archive

Display the entries stored in an archive:

```sh
./bin/ironjar --list --file target/example.ironjar
```

## Use an archive

Pass the archive to `ironwoodc` through the class path when compiling an
application:

```sh
./bin/ironwoodc --source-path app/src/main/ironwood \
  -cp target/example.ironjar \
  -d app/target/classes \
  app/src/main/ironwood/com/example/App.iron
```

Pass it again when linking the native executable:

```sh
./bin/ironwoodc --link \
  -cp target/example.ironjar:app/target/classes \
  --main-class com.example.App \
  -o app/target/App
```

Use the platform path separator between class-path entries. This is `:` on
macOS and Linux and `;` on Windows.

Only classes reachable from the application are included in the final native
program.

## Include license files

Use `--license` while creating an archive to include a license or notice below
`META-INF/LICENSES`:

```sh
./bin/ironjar --create --file target/example.ironjar \
  --license LICENSE target/classes
```

Repeat `--license` to include more than one file.

## Command summary

```text
ironjar --create --file <archive.ironjar> [--license <file>]... <class-directory|file.ironclass>...
ironjar --list --file <archive.ironjar>
```

Archives are deterministic: the same inputs produce the same archive bytes.
Nested `.ironjar` files and duplicate class definitions are rejected.
