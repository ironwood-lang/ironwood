# Generate IronDocs for your code

IronDocs converts `/** ... */` comments on declarations in `.iron` source files
into Markdown.

Assume this file is
`src/main/ironwood/org/ironwood/hello/Chatter.iron`:

```java
package org.ironwood.hello;

import ironwood.util.Random;

/**
 * Selects a greeting word at random.
 *
 * <p>Each instance owns its {@link Random} generator and reclaims it when the
 * instance is destroyed.</p>
 *
 * @since 1.0.0
 */
public class Chatter {

    private static final String[] WORDS = {
        "World", "Ironwood", "Developers"
    };

    private final Random rand = new Random();

    /**
     * Returns one configured greeting word.
     *
     * @return a random greeting word
     */
    public String getWord() {
        int index = this.rand.nextInt(WORDS.length);
        return WORDS[index];
    }

    destructor {
        free this.rand;
    }
}
```

From the project root, run:

```sh
irondoc \
  -d docs/api \
  -sourcepath src/main/ironwood \
  -doctitle 'Hello API'
```

The command recursively documents every `.iron` file under the source path. By
default, it includes public and protected declarations. It creates:

```text
docs/api/README.md
docs/api/assets/irondocs.svg
docs/api/org/ironwood/hello/package-summary.md
docs/api/org/ironwood/hello/Chatter.md
```

Commit `docs/api/` and push it with your project. GitHub renders the generated
Markdown directly. Link to `docs/api/README.md` from your project README.
