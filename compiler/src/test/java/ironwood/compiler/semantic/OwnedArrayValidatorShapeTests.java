// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.ast.ClassDeclaration;
import ironwood.compiler.ast.CompilationUnit;
import ironwood.compiler.ast.DeclaredTypes;
import ironwood.compiler.ast.DestructorDeclaration;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.ir.*;
import ironwood.compiler.lexer.Lexer;
import ironwood.compiler.parser.Parser;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourcePosition;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Exercises late validator branches that earlier source-level safety checks can mask. */
public final class OwnedArrayValidatorShapeTests {
    private OwnedArrayValidatorShapeTests() {}

    public static void defensivePredicates() {
        repeatedLoopStore();
        independentFree();
    }

    private static void repeatedLoopStore() {
        String text = """
                class Item {}
                class Owner {
                    private Item[] items = new Item[2];
                    Owner() {
                        Item value = new Item();
                        for (int i = 0; i < 2; i++) { items[0] = value; }
                    }
                    destructor {
                        for (int i = 0; i < this.items.length; i++) { free this.items[i]; }
                        free items;
                    }
                }
                """;
        Fixture fixture = new Fixture(text);
        IrBasicBlock entry = new IrBasicBlock("entry", fixture.setup(),
                new IrJump("repeat", fixture.span("for (int i = 0;")), fixture.constructorSpan());
        IrBasicBlock repeat = new IrBasicBlock("repeat", List.of(fixture.elementStore()),
                new IrJump("repeat", fixture.span("items[0] = value;")), fixture.constructorSpan());
        fixture.assertSelected(List.of(entry, repeat),
                "each creation-array entry must receive a distinct fresh object exactly once",
                "store can repeat without recreating", "items[0] = value;", false);
    }

    private static void independentFree() {
        String text = """
                class Item {}
                class Owner {
                    private Item[] items = new Item[2];
                    Owner() {
                        Item value = new Item();
                        items[0] = value;
                        free value;
                    }
                    destructor {
                        for (int i = 0; i < this.items.length; i++) { free this.items[i]; }
                        free items;
                    }
                }
                """;
        Fixture fixture = new Fixture(text);
        List<IrInstruction> instructions = new ArrayList<>(fixture.setup());
        instructions.add(fixture.elementStore());
        instructions.add(new IrFreeInstruction(fixture.object(), fixture.span("free value;")));
        IrBasicBlock entry = new IrBasicBlock("entry", instructions,
                new IrReturnTerminator(Optional.empty(), fixture.constructorSpan()),
                fixture.constructorSpan());
        fixture.assertSelected(List.of(entry),
                "a recorded object is reclaimed only by creation-array cleanup",
                "reclaims a recorded object independently", "free value;", true);
    }

    private static final class Fixture {
        private final String text;
        private final SourceFile source;
        private final TypeSymbol owner;
        private final FieldSymbol field;
        private final SourceSpan constructorSpan;
        private final IrValueReference receiver;
        private final IrValueReference array;
        private final IrValueReference object;

