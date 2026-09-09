<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Clean-install testing

Test the published IDK in a fresh operating system, without the compiler source
checkout or the host's developer tools. A successful CI build is not a substitute
for this check.

## Prerequisites

- **macOS ARM64:** Apple's Command Line Tools supply the macOS SDK. Install them
  with `xcode-select --install`, complete the installer, and verify with
  `xcode-select -p` and `xcrun --show-sdk-path`. Full Xcode is not required.
- **Linux:** start with an ordinary ARM64 or x86_64 distribution with Bash, tar,
  and gzip. The Linux IDK includes its compiler/linker dependencies. Record any
  additional packages actually needed instead of installing a development stack
  in advance.
- **Both:** extract the matching IDK into a user-writable directory. Java and
  LLVM are bundled; do not install Homebrew, Conda, or separate Java/LLVM packages
  merely to make the baseline test pass.

These are the distribution's documented requirements. The fresh-VM attempt
recorded below has **not yet verified them**.

## Procedure

1. Create a new VM with its own disk. Do not clone a development machine. In UTM,
   use native virtualization for ARM64. Testing an x86_64 operating system on an
   ARM host requires emulation; a Rosetta-backed ARM guest is a different test.
2. Install the OS and its available updates. Record the OS release, architecture,
   VM configuration, and initially installed developer tools. Do not sign into
   personal cloud accounts or share the host's home directory.
3. Transfer the release archive through a dedicated read-only share, or download
   it from the release page. On macOS, record the behavior before installing the
   Command Line Tools, then repeat after installing them. Distinguish a compiler
   version check from a successful native link.
4. Follow [Quick start](QUICK_START.md). Persist `IRONWOOD_HOME` and
   `PATH="$IRONWOOD_HOME/bin:$PATH"` in the guest's shell startup file. Open a
   new terminal and verify `ironwoodc --version`, `irondoc --version`, and
   `ironjar --help`.
5. In `$IRONWOOD_HOME/examples/hello`, run `./compile.sh`, `./link.sh`, and
   `./run.sh`. Expect `Hello World!` and exit status 0.
6. Copy `scripts/test-idk.sh` from the matching source release into the guest and
   run it against the archive, retaining stdout, stderr, and the exit status:

   ```sh
   bash test-idk.sh ironwood-idk-VERSION-PLATFORM.tar.gz > idk-smoke.log 2>&1
   result=$?
   tail -30 idk-smoke.log
   printf 'Smoke-test exit status: %s\n' "$result"
   ```

   This checks a separate temporary extraction, packaged examples and projects,
   archive/classpath linking, and native programs at O0 through O3. The permanent
   installation remains available afterward.
7. Reboot the installed guest and repeat the version and hello checks. Record
   which developer packages were added, any failures or workarounds, and the
   final test results. Do not describe an unexecuted test as passing.

## Attempt: Ironwood 0.2.3, September 9, 2026

Host: Apple Silicon, macOS 26.6.2 (25G83), UTM 4.7.5. The repository owner supplied
the three release archives. No fresh-guest Ironwood test has run yet.

| New VM | Configuration | Observed status |
| --- | --- | --- |
| `Ironwood macOS Clean 0.2.3` | Apple Virtualization, 4 CPUs, 8 GB RAM, separate 96 GB disk | Apple macOS 26.6.2 recovery downloaded. Restoration began; completion is unverified after a UTM hang and restart. VM is stopped; setup and tests remain pending. |
| `Ironwood Ubuntu ARM64 0.2.3` | QEMU/HVF ARM64, 4 CPUs, 8 GB RAM, separate 64 GB disk | Official Ubuntu 24.04.4 Desktop ARM64 ISO downloaded. Live installer boot retried with audio removed. OS installation and tests remain pending. |
| x86_64 Linux | Planned optional emulated VM | Not created or tested. |

Both created VMs have a read-only share named `Ironwood-VM-Transfer`, containing
only the supplied archives and preparation/test scripts. No guest account or
guest `IRONWOOD_HOME`/`PATH` configuration has been created yet.

The first Ubuntu boot displayed a blank screen. A host process sample showed
UTM's main thread waiting on its SPICE audio machinery, with the audio thread
blocked while initializing CoreAudio recording. UTM was restarted, and the
sound device was removed from the new Ubuntu VM. That workaround still needs
guest-side confirmation; this was not an observed Ironwood failure.

The host then locked. Computer Use could not unlock it, and the Ubuntu guest
agent was unavailable, preventing unattended completion. Resume the installers
after unlocking the host. The existing `newyork`, `miami`, and `cleveland` VMs were
not launched or changed; all three remained stopped in the final inventory.

Installer and VM integration references:

- [Official Ubuntu 24.04 ARM64 downloads](https://cdimage.ubuntu.com/ubuntu/releases/24.04/release/)
- [UTM macOS guest support](https://docs.getutm.app/guest-support/macos/)
- [UTM Linux guest support](https://docs.getutm.app/guest-support/linux/)
