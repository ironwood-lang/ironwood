#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0

"""Explicit local compiler tests on macOS and two Linux architectures, using Rosetta for x86-64."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import uuid


PLATFORMS = ("macos-arm64", "linux-arm64", "linux-x86_64")
PROFILE = "ironwood-tests"
DOCKER = ["docker", "--context", "colima-" + PROFILE]
PROGRESS = re.compile(r"^\d{2}:\d{2}:\d{2}\.\d{3} - \d+/\d+ - ")


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    mode = result.add_mutually_exclusive_group(required=True)
    mode.add_argument("--list", dest="list_platforms", action="store_true", help="list all supported platforms without running tools")
    mode.add_argument("--setup", action="store_true", help="prepare the dedicated Linux VM and cached toolchain images")
    mode.add_argument("--stop", action="store_true", help="stop the dedicated Linux VM, keeping cached toolchains and results")
    mode.add_argument("--full", action="store_true", help="run the full compiler/native suite once for release readiness")
    mode.add_argument("--test", action="append", metavar="EXACT_NAME", help="run only this named compiler test; repeat to select more")
    mode.add_argument("--failed", action="store_true", help="rerun only named failures from each platform's latest report")
    result.add_argument("--platform", action="append", choices=PLATFORMS, help="select platforms; default: all three")
    result.add_argument("--dry-run", action="store_true", help="print commands without running tools or creating output")
    return result


def run(command, root, dry_run=False, **kwargs):
    print("+ " + shlex.join(map(str, command)), flush=True)
    if dry_run:
        return None
    return subprocess.run(command, cwd=root, check=True, **kwargs)


def image(root, target):
    content = ((root / "scripts/platform-tests/Dockerfile").read_bytes()
               + (root / "packaging/idk-environment.yml").read_bytes())
    return "ironwood-tests-" + target + ":" + hashlib.sha256(content).hexdigest()[:16]


def verify_rosetta(root, dry_run=False):
    command = ["colima", "ssh", "--profile", PROFILE, "--", "sh", "-c",
               "cat /proc/sys/fs/binfmt_misc/rosetta && "
               "if test -r /proc/sys/fs/binfmt_misc/qemu-x86_64; then "
               "cat /proc/sys/fs/binfmt_misc/qemu-x86_64; fi"]
    if dry_run:
        run(command, root, True)
        return
    try:
        result = run(command, root, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        lines = result.stdout.splitlines()
        interpreter = next((line for line in lines if line.startswith("interpreter ")), "")
        flags = next((line for line in lines if line.startswith("flags:")), "")
        if (not lines or lines[0] != "enabled" or lines.count("enabled") != 1
                or not interpreter.endswith("/rosetta") or "F" not in flags):
            raise ValueError("missing Rosetta handler or an active QEMU handler")
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        raise ValueError("Rosetta is not the active x86-64 translator; install Rosetta on the Mac, "
                         "then run scripts/test-platforms.sh --stop and "
                         "scripts/test-platforms.sh --setup --platform linux-x86_64") from error
    print("linux-x86_64: Rosetta active", flush=True)


def setup(root, targets, dry_run):
    linux = [target for target in targets if target.startswith("linux-")]
    if not linux:
        print("macOS uses the checkout's existing Java and LLVM installation; no VM is needed.")
        return
    run(["colima", "start", "--profile", PROFILE, "--vm-type", "vz", "--arch", "aarch64",
         "--cpus", "6", "--memory", "8", "--runtime", "docker", "--activate=false",
         "--vz-rosetta=true", "--binfmt=false", "--mount", str(root) + ":w"], root, dry_run)
    if "linux-x86_64" in linux:
        verify_rosetta(root, dry_run)
    # Only the Dockerfile and toolchain specification enter the image build context.
    if dry_run:
        for target in linux:
            arch = "arm64" if target == "linux-arm64" else "amd64"
            run([*DOCKER, "build", "--platform", "linux/" + arch, "--tag", image(root, target),
                 "<temporary toolchain-only build context>"], root, True)
        return
    with tempfile.TemporaryDirectory(prefix="ironwood-toolchain-context-") as temporary:
        context = Path(temporary)
        shutil.copy2(root / "scripts/platform-tests/Dockerfile", context / "Dockerfile")
        shutil.copy2(root / "packaging/idk-environment.yml", context / "idk-environment.yml")
        for target in linux:
            arch = "arm64" if target == "linux-arm64" else "amd64"
            run([*DOCKER, "build", "--platform", "linux/" + arch, "--tag", image(root, target),
                 str(context)], root)


def test_command(root, target, names, container=None):
    arguments = [item for name in names for item in ("--test", name)]
    if target == "macos-arm64":
        return [str(root / "scripts/test.sh"), *arguments]
    build = root / "workspace/platform-tests/build" / target
    arch = "arm64" if target == "linux-arm64" else "amd64"
    return [*DOCKER, "run", "--rm", *(["--name", container] if container else []), "--platform", "linux/" + arch,
            "--user", f"{os.getuid()}:{os.getgid()}", "--env", "HOME=/tmp",
            "--env", "LANG=C.UTF-8", "--env", "LC_ALL=C.UTF-8",
            "--mount", f"type=bind,source={root},target={root}",
            "--mount", f"type=bind,source={build},target={root / 'compiler/build'}",
            "--workdir", str(root), image(root, target), "bash", "scripts/test.sh", *arguments]


def selected_names(report, args):
    if not args.failed:
        return args.test or []
    if not report.exists():
        raise ValueError(f"no previous report: {report}; select a test with --test")
    previous = json.loads(report.read_text())
    names = previous.get("failed_tests", [])
    if not names and previous.get("exit_code") != 0:
        raise ValueError("the previous run failed or stopped before individual test failures were recorded; "
                         "fix setup or select tests explicitly with --test")
    if previous.get("completed") is False:
        print(f"{report.stem}: previous run was incomplete; only recorded failures will be retried. "
              "Unrun tests remain unverified.")
    return names


def save_report(report, result):
    pending = report.with_suffix(".tmp")
    pending.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    pending.replace(report)


def duration(seconds):
    minutes, seconds = divmod(round(seconds), 60)
    hours, minutes = divmod(minutes, 60)
    return f"{hours:02d}:{minutes:02d}:{seconds:02d}"


def print_summary(targets, plans, results, mode):
    by_platform = {result["platform"]: result for result in results}
    planned = {target for target, _ in plans}
    print(f"\nFINAL PLATFORM SUMMARY ({mode})")
    print(f"{'Platform':<15} {'Result':<12} {'Passed':>7} {'Failed':>7} {'Elapsed':>9}")
    print("-" * 54)
    for target in targets:
        result = by_platform.get(target)
        if result is None:
            status = "NOT RUN" if target in planned else "SKIPPED"
            passed = failed = elapsed = "-"
        else:
            if result["exit_code"] == 130:
                status = "INTERRUPTED"
            elif not result["completed"]:
                status = "INCOMPLETE"
            else:
                status = "PASS" if result["exit_code"] == 0 else "FAIL"
            passed = result["passed_count"]
            failed = len(result["failed_tests"])
            elapsed = duration(result["seconds"])
        print(f"{target:<15} {status:<12} {passed:>7} {failed:>7} {elapsed:>9}")
    print("-" * 54)
    print(f"Total test time: {duration(sum(result['seconds'] for result in results))}")


def interrupt_process(process, container, root):
    if container:
        try:
            # Remove only this invocation's container, even if its client exited.
            # Cached images and the mounted checkout remain intact.
            subprocess.run([*DOCKER, "rm", "--force", container], cwd=root, check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=15)
        except (OSError, subprocess.SubprocessError) as error:
            print(f"Could not remove test container {container}: {error}", file=sys.stderr)
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()


def execute(root, args):
    if args.list_platforms:
        print("\n".join(PLATFORMS))
        return 0
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        raise ValueError("this local orchestrator requires an Apple Silicon Mac")
    if args.stop:
        run(["colima", "stop", "--profile", PROFILE], root, args.dry_run)
        return 0
    targets = list(dict.fromkeys(args.platform or PLATFORMS))
    if args.setup:
        setup(root, targets, args.dry_run)
        return 0
    storage = root / "workspace/platform-tests"
    plans = []
    for target in targets:
        names = selected_names(storage / (target + ".json"), args)
        if args.failed and not names:
            print(f"{target}: no failed tests to rerun")
        else:
            plans.append((target, names))
    if not args.dry_run:
        # Check every Linux image before spending time on even the first platform.
        if any(target == "linux-x86_64" for target, _ in plans):
            verify_rosetta(root)
        for target, _ in plans:
            if target.startswith("linux-"):
                try:
                    subprocess.run([*DOCKER, "image", "inspect", image(root, target)], cwd=root,
                                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
                    arch = "arm64" if target == "linux-arm64" else "amd64"
                    subprocess.run([*DOCKER, "run", "--rm", "--platform", "linux/" + arch,
                                    image(root, target), "/bin/true"], cwd=root, check=True,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
                except (OSError, subprocess.CalledProcessError) as error:
                    raise ValueError(f"{target}: VM or toolchain image is unavailable; "
                                     "run scripts/test-platforms.sh --setup first") from error
    if not args.dry_run:
        storage.mkdir(parents=True, exist_ok=True)
    results = []
    for target, names in plans:
        report = storage / (target + ".json")
        container = "ironwood-test-" + uuid.uuid4().hex if target.startswith("linux-") and not args.dry_run else None
        command = test_command(root, target, names, container)
        if args.dry_run:
            run(command, root, True)
            continue
        if target.startswith("linux-"):
            (storage / "build" / target).mkdir(parents=True, exist_ok=True)
        log = storage / (target + "-" + time.strftime("%Y%m%d-%H%M%S") + ".log")
        print(f"\n{target}: {'selected tests' if names else 'full suite'}; log: {log}", flush=True)
        started = time.monotonic()
        failures = []
        exit_code = 1
        completed = False
        interrupted = False
        result = {"platform": target, "mode": "selected" if names else "full", "tests": names,
                  "passed_count": 0, "failed_tests": failures, "exit_code": None, "completed": False,
                  "seconds": 0, "log": str(log)}
        # Replace stale reports before starting, and persist each failure so an
        # interrupted run can still retry its recorded failures.
        save_report(report, result)
        try:
            with log.open("w", encoding="utf-8") as output:
                output.write("Command: " + shlex.join(command) + "\n")
                with subprocess.Popen(command, cwd=root, stdout=subprocess.PIPE,
                                      stderr=subprocess.STDOUT, text=True, start_new_session=True) as process:
                    try:
                        for line in process.stdout:
                            print(line, end="", flush=True)
                            output.write(line)
                            output.flush()
                            message = PROGRESS.sub("", line)
                            if message.startswith("ok - "):
                                result["passed_count"] += 1
                            if message.startswith("not ok - "):
                                failures.append(message.removeprefix("not ok - ").strip())
                                result["seconds"] = round(time.monotonic() - started, 2)
                                save_report(report, result)
                            if line.startswith(("PASS:", "FAIL:")):
                                completed = True
                        exit_code = process.wait()
                    except KeyboardInterrupt:
                        interrupted = True
                        completed = False
                        exit_code = 130
                        interrupt_process(process, container, root)
                        output.write("Interrupted; unrun tests remain unverified.\n")
        except OSError as error:
            print(f"{target}: {error}", file=sys.stderr)
        elapsed = round(time.monotonic() - started, 2)
        result.update(exit_code=exit_code, completed=completed, seconds=elapsed)
        save_report(report, result)
        results.append(result)
        if interrupted:
            print("Interrupted; saved recorded failures and stopped the current test process.")
            break
    if args.failed and results:
        print("These results cover the retried tests only; they are not a new full-suite verification.")
    if any(result["platform"].startswith("linux-") for result in results):
        print("When finished, stop the Linux VM: ./scripts/test-platforms.sh --stop")
    if not args.dry_run:
        mode = "full suite" if args.full else "retried tests" if args.failed else "selected tests"
        print_summary(targets, plans, results, mode)
    if any(result["exit_code"] == 130 for result in results):
        return 130
    return int(any(result["exit_code"] != 0 for result in results))


def handle_interrupt(signum, frame):
    raise KeyboardInterrupt


def main():
    args = parser().parse_args()
    signal.signal(signal.SIGTERM, handle_interrupt)
    signal.signal(signal.SIGHUP, handle_interrupt)
    try:
        return execute(Path(__file__).resolve().parent.parent, args)
    except KeyboardInterrupt:
        print("Interrupted.", file=sys.stderr)
        return 130
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
