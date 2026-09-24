<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Rejected `free` explanations: compile and link examples

Pass `--explain-rejected-free` to the `ironwoodc` command that rejects a
`free`. A source compilation and a `--link` invocation can each report a
rejection. The flag applies only to that command; it is off by default and
does not make an unsafe `free` legal. The commands below leave `--unfreed`
unset, so its default warning mode applies. Mandatory safety checks still run.

The diagnostic blocks were captured from the commands shown. Only the
absolute checkout prefix in source paths has been shortened to `.../`.
Run each example from a directory containing the named source files, with
`ironwoodc` on your `PATH`.

## A source compilation rejects a static escape

Save this as `Escaped.iron`:

```java
// SPDX-License-Identifier: MIT OR Apache-2.0
class Escaped {

    static Object saved;

    static void check() {

        Object value = new Object();
        saved = value;
        free value;
    }
}
```

Compile it with explanations enabled:

```sh
ironwoodc --explain-rejected-free -d target/escaped Escaped.iron
```

The command exits with status 1 and reports:

```text
error: cannot free 'value': allocation escapes through static field 'Escaped.saved'
  --> .../Escaped.iron:10:14
   |
10 |         free value;
   |              ^^^^^
note: this operation established the selected ownership reason: allocation escapes through static field 'Escaped.saved'
  --> .../Escaped.iron:9:17
  |
9 |         saved = value;
  |                 ^^^^^
```

The primary points at the rejected `free`. The note points at the store that
made the allocation reachable from a static field. Removing that store, or
avoiding the `free` while the reference remains reachable, changes the
ownership situation. The rejected command emits no class file.

## A link rejects a retaining application override

Here, the application and the replacement library each compile separately.
The linker reanalyzes their combined classes and discovers that the
application override retains an array the library tries to free.

First save this compatible library version as `lib-v1/src/lib/Sink.iron`:

```java
// SPDX-License-Identifier: MIT OR Apache-2.0
package lib;

public class Sink {

    public void accept(byte[] value) {

    }

    public static void use(Sink sink) {

        byte[] data = new byte[16];
        free data;
    }
}
```

Save the application as `app/src/app/Keeper.iron`:

```java
// SPDX-License-Identifier: MIT OR Apache-2.0
package app;

import lib.Sink;

public class Keeper extends Sink {

    static byte[] kept;

    @Override
    public void accept(byte[] value) {

        kept = value;
    }

    public static void main(String[] args) {

        Keeper keeper = new Keeper();
        Sink.use(keeper);
        free keeper;
    }
}
```

The replacement library, saved as `lib-v2/src/lib/Sink.iron`, now calls
`accept` before freeing its array:

```java
// SPDX-License-Identifier: MIT OR Apache-2.0
package lib;

public class Sink {

    public void accept(byte[] value) {

    }

    public static void use(Sink sink) {

        byte[] data = new byte[16];
        sink.accept(data);
        free data;
    }
}
```

Compile the application against the earlier library, then compile the
replacement library independently. Do not put `lib-v1/classes` on the final
classpath:

```sh
ironwoodc -d lib-v1/classes lib-v1/src/lib/Sink.iron
ironwoodc -cp lib-v1/classes -d app/classes app/src/app/Keeper.iron
ironwoodc -d lib-v2/classes lib-v2/src/lib/Sink.iron
mkdir -p target
ironwoodc --link --explain-rejected-free \
  -cp app/classes:lib-v2/classes --main-class app.Keeper -o target/Keeper
```

The first three commands succeed without warnings. The link exits with status
1 and reports:

```text
error: cannot free 'data': allocation escapes through argument 1 of method 'accept'
  --> .../lib-v2/classes/lib/Sink.ironclass!/source/Sink.iron:14:14
   |
14 |         free data;
   |              ^^^^
note: this call can pass the allocation as argument 1 to possible target 'Keeper.accept'
  --> .../lib-v2/classes/lib/Sink.ironclass!/source/Sink.iron:13:21
   |
13 |         sink.accept(data);
   |                     ^^^^
note: 'Keeper.accept' can store that reference in static field 'app.Keeper.kept'
  --> .../app/classes/app/Keeper.ironclass!/source/Keeper.iron:13:16
   |
13 |         kept = value;
   |                ^^^^^
```

The primary and first note come from the replacement library class. The
last note comes from the application class. The linker describes
`Keeper.accept` as a possible target because the combined program can dispatch
to that retaining override. It emits no executable. Classes compiled with
`--explain-rejected-free` do not carry the flag into a later link, so add it
to the link command when the rejection occurs there. On Windows, use the
platform classpath separator in place of `:`.

For the supported evidence categories, note limits, and cases that receive a
boundary note, see [rejected-free explanations](MEMORY.md#rejected-free-explanations).
