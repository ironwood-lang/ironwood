// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.ast;

import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourcePosition;
import ironwood.compiler.source.SourceSpan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;

/** Test-only contract calls against original J0; no alternative AST algorithm. */
public final class AstContractProbe {
    private static final SourceSpan SPAN = SourceSpan.at(new SourcePosition(0, 1, 1));
    private static int checks;

    private AstContractProbe() {}

    private static void require(boolean condition, String contract) {
        checks++;
        if (!condition) throw new AssertionError(contract);
    }

    private static void rejects(Class<? extends RuntimeException> expected, Runnable action,
                                String contract) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            require(exception.getClass() == expected, contract + ": exact failure type");
            return;
        }
        throw new AssertionError(contract + ": missing failure");
    }

    private static TypeName type(String name) {
        return TypeName.reference(name, SPAN);
    }

    private static InstanceOfExpression binding(String name, int offset) {
        SourceSpan nameSpan = SourceSpan.at(new SourcePosition(offset, 1, offset + 1));
        return new InstanceOfExpression(new NameExpression("value", SPAN), type("Node"),
                Optional.of(new TypePatternBinding(name, nameSpan, false)), SPAN, SPAN);
    }

    private static Expression and(Expression first, Expression second) {
        return new BinaryExpression(first, BinaryOperator.LOGICAL_AND, second, SPAN, SPAN);
    }

    private static void types() {
        TypeName integer = TypeName.primitive(TypeName.Kind.INT, SPAN);
        TypeName nested = TypeName.reference("pkg.Outer.Inner", List.of(type("A"), integer),
                List.of(0, 1, 1), SPAN);
        require(nested.displayName().equals("pkg.Outer<A>.Inner<int>"), "generic rendering order");
        TypeName qualifier = nested.qualifierReference().orElseThrow();
        require(qualifier.displayName().equals("pkg.Outer<A>"), "qualifier argument prefix");
        require(nested.hasParameterizedQualifier() && nested.lastSegmentTypeArgumentCount() == 1,
                "primitive segment counts");
        require(nested.hasExplicitMemberQualifier(new String("Inner")), "member suffix text");
        require(TypeName.array(TypeName.wildcardExtends(type("A"), SPAN), SPAN)
                .displayName().equals("? extends A[]"), "array/wildcard recursion");
        require(TypeName.wildcardSuper(type("A"), SPAN).displayName().equals("? super A"), "super bound");
        require(TypeName.wildcard(SPAN).displayName().equals("?"), "unbounded wildcard");
        for (String name : List.of("A.", ".A", "A..B", ".", "..", "A..")) {
            String expected = switch (name) {
                case "A.", "A.." -> "A";
                case ".A" -> ".A";
                case "A..B" -> "A..B";
                default -> "";
            };
            require(type(name).displayName().equals(expected), "zero-limit literal dot split " + name);
        }
        require(TypeName.reference("A.", List.of(integer), SPAN).displayName().equals("A<int>"),
                "trailing-dot count offset");
        require(type("A\uD83D\uDE00.B").typeArgumentSegmentCounts().equals(List.of(0, 0)),
                "UTF-16 literal dot counting");
        rejects(IllegalArgumentException.class, () -> type(" "), "blank reference");
        rejects(IllegalArgumentException.class, () -> type(null), "null reference");
        rejects(IllegalArgumentException.class, () -> TypeName.array(
                TypeName.primitive(TypeName.Kind.VOID, SPAN), SPAN), "void array");
        rejects(IllegalArgumentException.class, () -> TypeName.wildcardExtends(null, SPAN), "missing bound");
        rejects(IllegalArgumentException.class, () -> new TypeName(TypeName.Kind.WILDCARD, null,
                List.of(), List.of(), null, TypeName.WildcardKind.UNBOUNDED, integer, SPAN), "extra bound");
        rejects(IllegalArgumentException.class, () -> TypeName.reference("A", List.of(), List.of(1), SPAN),
                "argument total mismatch");
        rejects(IllegalArgumentException.class, () -> TypeName.reference("A.B", List.of(), List.of(-1, 1), SPAN),
                "negative counts after balanced total");
        rejects(IllegalArgumentException.class, () -> TypeName.reference("A.B", List.of(), List.of(0), SPAN),
                "name segment mismatch");
        TypeName wrapping = TypeName.reference("A.B.C.D", List.of(),
                List.of(1 << 30, 1 << 30, 1 << 30, 1 << 30), SPAN);
        require(wrapping.typeArgumentSegmentCounts().size() == 4, "int sum wraps before comparison");
        TypeReference reference = new TypeReference("pkg.Outer.Inner", List.of(type("A"), integer),
                List.of(0, 1, 1), SPAN);
        require(reference.displayName().equals(nested.displayName()), "reference rendering");
        require(reference.hasParameterizedQualifier() && reference.lastSegmentTypeArgumentCount() == 1,
                "reference prefix counts");
        rejects(NullPointerException.class, () -> new TypeReference(null, SPAN), "reference null failure");
        rejects(IllegalArgumentException.class, () -> new TypeReference("A", List.of(), List.of(-1), SPAN),
                "reference negative count");
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ENGLISH);
            for (TypeName.Kind kind : TypeName.Kind.values()) {
                if (kind != TypeName.Kind.REFERENCE && kind != TypeName.Kind.ARRAY && kind != TypeName.Kind.WILDCARD) {
                    require(TypeName.primitive(kind, SPAN).displayName().equals(kind.name().toLowerCase(Locale.ROOT)),
                            "English primitive spelling " + kind.name());
                }
            }
            Locale.setDefault(Locale.forLanguageTag("tr"));
            require(integer.displayName().equals("\u0131nt"), "original locale-sensitive INT limitation");
        } finally {
            Locale.setDefault(original);
        }
    }

    private static void snapshots() {
        Statement child = new ReturnStatement(null, SPAN);
        List<Statement> mutable = new ArrayList<>();
        mutable.add(child);
        Block block = new Block(mutable, SPAN);
        mutable.clear();
        require(block.statements().size() == 1 && block.statements().getFirst() == child,
                "block membership snapshot borrows child");
        rejects(UnsupportedOperationException.class, () -> block.statements().clear(), "immutable snapshot");
        rejects(NullPointerException.class, () -> new Block(null, SPAN), "required null list");
        rejects(NullPointerException.class, () -> new Block(Arrays.asList((Statement) null), SPAN), "null child");
        require(block.equals(new Block(List.of(new ReturnStatement(Optional.empty(), SPAN)), SPAN)),
                "structural child/list equality");
        CompilationUnit unit = new CompilationUnit(SourceFile.of("package-info.iron", ""), null, List.of(), List.of(), SPAN);
        require(unit.packageName().isEmpty(), "null optional package default");
        rejects(NoSuchElementException.class, unit::declaration, "empty unit convenience failure");
        require(!unit.equals(new CompilationUnit(SourceFile.of("package-info.iron", ""), null, List.of(), List.of(), SPAN)),
                "compilation source identity");
        require(SPAN.equals(SourceSpan.at(new SourcePosition(0, 1, 1))), "position/span value equality");
        for (int value : new int[]{0, 0xD800, 65535}) {
            require(new CharacterLiteralExpression(value, SPAN).value() == value, "UTF-16 char range");
        }
        rejects(IllegalArgumentException.class, () -> new CharacterLiteralExpression(-1, SPAN), "low char");
        rejects(IllegalArgumentException.class, () -> new CharacterLiteralExpression(65536, SPAN), "high char");
        rejects(IllegalArgumentException.class, () -> new CatchClause(List.of(), false, "x", SPAN, block, SPAN), "empty catch");
        rejects(IllegalArgumentException.class, () -> new BreakStatement(Optional.of("L"), Optional.empty(), SPAN), "break pairing");
        rejects(IllegalArgumentException.class, () -> new ContinueStatement(Optional.empty(), Optional.of(SPAN), SPAN), "continue pairing");
        rejects(IllegalArgumentException.class, () -> new ArrayCreationExpression(type("A"), null, null, SPAN), "array alternatives");
        require(new ArrayCreationExpression(type("A"), new NameExpression("n", SPAN), SPAN).length().isPresent(), "array length");
        rejects(IllegalArgumentException.class, () -> new TypePatternBinding("x", null, false), "binding span");
        rejects(IllegalArgumentException.class, () -> new TypeParameter(" ", SPAN), "parameter name");
        require(new TypeParameter("T", null, SPAN).bounds().isEmpty(), "null bound default");
    }

    private static void patterns() {
        InstanceOfExpression first = binding(new String("Aa"), 0);
        InstanceOfExpression repeated = binding(new String("Aa"), 1);
        InstanceOfExpression collision = binding("BB", 2);
        InstanceOfExpression later = binding("Aa", 3);
        PatternFlow.Result result = PatternFlow.analyze(and(and(and(first, repeated), collision), later));
        require("Aa".hashCode() == "BB".hashCode(), "colliding content keys");
        require(result.whenTrue().stream().map(b -> b.expression()).toList()
                .equals(List.of(first, repeated, collision, later)), "binding concatenation order");
        require(result.conflicts().size() == 2 && result.conflicts().getLast().earlier().expression() == first
                && result.conflicts().getLast().later().expression() == later, "first source binding retained");
        require(result.conflicts().getLast().span().equals(later.binding().orElseThrow().nameSpan()), "later diagnostic span");
        require(PatternFlow.analyze(and(first, first)).conflicts().isEmpty(), "same expression identity is not conflict");
        InstanceOfExpression equalButDistinct = binding(new String("Aa"), 0);
        require(first.equals(equalButDistinct) && first != equalButDistinct, "equal-looking AST nodes distinct");
        require(PatternFlow.analyze(and(first, equalButDistinct)).conflicts().size() == 1, "identity overlap test");
        for (boolean reverse : new boolean[]{false, true}) {
            InstanceOfExpression laterCollision = binding("BB", 4);
            Expression right = reverse ? and(later, laterCollision) : and(laterCollision, later);
            List<PatternFlow.Conflict> conflicts = PatternFlow.analyze(and(and(first, collision), right)).conflicts();
            require(conflicts.size() == 2
                    && conflicts.getFirst().later().expression() == (reverse ? later : laterCollision)
                    && conflicts.getLast().later().expression() == (reverse ? laterCollision : later),
                    "conflicts follow second-list order");
        }
        PatternFlow.Result negated = PatternFlow.analyze(new UnaryExpression(UnaryOperator.NOT, first, SPAN, SPAN));
        require(negated.whenTrue().isEmpty() && negated.whenFalse().getFirst().expression() == first, "negation fact swap");
        require(PatternFlow.canCompleteNormally(new Block(List.of(), SPAN)), "empty block completion");
        Block stopped = new Block(List.of(new ReturnStatement(null, SPAN)), SPAN);
        require(!PatternFlow.canCompleteNormally(stopped), "last statement completion");
        require(!PatternFlow.canCompleteNormally(new TryStatement(new Block(List.of(), SPAN), List.of(), Optional.of(stopped), SPAN)),
                "finally completion precedence");
        BreakStatement transfer = new BreakStatement(Optional.of(new String("L")), Optional.of(SPAN), SPAN);
        require(PatternFlow.canCompleteNormally(new LabeledStatement("L", SPAN, transfer, SPAN)), "label text equality");
        Statement shadow = new LabeledStatement(new String("L"), SPAN, transfer, SPAN);
        require(!PatternFlow.canCompleteNormally(new LabeledStatement("L", SPAN,
                new Block(List.of(shadow, new ReturnStatement(null, SPAN)), SPAN), SPAN)), "nested label shadowing");
        require(PatternFlow.containsBreakForCurrentLoop(transfer), "current loop transfer");
    }

    private static ClassDeclaration declaration(String name, List<TypeDeclaration> children) {
        return new ClassDeclaration(AccessModifier.PUBLIC, false, false, false, name, SPAN,
                List.of(), Optional.empty(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), children, SPAN);
    }

    private static void declarations() {
        FieldDeclaration first = new FieldDeclaration(AccessModifier.PUBLIC, false, false,
                type("A"), "first", SPAN, null, SPAN);
        FieldDeclaration shared = new FieldDeclaration(AccessModifier.PUBLIC, true, false,
                type("A"), "shared", SPAN, null, SPAN);
        FieldDeclaration last = new FieldDeclaration(AccessModifier.PUBLIC, false, false,
                type("A"), "last", SPAN, null, SPAN);
        List<FieldDeclaration> fields = new ArrayList<>(List.of(first, shared, last));
        ClassDeclaration projected = new ClassDeclaration(AccessModifier.PUBLIC, "Fields", SPAN,
                Optional.empty(), List.of(), fields, List.of(), List.of(), SPAN);
        fields.clear();
        require(projected.instanceInitializations().equals(List.of(first, last)), "instance projection order");
        require(projected.staticInitializations().equals(List.of(shared)), "static projection order");
        require(projected.instanceInitializations().getFirst() == first
                && projected.staticInitializations().getFirst() == shared, "projection retains field identities");
        ClassDeclaration inner = declaration("Inner", List.of());
        ClassDeclaration sibling = declaration("Sibling", List.of());
        ClassDeclaration outer = declaration("Outer", List.of(inner, sibling));
        CompilationUnit unit = new CompilationUnit(SourceFile.of("types.iron", ""),
                Optional.of(new PackageDeclaration("pkg", SPAN, SPAN)), List.of(),
                List.of(outer, projected), SPAN);
        List<DeclaredType> types = DeclaredTypes.in(unit);
        require(types.stream().map(DeclaredType::sourceName).toList()
                .equals(List.of("pkg.Outer", "pkg.Outer.Inner", "pkg.Outer.Sibling", "pkg.Fields")),
                "source pre-order traversal");
        require(types.get(1).binaryName().equals("pkg.Outer$Inner")
                && types.get(1).enclosingBinaryName().equals("pkg.Outer"), "binary nesting spelling");
        require(types.getFirst().enclosingType() == null && types.getFirst().enclosingBinaryName() == null
                && types.get(1).enclosingType() == outer, "top-level and borrowed enclosing identity");
        require(types.get(1).nestHostBinaryName().equals("pkg.Outer"), "nest host from first enclosing type");
        List<TypeDeclaration> path = new ArrayList<>(List.of(outer, inner));
        DeclaredType deep = new DeclaredType(unit, sibling, path, "pkg.Outer.Inner.Sibling", "pkg.Outer$Inner$Sibling");
        path.clear();
        require(deep.enclosingBinaryName().equals("pkg.Outer$Inner") && deep.enclosingType() == inner,
                "independent ordered enclosing snapshot");
    }

    public static void main(String[] args) {
        types();
        snapshots();
        patterns();
        declarations();
        System.out.println("PASS: original AST contracts, " + checks + " checks");
    }
}
