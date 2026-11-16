package cc.gahaha.fastmultiversion.javac;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCImport;
import com.sun.tools.javac.tree.JCTree.JCPackageDecl;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Removes imports that would not compile for the target version once pruned code is gone. javac rejects
 * an import of a class or package that does not exist, even if nothing uses it.
 */
final class Imports {
    private Imports() {
    }

    /** Every simple name the file uses outside its package and import declarations. */
    private static Set<Name> usedNames(JCCompilationUnit unit) {
        Set<Name> names = new HashSet<>();
        TreeScanner scanner = new TreeScanner() {
            @Override
            public void visitIdent(JCIdent tree) {
                names.add(tree.name);
            }
        };
        for (JCTree def : unit.defs) {
            if (!(def instanceof JCImport) && !(def instanceof JCPackageDecl)) {
                scanner.scan(def);
            }
        }
        return names;
    }

    /**
     * Removes single imports (including single static imports) whose name the remaining code does not use.
     * An unused import has no effect on the program, but javac still rejects it if the class is missing
     * from this version, which typically happens when only pruned code used it.
     */
    static void removeUnused(JCCompilationUnit unit) {
        Set<Name> used = usedNames(unit);
        ListBuffer<JCTree> kept = new ListBuffer<>();
        for (JCTree def : unit.defs) {
            if (def instanceof JCImport imp && !isOnDemand(imp) && !used.contains(imp.qualid.name)) {
                continue;
            }
            kept.append(def);
        }
        unit.defs = kept.toList();
    }

    /** Removes {@code import name.*;} where {@code name} is neither a package nor a class in this build. */
    static void removeMissingPackages(JCCompilationUnit unit, Predicate<String> exists) {
        ListBuffer<JCTree> kept = new ListBuffer<>();
        for (JCTree def : unit.defs) {
            if (def instanceof JCImport imp && !imp.isStatic() && isOnDemand(imp)
                    && !exists.test(TreeInfo.fullName(imp.qualid.selected).toString())) {
                continue;
            }
            kept.append(def);
        }
        unit.defs = kept.toList();
    }

    /**
     * Removes single imports ({@code import a.b.C;}, {@code import static a.b.C.m;}) of a class that this
     * build does not have but another version does ({@code otherVersion}). That lets a file import a class
     * from each version's package under the same simple name, e.g. a class that moved to another package:
     * each build keeps the import its version has. Imports of classes no version has are kept, so that
     * javac still reports them.
     *
     * <p>Where a version has several of them (e.g. a library kept the old class next to the moved one), the
     * first import of the name wins, as in the IDE: a later class import of the same simple name is removed
     * if both are classes of the database. So the order of the imports says which class a version prefers.
     */
    static void removeOtherVersionClasses(JCCompilationUnit unit, Predicate<String> exists, Predicate<String> otherVersion) {
        ListBuffer<JCTree> kept = new ListBuffer<>();
        Set<Name> imported = new HashSet<>(); // simple names of kept class imports of database classes
        for (JCTree def : unit.defs) {
            if (def instanceof JCImport imp && !isOnDemand(imp)) {
                String className = TreeInfo.fullName(imp.isStatic() ? imp.qualid.selected : imp.qualid).toString();
                if (!exists.test(className) && otherVersion.test(className)) {
                    continue;
                }
                if (!imp.isStatic() && otherVersion.test(className) && !imported.add(imp.qualid.name)) {
                    continue; // an earlier import of the same name, which this version has too, wins
                }
            }
            kept.append(def);
        }
        unit.defs = kept.toList();
    }

    private static boolean isOnDemand(JCImport imp) {
        return imp.qualid.name.contentEquals("*");
    }
}
