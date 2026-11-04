; SPDX-License-Identifier: MIT OR Apache-2.0
; Original LLVM qualification fixture, not Ironwood frontend output.
target triple = "arm64-apple-darwin"

declare ptr addrspace(1) @mm_new_node() #0
declare void @collect_from_stack()
declare i64 @read_interior(ptr addrspace(1)) #0

define i64 @root_chain(i64 %depth) noinline uwtable gc "statepoint-example" {
entry:
  %p = call ptr addrspace(1) @mm_new_node()
  %slot = getelementptr inbounds i8, ptr addrspace(1) %p, i64 16
  %cookie = add i64 %depth, 123
  store i64 %cookie, ptr addrspace(1) %slot, align 8
  %bottom = icmp eq i64 %depth, 0
  br i1 %bottom, label %collect, label %recurse
collect:
  call void @collect_from_stack()
  br label %done
recurse:
  %next = sub i64 %depth, 1
  %child = call i64 @root_chain(i64 %next)
  br label %done
done:
  %sum = phi i64 [0, %collect], [%child, %recurse]
  %value = call i64 @read_interior(ptr addrspace(1) %slot)
  %result = add i64 %sum, %value
  ret i64 %result
}

attributes #0 = { "gc-leaf-function" }
