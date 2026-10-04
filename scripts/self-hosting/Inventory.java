// SPDX-License-Identifier: MIT OR Apache-2.0

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.tools.*;

/** Attributed source inventory. Does not use textual import counts as a gate. */
public final class Inventory {
    private Inventory() {}

    private static String cell(Object value) {
        return String.valueOf(value).replace("\\", "\\\\").replace("\t", "\\t")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            List<Path> sources;
            try (var paths = Files.walk(root.resolve("compiler/src/main/java"))) {
                sources = paths.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            }
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
                    List.of("--release", "21", "-proc:none"), null,
                    files.getJavaFileObjectsFromPaths(sources));
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            task.analyze();
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)) {
                throw new IllegalStateException(diagnostics.getDiagnostics().toString());
            }
            Trees trees = Trees.instance(task);
            try (PrintWriter calls = new PrintWriter(output.resolve("calls.tsv").toFile(), StandardCharsets.UTF_8);
                 PrintWriter syntax = new PrintWriter(output.resolve("syntax.tsv").toFile(), StandardCharsets.UTF_8);
                 PrintWriter containers = new PrintWriter(output.resolve("containers.tsv").toFile(), StandardCharsets.UTF_8);
                 PrintWriter uses = new PrintWriter(output.resolve("references.tsv").toFile(), StandardCharsets.UTF_8);
                 PrintWriter captures = new PrintWriter(output.resolve("captures.tsv").toFile(), StandardCharsets.UTF_8);
                 PrintWriter flow = new PrintWriter(output.resolve("flow.tsv").toFile(), StandardCharsets.UTF_8);
                 PrintWriter traversal = new PrintWriter(output.resolve("traversals.tsv").toFile(), StandardCharsets.UTF_8)) {
                calls.println("file\tline\tstart_utf16\tend_utf16\tconsumer\tkind\tdeclaring_owner\tresolved_signature\tinstantiated_type\texpression");
                syntax.println("file\tline\tstart_utf16\tend_utf16\tconsumer\tkind\tcontext\texpression");
                containers.println("file\tline\tstart_utf16\tend_utf16\tconsumer\tsymbol\ttype\tinitializer");
                uses.println("file\tline\tstart_utf16\tend_utf16\tconsumer\tsymbol\tparent_kind\texpression");
                captures.println("file\tline\tstart_utf16\tend_utf16\tconsumer\tcallback_start\tcaptured_symbol\tcaptured_type\tretention_context");
                flow.println("from\tto\tkind\tfile\tline");
                traversal.println("file\tline\tstart_utf16\tend_utf16\tconsumer\tnode\tkind\texpression");
                for (CompilationUnitTree unit : units) {
                    new TreePathScanner<Void, Void>() {
                        String consumer = "<class>";
                        @Override public Void scan(Tree tree, Void ignored) {
                            if (tree != null && trees.getSourcePositions().getEndPosition(unit, tree) < 0) return null;
                            return super.scan(tree, ignored);
                        }
                        String location(Tree tree) {
                            long start = trees.getSourcePositions().getStartPosition(unit, tree);
                            long end = trees.getSourcePositions().getEndPosition(unit, tree);
                            return cell(root.relativize(Path.of(unit.getSourceFile().toUri()))) + "\t" +
                                    unit.getLineMap().getLineNumber(Math.max(0, start)) + "\t" + start + "\t" + end + "\t" + cell(consumer);
                        }
                        String symbol(Element e) {
                            if (e == null) return "<unresolved>";
                            Element parent = e.getEnclosingElement();
                            if (parent instanceof ExecutableElement) {
                                String declaration = "";
                                TreePath path = trees.getPath(e);
                                if (e instanceof VariableElement && path != null) {
                                    long position = trees.getSourcePositions().getStartPosition(path.getCompilationUnit(), path.getLeaf());
                                    declaration = "@" + root.relativize(Path.of(path.getCompilationUnit().getSourceFile().toUri())) + ":" + position;
                                }
                                return parent.getEnclosingElement() + "::" + parent + declaration + "::" + e;
                            }
                            return parent + "::" + e;
                        }
                        String type(TreePath path) {
                            TypeMirror type = trees.getTypeMirror(path);
                            return type == null ? "" : type.toString();
                        }
                        boolean container(String type) {
                            return type.matches(".*(?:Map|Set|List|Deque|BitSet|Queue)(?:<.*>)?");
                        }
                        boolean aggregate(TreePath path) {
                            return type(path).matches(".*(?:Map|Set|List|Deque|Queue|Stream|Iterator|Collection|Collector|Entry|BitSet)(?:<.*>)?") ||
                                    type(path).endsWith("[]");
                        }
                        String node(Tree tree) {
                            return "E:" + root.relativize(Path.of(unit.getSourceFile().toUri())) + ":" +
                                    trees.getSourcePositions().getStartPosition(unit, tree) + ":" +
                                    trees.getSourcePositions().getEndPosition(unit, tree);
                        }
                        void edge(String from, String to, String kind, Tree tree) {
                            flow.println(cell(from) + "\t" + cell(to) + "\t" + kind + "\t" +
                                    cell(root.relativize(Path.of(unit.getSourceFile().toUri()))) + "\t" +
                                    unit.getLineMap().getLineNumber(Math.max(0, trees.getSourcePositions().getStartPosition(unit, tree))));
                        }
                        void traversal(Tree tree, String kind, Tree source) {
                            traversal.println(location(tree) + "\t" + cell(node(source)) + "\t" + kind + "\t" + cell(tree));
                        }
                        void call(Tree tree, Tree select, String kind) {
                            TreePath path = select == tree ? getCurrentPath() : new TreePath(getCurrentPath(), select);
                            Element e = trees.getElement(path);
                            if (e != null) {
                                calls.println(location(tree) + "\t" + kind + "\t" + cell(e.getEnclosingElement()) +
                                        "\t" + cell(e) + "\t" + cell(type(path)) + "\t" + cell(tree));
                                String target = e.getEnclosingElement() + "::" + e;
                                List<? extends ExpressionTree> arguments = tree instanceof MethodInvocationTree m ? m.getArguments() :
                                        tree instanceof NewClassTree n ? n.getArguments() : List.of();
                                boolean internal = e.getEnclosingElement().toString().startsWith("ironwood.");
                                if (internal) {
                                    edge("R:" + target, node(tree), "RETURNED", tree);
                                    List<? extends VariableElement> params = ((ExecutableElement)e).getParameters();
                                    for (int i = 0; i < Math.min(params.size(), arguments.size()); i++) {
                                        edge(node(arguments.get(i)), "V:" + symbol(params.get(i)), "ARGUMENT", tree);
                                        if (tree instanceof NewClassTree && e.getEnclosingElement().getKind() == ElementKind.RECORD) {
                                            VariableElement param = params.get(i);
                                            String argument = node(arguments.get(i));
                                            e.getEnclosingElement().getEnclosedElements().stream()
                                                    .filter(f -> f.getKind().isField() && f.getSimpleName().equals(param.getSimpleName()))
                                                    .forEach(f -> edge(argument, "V:" + symbol(f), "RECORD_ARGUMENT", tree));
                                        }
                                    }
                                }
                                if (tree instanceof NewClassTree || aggregate(getCurrentPath())) {
                                    if (select instanceof MemberSelectTree member)
                                        edge(node(member.getExpression()), node(tree), "VIEW_OR_RESULT", tree);
                                    for (ExpressionTree argument : arguments)
                                        edge(node(argument), node(tree), "COPY_OR_FACTORY", tree);
                                }
                                if (tree instanceof MethodInvocationTree && select instanceof MemberSelectTree member) {
                                    String name = e.getSimpleName().toString();
                                    if (Set.of("put", "putIfAbsent", "add", "addAll", "putAll", "merge", "compute", "computeIfAbsent").contains(name))
                                        for (ExpressionTree argument : arguments)
                                            edge(node(argument), node(member.getExpression()), "RETAINED", tree);
                                    if (Set.of("iterator", "stream", "forEach", "removeIf", "replaceAll", "putAll", "addAll", "equals", "containsAll", "removeAll", "retainAll").contains(name))
                                        traversal(tree, "CALL:" + name, member.getExpression());
                                    if (tree instanceof MethodInvocationTree && aggregate(getCurrentPath()) &&
                                            Set.of("copyOf", "toArray", "toList", "collect").contains(name))
                                        traversal(tree, "COPY:" + name, member.getExpression());
                                    if (Set.of("copyOf", "putAll", "addAll", "equals", "containsAll", "removeAll", "retainAll").contains(name))
                                        for (ExpressionTree argument : arguments)
                                            traversal(tree, "ARGUMENT_TRAVERSAL:" + name, argument);
                                }
                                if (tree instanceof NewClassTree)
                                    for (ExpressionTree argument : arguments)
                                        if (aggregate(new TreePath(getCurrentPath(), argument)))
                                            traversal(tree, "CONSTRUCTOR_COPY", argument);
                                if (e.getKind() == ElementKind.METHOD && e.getEnclosingElement().getKind() == ElementKind.RECORD &&
                                        ((ExecutableElement) e).getParameters().isEmpty()) {
                                    e.getEnclosingElement().getEnclosedElements().stream()
                                            .filter(f -> f.getKind().isField() && f.getSimpleName().equals(e.getSimpleName()))
                                            .filter(f -> container(f.asType().toString())).forEach(f ->
                                                uses.println(location(tree) + "\t" + cell(symbol(f)) +
                                                        "\tRECORD_ACCESSOR\t" + cell(tree)));
                                    e.getEnclosingElement().getEnclosedElements().stream()
                                            .filter(f -> f.getKind().isField() && f.getSimpleName().equals(e.getSimpleName()))
                                            .forEach(f -> edge("V:" + symbol(f), node(tree), "RECORD_ACCESSOR", tree));
                                }
                            }
                        }
                        void construct(Tree tree, String kind, Object context) {
                            Object expression = tree instanceof ClassTree c ? c.getSimpleName() : tree;
                            syntax.println(location(tree) + "\t" + kind + "\t" + cell(context) + "\t" + cell(expression));
                        }
                        void capture(Tree tree) {
                            long start = trees.getSourcePositions().getStartPosition(unit, tree);
                            long end = trees.getSourcePositions().getEndPosition(unit, tree);
                            Set<Element> seen = new HashSet<>();
                            new TreePathScanner<Void, Void>() {
                                void inspect() {
                                    Element e = trees.getElement(getCurrentPath());
                                    if (e == null) return;
                                    TreePath declaration = trees.getPath(e);
                                    long declarationStart = declaration == null ? -1 :
                                            trees.getSourcePositions().getStartPosition(unit, declaration.getLeaf());
                                    boolean outerLocal = e instanceof VariableElement && !e.getKind().isField() &&
                                            (declarationStart < start || declarationStart > end);
                                    boolean receiver = getCurrentPath().getLeaf() instanceof IdentifierTree &&
                                            (e.getKind().isField() || e.getKind() == ElementKind.METHOD) &&
                                            !e.getModifiers().contains(Modifier.STATIC) &&
                                            (declarationStart < start || declarationStart > end);
                                    if ((outerLocal || receiver) && seen.add(e)) {
                                        captures.println(location(getCurrentPath().getLeaf()) + "\t" + start + "\t" +
                                                cell(symbol(e)) + "\t" + cell(e.asType()) + "\t" +
                                                cell(InventoryContext(tree)));
                                    }
                                }
                                @Override public Void visitIdentifier(IdentifierTree t, Void v) {
                                    inspect(); return super.visitIdentifier(t, v);
                                }
                                @Override public Void visitMemberSelect(MemberSelectTree t, Void v) {
                                    inspect(); return super.visitMemberSelect(t, v);
                                }
                            }.scan(getCurrentPath(), null);
                        }
                        Object InventoryContext(Tree tree) {
                            return getCurrentPath().getParentPath().getLeaf().getKind() + ":" + type(getCurrentPath());
                        }
                        @Override public Void visitMethod(MethodTree tree, Void ignored) {
                            String old = consumer;
                            consumer = symbol(trees.getElement(getCurrentPath()));
                            if (tree.getParameters().stream().anyMatch(p -> p.toString().contains("...")))
                                construct(tree, "VARARGS", consumer);
                            Void result = super.visitMethod(tree, ignored);
                            consumer = old;
                            return result;
                        }
                        @Override public Void visitMethodInvocation(MethodInvocationTree tree, Void ignored) {
                            call(tree, tree.getMethodSelect(), "METHOD");
                            return super.visitMethodInvocation(tree, ignored);
                        }
                        @Override public Void visitNewClass(NewClassTree tree, Void ignored) {
                            call(tree, tree, "CONSTRUCTOR");
                            if (tree.getClassBody() != null) {
                                construct(tree, "ANONYMOUS", InventoryContext(tree)); capture(tree);
                            }
                            return super.visitNewClass(tree, ignored);
                        }
                        @Override public Void visitMemberReference(MemberReferenceTree tree, Void ignored) {
                            call(tree, tree, "REFERENCE");
                            construct(tree, "METHOD_REFERENCE", InventoryContext(tree)); capture(tree);
                            return super.visitMemberReference(tree, ignored);
                        }
                        @Override public Void visitLambdaExpression(LambdaExpressionTree tree, Void ignored) {
                            construct(tree, "LAMBDA", InventoryContext(tree)); capture(tree);
                            if (tree.getBody() instanceof ExpressionTree expression)
                                edge(node(expression), node(tree), "CALLBACK_RESULT", tree);
                            return super.visitLambdaExpression(tree, ignored);
                        }
                        @Override public Void visitClass(ClassTree tree, Void ignored) {
                            if (tree.getKind() == Tree.Kind.RECORD || tree.getModifiers().getFlags().contains(Modifier.SEALED))
                                construct(tree, tree.getKind().toString(), tree.getSimpleName());
                            return super.visitClass(tree, ignored);
                        }
                        @Override public Void visitBindingPattern(BindingPatternTree tree, Void ignored) {
                            construct(tree, "TYPE_PATTERN", getCurrentPath().getParentPath().getLeaf().getKind());
                            return super.visitBindingPattern(tree, ignored);
                        }
                        @Override public Void visitTry(TryTree tree, Void ignored) {
                            if (!tree.getResources().isEmpty()) construct(tree, "RESOURCE_TRY", tree.getResources());
                            return super.visitTry(tree, ignored);
                        }
                        @Override public Void visitLiteral(LiteralTree tree, Void ignored) {
                            if (tree.getKind() == Tree.Kind.NULL_LITERAL) construct(tree, "NULL", "null contract");
                            return super.visitLiteral(tree, ignored);
                        }
                        @Override public Void visitSynchronized(SynchronizedTree tree, Void ignored) {
                            construct(tree, "SYNCHRONIZED", tree.getExpression());
                            return super.visitSynchronized(tree, ignored);
                        }
                        @Override public Void visitVariable(VariableTree tree, Void ignored) {
                            Element e = trees.getElement(getCurrentPath());
                            if (e != null && tree.getInitializer() != null)
                                edge(node(tree.getInitializer()), "V:" + symbol(e), "INITIALIZER", tree);
                            if (e != null && e.getEnclosingElement() instanceof ExecutableElement method &&
                                    method.getEnclosingElement().getKind() == ElementKind.RECORD && e.getKind() == ElementKind.PARAMETER)
                                method.getEnclosingElement().getEnclosedElements().stream()
                                        .filter(f -> f.getKind().isField() && f.getSimpleName().equals(e.getSimpleName()))
                                        .forEach(f -> edge("V:" + symbol(e), "V:" + symbol(f), "RECORD_COMPONENT", tree));
                            if (e != null && e.getKind() == ElementKind.LOCAL_VARIABLE && tree.getInitializer() == null)
                                construct(tree, "UNINITIALIZED", tree.getType());
                            if (e != null && container(e.asType().toString()))
                                containers.println(location(tree) + "\t" + cell(symbol(e)) + "\t" + cell(e.asType()) +
                                        "\t" + cell(tree.getInitializer()));
                            return super.visitVariable(tree, ignored);
                        }
                        void reference(Tree tree) {
                            Element e = trees.getElement(getCurrentPath());
                            if (e instanceof VariableElement)
                                edge("V:" + symbol(e), node(tree), "READ", tree);
                            if (e != null && e.getKind().isField() &&
                                    !e.getEnclosingElement().toString().startsWith("ironwood."))
                                calls.println(location(tree) + "\tFIELD\t" + cell(e.getEnclosingElement()) +
                                        "\t" + cell(e) + "\t" + cell(e.asType()) + "\t" + cell(tree));
                            if (e instanceof VariableElement && container(e.asType().toString())) {
                                Tree parent = getCurrentPath().getParentPath().getLeaf();
                                uses.println(location(tree) + "\t" + cell(symbol(e)) + "\t" + parent.getKind() + "\t" + cell(parent));
                            }
                        }
                        @Override public Void visitIdentifier(IdentifierTree tree, Void ignored) {
                            reference(tree);
                            return super.visitIdentifier(tree, ignored);
                        }
                        @Override public Void visitMemberSelect(MemberSelectTree tree, Void ignored) {
                            reference(tree);
                            return super.visitMemberSelect(tree, ignored);
                        }
                        @Override public Void visitReturn(ReturnTree tree, Void ignored) {
                            if (tree.getExpression() != null) {
                                TreePath context = getCurrentPath().getParentPath();
                                while (context != null && !(context.getLeaf() instanceof LambdaExpressionTree) &&
                                        !(context.getLeaf() instanceof MethodTree)) context = context.getParentPath();
                                String result = context != null && context.getLeaf() instanceof LambdaExpressionTree ?
                                        node(context.getLeaf()) : "R:" + consumer;
                                edge(node(tree.getExpression()), result, "RETURN", tree);
                            }
                            return super.visitReturn(tree, ignored);
                        }
                        @Override public Void visitAssignment(AssignmentTree tree, Void ignored) {
                            Element e = trees.getElement(new TreePath(getCurrentPath(), tree.getVariable()));
                            if (e != null) edge(node(tree.getExpression()), "V:" + symbol(e), "ASSIGNMENT", tree);
                            return super.visitAssignment(tree, ignored);
                        }
                        @Override public Void visitConditionalExpression(ConditionalExpressionTree tree, Void ignored) {
                            edge(node(tree.getTrueExpression()), node(tree), "CHOICE", tree);
                            edge(node(tree.getFalseExpression()), node(tree), "CHOICE", tree);
                            return super.visitConditionalExpression(tree, ignored);
                        }
                        @Override public Void visitTypeCast(TypeCastTree tree, Void ignored) {
                            edge(node(tree.getExpression()), node(tree), "CAST", tree);
                            return super.visitTypeCast(tree, ignored);
                        }
                        @Override public Void visitParenthesized(ParenthesizedTree tree, Void ignored) {
                            edge(node(tree.getExpression()), node(tree), "PARENTHESIS", tree);
                            return super.visitParenthesized(tree, ignored);
                        }
                        @Override public Void visitEnhancedForLoop(EnhancedForLoopTree tree, Void ignored) {
                            traversal(tree, "ENHANCED_FOR", tree.getExpression());
                            Element e = trees.getElement(new TreePath(getCurrentPath(), tree.getVariable()));
                            if (e != null) edge(node(tree.getExpression()), "V:" + symbol(e), "ITERATED_ELEMENT", tree);
                            return super.visitEnhancedForLoop(tree, ignored);
                        }
                    }.scan(unit, null);
                }
            }
            System.out.println("Attributed " + sources.size() + " production sources; no javac errors");
        }
    }
}
