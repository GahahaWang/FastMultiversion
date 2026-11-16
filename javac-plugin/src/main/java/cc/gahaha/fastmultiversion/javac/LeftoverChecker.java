package cc.gahaha.fastmultiversion.javac;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCMemberReference;
import com.sun.tools.javac.tree.JCTree.JCNewClass;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.Name;
import java.util.List;
import java.util.Map;

/**
 * Runs after attribution, on the code that is kept. Reports
 * <ul>
 *   <li>any remaining use of {@code Mc}: every valid {@code Mc.is} call was replaced before attribution,
 *       so anything left would call the throwing stub at runtime (an {@code @Mc.Is} annotation is fine:
 *       it is only retained in the source);</li>
 *   <li>a hint next to javac's "cannot find symbol" when the name belongs to a declaration that
 *       {@code @Mc.Is} removed from this build.</li>
 * </ul>
 */
final class LeftoverChecker extends TreeScanner {
    private final MultiversionListener host;
    private final CompilationUnitTree unit;
    private final Map<String, List<String>> removed;

    LeftoverChecker(MultiversionListener host, CompilationUnitTree unit, Map<String, List<String>> removed) {
        this.host = host;
        this.unit = unit;
        this.removed = removed;
    }

    @Override
    public void visitIdent(JCIdent tree) {
        check(tree.sym, tree.name, tree);
        super.visitIdent(tree);
    }

    @Override
    public void visitSelect(JCFieldAccess tree) {
        check(tree.sym, tree.name, tree);
        super.visitSelect(tree);
    }

    @Override
    public void visitReference(JCMemberReference tree) {
        check(tree.sym, tree.name, tree);
        super.visitReference(tree);
    }

    @Override
    public void visitAnnotation(JCAnnotation tree) {
        // @Mc.Is of a kept declaration: retained in the source only, so nothing of it reaches the bytecode.
        Symbol type = TreeInfo.symbol(tree.annotationType);
        if (type == null || !type.getQualifiedName().contentEquals(McSyntax.MC_IS)) {
            super.visitAnnotation(tree);
        }
    }

    @Override
    public void visitNewClass(JCNewClass tree) {
        if (isMc(tree.constructor)) {
            check(tree.constructor, null, tree);
        }
        super.visitNewClass(tree);
    }

    private void check(Symbol sym, Name name, JCTree at) {
        if (isMc(sym)) {
            host.error(at, unit, "Mc can only be used as a direct call Mc.is(\"<range>\") with a range written in "
                    + "this file; this use would stay in the bytecode and throw at runtime");
        } else if (name != null && isUnresolved(sym, at) && removed.containsKey(name.toString())) {
            for (String description : removed.get(name.toString())) {
                host.note(at, unit, description + ", so it is not part of the Minecraft " + host.rawTarget()
                        + " build. Use it only inside a matching Mc.is(...) branch or a declaration with a "
                        + "matching @Mc.Is.");
            }
        }
    }

    private static boolean isUnresolved(Symbol sym, JCTree at) {
        return sym == null || sym.kind == Kind.ERR || sym.kind.isResolutionError()
                || (at.type != null && at.type.isErroneous());
    }

    private static boolean isMc(Symbol sym) {
        if (sym == null || sym.kind.isResolutionError() || sym.kind == Kind.ERR) {
            return false;
        }
        Symbol type = sym.kind == Kind.TYP ? sym : sym.owner;
        return type != null && type.kind == Kind.TYP && type.getQualifiedName().contentEquals(McSyntax.MC);
    }
}
