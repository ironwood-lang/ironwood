<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Host networking

The default example prints interface names, current MTUs and captured IP
addresses. Output depends on the host; successful completion returns 0. The
owning enumeration keeps its interface and address views alive. Independent
address cursors are explicitly freed before that owner.

```console
./compile.sh
./link.sh
./run.sh
```

Reachability is a separate opt-in smoke check:

```console
./probe.sh ::1 100
```

The probe uses the current privileges and network configuration. It prints the
numeric target and an observed boolean. Record whether the environment is a
native host, VM/container or translated process when sharing results. The
default interface and TTL are used; an IPv6 literal can name a scope, for example
`fe80::1%en0` on a suitable controlled network.

ICMP availability is not inferred from the boolean. A successful or refused TCP
port-7 connection also returns true, and false does not establish that a host or
service is down. Do not use this probe before connecting to an actual service.
No live probe runs in the default example, compiler or package checks. See
[D156](../../docs/DECISIONS.md#d156---separate-reachability-contract-tests-from-host-smoke-checks).
