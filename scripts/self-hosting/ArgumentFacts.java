// SPDX-License-Identifier: MIT OR Apache-2.0

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.lang.model.element.*;
import javax.tools.*;

/** Exact actual/formal domains and constant arguments; does not infer lifetimes. */
public final class ArgumentFacts {
    private ArgumentFacts() {}
    private record SourceCall(TreePath path, Tree select, String kind,
                              List<? extends ExpressionTree> arguments,
                              ExpressionTree receiver, TreePath method) {}

    private static String cell(Object value) {
        return String.valueOf(value).replace("\\", "\\\\").replace("\t", "\\t")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String constantUnits(Object value) {
        if (value instanceof String || value instanceof Character) {
            String text = String.valueOf(value);
            StringBuilder encoded = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                String hex = Integer.toHexString(text.charAt(i));
                encoded.append("0000", 0, 4 - hex.length()).append(hex);
            }
            return encoded.toString();
        }
        if (value instanceof Float number) return Integer.toHexString(Float.floatToRawIntBits(number));
        if (value instanceof Double number) return Long.toHexString(Double.doubleToRawLongBits(number));
        return value == null ? "" : String.valueOf(value);
    }

    public static void main(String[] arguments) throws Exception {
        Path root = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path output = Path.of(arguments[1]);
        List<Path> sources;
        try (var stream = Files.walk(root.resolve("compiler/src/main/java"))) {
            sources = stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
                    List.of("--release", "21", "-proc:none"), null, files.getJavaFileObjectsFromPaths(sources));
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            Trees trees = Trees.instance(task);
            Map<Tree, Long> parsedEnds = new IdentityHashMap<>();
            Map<CompilationUnitTree, List<SourceCall>> parsedCalls = new IdentityHashMap<>();
            for (CompilationUnitTree unit : units) {
                List<SourceCall> captured = new ArrayList<>();
                parsedCalls.put(unit, captured);
                new TreePathScanner<Void, Void>() {
                    @Override public Void scan(Tree tree, Void ignored) {
                        if (tree != null) parsedEnds.put(tree, trees.getSourcePositions().getEndPosition(unit, tree));
                        return super.scan(tree, ignored);
                    }
                    void capture(Tree select, String kind, List<? extends ExpressionTree> args, ExpressionTree receiver) {
                        TreePath method = getCurrentPath();
                        while (method != null && !(method.getLeaf() instanceof MethodTree)) method = method.getParentPath();
                        captured.add(new SourceCall(getCurrentPath(), select, kind, List.copyOf(args), receiver, method));
                    }
                    @Override public Void visitMethodInvocation(MethodInvocationTree tree, Void ignored) {
                        capture(tree.getMethodSelect(), "METHOD", tree.getArguments(), tree.getMethodSelect() instanceof MemberSelectTree m ? m.getExpression() : null);
                        return super.visitMethodInvocation(tree, ignored);
                    }
                    @Override public Void visitNewClass(NewClassTree tree, Void ignored) {
                        capture(tree, "CONSTRUCTOR", tree.getArguments(), tree.getEnclosingExpression());
                        return super.visitNewClass(tree, ignored);
                    }
                    @Override public Void visitMemberReference(MemberReferenceTree tree, Void ignored) {
                        capture(tree, "REFERENCE", List.of(), tree.getQualifierExpression());
                        return super.visitMemberReference(tree, ignored);
                    }
                    @Override public Void visitMemberSelect(MemberSelectTree tree, Void ignored) {
                        capture(tree, "FIELD", List.of(), tree.getExpression());
                        return super.visitMemberSelect(tree, ignored);
                    }
                }.scan(unit, null);
            }
            task.analyze();
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)) {
                throw new IllegalStateException(diagnostics.getDiagnostics().toString());
            }
            List<Runnable> pending = new ArrayList<>();
            try (PrintWriter writer = new PrintWriter(output.toFile(), StandardCharsets.UTF_8)) {
                writer.println("file\tline\tstart_utf16\tend_utf16\tconsumer\tkind\tdeclaring_owner\tresolved_signature\tpart\tpart_start\tpart_end\tactual_type\tformal_type\tvalue_kind\tconstant_display\tconstant_encoding\tconstant_units\tsymbol\tpart_source");
                for (CompilationUnitTree unit : units) {
                    String text = unit.getSourceFile().getCharContent(true).toString();
                    String file = root.relativize(Path.of(unit.getSourceFile().toUri())).toString();
                    var collector = new TreePathScanner<Void, Void>() {
                        String consumer = "<class>";
                        @Override public Void scan(Tree tree, Void ignored) {
                            if (tree != null && parsedEnds.getOrDefault(tree, -1L) < 0) return null;
                            return super.scan(tree, ignored);
                        }
                        void part(Tree call, Element member, String kind, String label, ExpressionTree expression, String formal) {
                            long start = trees.getSourcePositions().getStartPosition(unit, call);
                            long end = parsedEnds.getOrDefault(call, -1L);
                            long a = expression == null ? -1 : trees.getSourcePositions().getStartPosition(unit, expression);
                            long b = expression == null ? -1 : parsedEnds.getOrDefault(expression, -1L);
                            TreePath path = expression == null ? null : TreePath.getPath(unit, expression);
                            Element element = path == null ? null : trees.getElement(path);
                            Object constant = expression instanceof LiteralTree literal ? literal.getValue() :
                                    element instanceof VariableElement variable ? variable.getConstantValue() : null;
                            String valueKind = expression == null ? "implicit/type receiver" :
                                    expression.getKind() == Tree.Kind.NULL_LITERAL ? "literal null" :
                                    constant != null ? "literal or constant variable" :
                                    element != null && element.getKind() == ElementKind.ENUM_CONSTANT ? "enum constant" :
                                    element instanceof TypeElement ? "type name, no reference evaluation" :
                                    "dynamic; null/alias/retention not inferred";
                            String symbol = element == null ? "" : element.getEnclosingElement() + "::" + element;
                            writer.println(cell(file) + "\t" + unit.getLineMap().getLineNumber(start) + "\t" + start + "\t" + end + "\t" + cell(consumer)
                                    + "\t" + kind + "\t" + cell(member.getEnclosingElement()) + "\t" + cell(member) + "\t" + label
                                    + "\t" + a + "\t" + b + "\t" + cell(path == null ? "" : trees.getTypeMirror(path)) + "\t" + cell(formal)
                                    + "\t" + valueKind + "\t" + cell(constant == null ? "" : constant)
                                    + "\t" + (constant instanceof String || constant instanceof Character ? "UTF16 hex" : constant instanceof Float || constant instanceof Double ? "IEEE bits hex" : "scalar text")
                                    + "\t" + constantUnits(constant) + "\t" + cell(symbol)
                                    + "\t" + cell(a < 0 ? "" : text.substring((int) a, (int) b)));
                        }
                        void emit(SourceCall captured) {
                            Tree tree = captured.path().getLeaf();
                            Tree select = captured.select();
                            String kind = captured.kind();
                            List<? extends ExpressionTree> args = captured.arguments();
                            ExpressionTree receiver = captured.receiver();
                            TreePath path = select == tree ? captured.path() : new TreePath(captured.path(), select);
                            Element member = trees.getElement(path);
                            if (member == null || member.getEnclosingElement().toString().startsWith("ironwood.")) return;
                            if (kind.equals("FIELD") && !member.getKind().isField()) return;
                            Element method = captured.method() == null ? null : trees.getElement(captured.method());
                            consumer = method == null ? "<class>" : method.getEnclosingElement() + "::" + method;
                            String savedConsumer = consumer;
                            pending.add(() -> {
                                String previous = consumer;
                                consumer = savedConsumer;
                                part(tree, member, kind, "receiver", receiver, "");
                                List<? extends VariableElement> params = member instanceof ExecutableElement executable ? executable.getParameters() : List.of();
                                for (int i = 0; i < args.size(); i++) {
                                    String formal = params.isEmpty() ? "" : params.get(Math.min(i, params.size() - 1)).asType().toString();
                                    part(tree, member, kind, "argument" + i, args.get(i), formal);
                                }
                                consumer = previous;
                            });
                        }
                    };
                    parsedCalls.get(unit).forEach(collector::emit);
                }
                // Write the captured source sites in traversal order.
                pending.forEach(Runnable::run);
            }
        }
    }
}
