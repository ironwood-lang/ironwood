#!/usr/bin/env bash
# SPDX-License-Identifier: MIT OR Apache-2.0
# Linux arm64 run of both M4 checkpoints from this Mac: ships a git archive of
# the given commit to the miami VM (Ubuntu 22.04 aarch64), runs
# linux-arm64-evidence.sh there (the twelve M4.1/M4.2 tests, the fifteen M4.3
# tests and the glibc 2.17 symbol check of the native harness), copies its
# logs into workspace/m4/arm64-evidence and removes the remote tree.
set -u
root=/Users/developer/workspace-mba-m2/Ironwood
commit=${1:?usage: run-evidence.sh COMMIT}
ev=$root/workspace/m4/arm64-evidence
remote=temp/ironwood-m4-arm64
rm -rf "$ev" && mkdir -p "$ev"
git -C "$root" rev-parse "$commit" > "$ev/commit.txt"
status() { echo "$1 exit=$2" >> "$ev/status.txt"; }
git -C "$root" archive "$commit" | ssh -o BatchMode=yes miami \
    "rm -rf $remote && mkdir -p $remote/tree && tar -xf - -C $remote/tree"
status ship $?
ssh -o BatchMode=yes miami "cd $remote && nohup tree/docs/self-hosting/m4/arm64-evidence/linux-arm64-evidence.sh \
    > run.log 2>&1 < /dev/null &"
# The remote script marks its end whatever its statuses; a dropped connection
# only repeats the check.
until ssh -o BatchMode=yes miami "test -e $remote/logs/done" 2>/dev/null; do
    sleep 15
done
for log in status.txt host.txt tests.log abi-build.log runtime-undefined.txt glibc-versions.txt; do
    scp -q "miami:$remote/logs/$log" "$ev/$log.remote" || echo "missing $log" >> "$ev/fetch.log"
done
cat "$ev/status.txt.remote" >> "$ev/status.txt" && rm "$ev/status.txt.remote"
for log in host.txt tests.log abi-build.log runtime-undefined.txt glibc-versions.txt; do
    [ -e "$ev/$log.remote" ] && mv "$ev/$log.remote" "$ev/$log"
done
ssh -o BatchMode=yes miami "rm -rf $remote"
status cleanup $?
cat "$ev/status.txt"
