// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

/** A callable input from which a returned reference may originate. */
record ReturnOrigin(Kind kind, int parameterIndex) {
    ReturnOrigin {
        if (kind == null) {
            throw new IllegalArgumentException("return origin requires a kind");
        }
        if (kind == Kind.THIS && parameterIndex != -1) {
            throw new IllegalArgumentException("THIS return origin cannot name a parameter");
        }
        if (kind != Kind.THIS && parameterIndex < 0) {
            throw new IllegalArgumentException("parameter return origin requires a non-negative index");
        }
    }

    static ReturnOrigin thisOrigin() {
        return new ReturnOrigin(Kind.THIS, -1);
    }

    static ReturnOrigin parameter(int index) {
        return new ReturnOrigin(Kind.PARAMETER, index);
    }

    static ReturnOrigin elementOfParameter(int index) {
        return new ReturnOrigin(Kind.ELEMENT_OF_PARAMETER, index);
    }

    enum Kind {
        THIS,
        PARAMETER,
        ELEMENT_OF_PARAMETER
    }
}
