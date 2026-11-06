<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# HelloEclipse

This Eclipse sample prints a greeting using managed values. Its builder-returning
helper remains an explicit composition API; GC does not require it as an
ownership workaround. Chained calls and String results may retain ordinary
aliases, including across explicit collection.

Use the checked-in project compile/link/run scripts and focused test entry
points. Resource operations remain explicit. The IDE's current wizard template
also checks a shared String result across Memory.collect(), independently of
resource closure. See [the memory contract](../../docs/MEMORY.md).
