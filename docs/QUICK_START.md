<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Ironwood Quick Start

Ironwood publishes self-contained IDK archives for these platforms:

| Platform | Archive |
| --- | --- |
| macOS on Apple silicon | `ironwood-idk-VERSION-macos-arm64.tar.gz` |
| Linux on ARM64 | `ironwood-idk-VERSION-linux-arm64.tar.gz` |
| Linux on x86-64 | `ironwood-idk-VERSION-linux-x86_64.tar.gz` |

On macOS, install Apple's Command Line Tools for the macOS SDK first:

```sh
xcode-select --install
```

Full Xcode, Homebrew, and separate Java or LLVM installations are not required
for the IDK. See [clean-install testing](CLEAN_INSTALL_TESTING.md) for the
fresh-VM validation procedure and its current results.

1. Download the archive for your system from
   [Ironwood releases](https://github.com/ironwood-lang/ironwood/releases).
   Replace `VERSION` and `PLATFORM` below with the values in its filename.

2. Extract the archive and set `IRONWOOD_HOME` to the extracted directory:

   ```sh
   mkdir -p "$HOME/.local/ironwood"

   tar -xzf "$HOME/Downloads/ironwood-idk-VERSION-PLATFORM.tar.gz" \
     -C "$HOME/.local/ironwood"

   export IRONWOOD_HOME="$HOME/.local/ironwood/ironwood-idk-VERSION-PLATFORM"

   export PATH="$IRONWOOD_HOME/bin:$PATH"
   ```

   Add the two `export` commands to `~/.zshrc`, `~/.bashrc`, or the startup file used by your shell.

3. Check the installed compiler:

   ```sh
   ironwoodc --version
   ```

   This prints `ironwoodc VERSION`. With `IRONWOOD_HOME/bin` on `PATH`, you can
   run `ironwoodc`, `ironjar`, and `irondoc` from any directory.

4. Compile, link, and run the packaged hello example:

   ```sh
   cd "$IRONWOOD_HOME/examples/hello"
   ./compile.sh
   ./link.sh
   ./run.sh
   ```

   A working installation prints `Hello World!` and reports exit status `0`.
   The IDK also includes the complete `examples/` and `projects/` directories.

## Optional JVM settings

Starting with 0.2.4, `$IRONWOOD_HOME/conf/jvm.options` configures the JVM used by
`ironwoodc`, `ironjar`, and `irondoc`. Its examples are commented out by default.
Uncomment `-Xmx2g` to set a heap limit, or `-XX:UseSVE=0` only on Linux ARM systems
reporting the SVE vector-length warning. Use one option per line without shell
quotes. These settings do not affect compiled Ironwood executables.
