// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

public record IrPhiIncoming(String predecessor, IrOperand value) {
}
