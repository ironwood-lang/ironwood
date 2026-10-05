// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;
import ironwood.compiler.source.SourceFile;
import java.nio.file.*;
import java.util.*;
public final class SetRollbackProbe {
 public static void main(String[] args) throws Exception {
  for (String family : List.of("HashSet", "IdentityHashSet", "LinkedHashSet")) {
   String base = "stdlib/src/main/ironwood/ironwood/ds/";
   String iterator = Files.readString(Path.of(base + family + "Iterator.iron"));
   iterator = iterator.replace("private " + family + "<E> owner;", "private " + family + "<E> owner; static " + family + "<?> saved; static int calls;")
    .replace("void reset() {", "void reset() { if (++calls == 2) { saved = this.owner; throw new RuntimeException(); }");
   String main = "package ironwood.ds; class Main { public static int main(String[] args) { try { " + family + "<String> source = new " + family + "<String>(1); source.add(\"entry\"); " + family + "<String> copied = source.copy(); } catch (RuntimeException failure) { return " + family + "Iterator.saved.size(); } return 0; }}";
   for (String form : List.of("source", "classes", "archive")) {
    SourceFile set = switch (form) {
     case "classes" -> IronClass.read(Path.of("compiler/build/stdlib/ironwood/ds/"+family+".ironclass")).source("ironwood.ds."+family).orElseThrow();
     case "archive" -> IronJar.read(Path.of("compiler/build/ironwood-stdlib.ironjar")).source("ironwood.ds."+family).orElseThrow();
     default -> SourceFile.of("test/"+family+".iron",Files.readString(Path.of(base+family+".iron")));
    };
    for (UnfreedMode mode : UnfreedMode.values()) {
     var artifact = new CompilerPipeline(mode).compile(List.of(set,SourceFile.of("test/"+family+"Iterator.iron",iterator),SourceFile.of("test/Main.iron",main)));
     String report = family+" "+form+" "+mode+" successful="+artifact.successful()+"\n"+artifact.diagnostics().stream().map(d->d.message()).reduce("",(a,b)->a+b+"\n");
     Files.writeString(Path.of("workspace/m1/"+family+"-rollback-"+form+"-"+mode+".log"),report);
     System.out.print(report);
    }
   }
  }
 }
}