        Fixture(String text) {
            this.text = text;
            source = SourceFile.of("DefensiveOwnedArray.iron", text);
            var lexed = new Lexer(source).lex();
            require(lexed.diagnostics().isEmpty(), "defensive fixture did not lex");
            var parsed = new Parser(source, lexed.tokens()).parse();
            require(parsed.diagnostics().isEmpty() && parsed.unit().isPresent(),
                    "defensive fixture did not parse: " + parsed.diagnostics());
            CompilationUnit unit = parsed.unit().orElseThrow();
            owner = DeclaredTypes.in(unit).stream()
                    .filter(type -> type.binaryName().equals("Owner"))
                    .map(type -> new TypeSymbol(type, false)).findFirst().orElseThrow();
            ClassDeclaration declaration = (ClassDeclaration) owner.declaration();
            var fieldDeclaration = declaration.fields().stream()
                    .filter(value -> value.name().equals("items")).findFirst().orElseThrow();
            IrType elementType = IrType.reference("Item");
            IrType arrayType = IrType.array(elementType);
            IrField irField = new IrField("Owner", "items", arrayType, 0,
                    fieldDeclaration.nameSpan());
            field = new FieldSymbol(fieldDeclaration, AccessModifier.PRIVATE, arrayType,
                    "Owner", irField, null, null);
            owner.addField("items", field);
            DestructorDeclaration destructor = declaration.destructor().orElseThrow();
            owner.setDestructor(new CallableSymbol("Owner", "<destructor>", AccessModifier.PUBLIC,
                    false, IrCallableKind.DESTRUCTOR, IrType.VOID, List.of(), List.of(),
                    Optional.of(destructor.body()), Optional.empty(), Optional.empty(),
                    false, false, false, destructor.keywordSpan(), destructor.span(),
                    "Owner.<destructor>"));
            constructorSpan = declaration.constructors().getFirst().span();
            receiver = new IrValueReference(1, IrType.reference("Owner"), constructorSpan);
            array = new IrValueReference(2, arrayType, span("new Item[2]"));
            object = new IrValueReference(3, elementType, span("new Item()"));
        }

        SourceSpan constructorSpan() { return constructorSpan; }
        IrValueReference object() { return object; }

        List<IrInstruction> setup() {
            return List.of(new IrFieldStoreInstruction(receiver, field.irField(), array,
                            span("items = new Item[2]")),
                    new IrAllocateInstruction(object, "Item", span("new Item()")));
        }

        IrArrayStoreInstruction elementStore() {
            SourceSpan site = span("items[0] = value;");
            return new IrArrayStoreInstruction(array, new IrConstant(IrType.I32, 0, site),
                    object, site);
        }

        void assertSelected(List<IrBasicBlock> blocks, String reason, String detail,
                            String operation, boolean firstStore) {
            IrFunction function = new IrFunction("Owner", "<init>", "Owner.<init>",
                    IrType.VOID, List.of(new IrParameter("this", receiver, constructorSpan)),
                    blocks, constructorSpan, source.path().toString(), IrCallableKind.CONSTRUCTOR);
            List<Diagnostic> plain = new ArrayList<>();
            List<Diagnostic> explained = new ArrayList<>();
            new OwnedArrayElementAnalyzer.Checker(owner, field, function, null, plain,
                    false, true, null).check();
            new OwnedArrayElementAnalyzer.Checker(owner, field, function, null, explained,
                    true, true, source).check();
            require(plain.size() == 1 && explained.size() == 1,
                    "defensive checker changed diagnostic multiplicity: " + explained);
            Diagnostic prior = plain.getFirst(), current = explained.getFirst();
            require(current.message().equals(prior.message())
                            && current.message().equals("cannot prove owned elements of 'items' safe: " + reason)
                            && current.span().equals(prior.span()) && prior.notes().isEmpty()
                            && current.notes().size() == (firstStore ? 3 : 2)
                            && current.notes().getFirst().message().contains(detail)
                            && current.notes().getFirst().span().equals(span(operation))
                            && current.notes().getFirst().source().path().equals(source.path())
                            && current.notes().getLast().span().equals(span("free this.items[i];")),
                    "defensive checker lost selected operation or cleanup: " + current);
            if (firstStore) {
                require(current.notes().get(1).span().equals(span("items[0] = value;")),
                        "defensive checker lost recorded object identity: " + current);
            }
        }

        SourceSpan span(String fragment) {
            int start = text.indexOf(fragment);
            require(start >= 0, "missing fixture fragment " + fragment);
            int end = start + fragment.length();
            return new SourceSpan(position(start), position(end));
        }

        private SourcePosition position(int offset) {
            int line = 1;
            int lastBreak = -1;
            for (int index = 0; index < offset; index++) {
                if (text.charAt(index) == '\n') { line++; lastBreak = index; }
            }
            return new SourcePosition(offset, line, offset - lastBreak);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
