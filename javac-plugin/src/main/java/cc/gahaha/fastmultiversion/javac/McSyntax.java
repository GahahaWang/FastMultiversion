package cc.gahaha.fastmultiversion.javac;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCImport;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.util.Name;

/**
 * Recognizes {@code Mc}, {@code Mc.is} and {@code @IfMc} in a parse tree. Names are not resolved yet,
 * so a simple name counts only if the file's imports (or its package) make it refer to our class.
 */
final class McSyntax {
    static final String PACKAGE = "cc.gahaha.fastmultiversion";
    static final String MC = PACKAGE + ".Mc";
    static final String IF_MC = PACKAGE + ".IfMc";

    private final boolean mcBySimpleName;
    private final boolean ifMcBySimpleName;
    private final boolean isByStaticImport;

    McSyntax(JCCompilationUnit unit) {
        boolean mcImported = false;
        boolean otherMc = false;
        boolean ifMcImported = false;
        boolean otherIfMc = false;
        boolean wholePackage = unit.getPackageName() != null && unit.getPackageName().toString().equals(PACKAGE);
        boolean staticIs = false;
        for (JCTree def : unit.defs) {
            if (!(def instanceof JCImport imp)) {
                continue;
            }
            String full = TreeInfo.fullName(imp.qualid).toString();
            String simple = imp.qualid.name.toString();
            if (imp.isStatic()) {
                staticIs |= full.equals(MC + ".is") || full.equals(MC + ".*");
            } else if (full.equals(PACKAGE + ".*")) {
                wholePackage = true;
            } else if (simple.equals("Mc")) {
                mcImported |= full.equals(MC);
                otherMc |= !full.equals(MC);
            } else if (simple.equals("IfMc")) {
                ifMcImported |= full.equals(IF_MC);
                otherIfMc |= !full.equals(IF_MC);
            }
        }
        this.mcBySimpleName = mcImported || (wholePackage && !otherMc);
        this.ifMcBySimpleName = ifMcImported || (wholePackage && !otherIfMc);
        this.isByStaticImport = staticIs;
    }

    /** Whether {@code expr} names the class {@code Mc}. */
    boolean isMc(JCExpression expr) {
        return names(expr, "Mc", mcBySimpleName, MC);
    }

    /**
     * Whether {@code call} is {@code Mc.is(x)}, or {@code is(x)} through a static import.
     *
     * @param shadowed whether an enclosing class declares its own {@code is} method, which hides the import
     */
    boolean isMcIs(JCMethodInvocation call, boolean shadowed) {
        if (call.args.size() != 1) {
            return false;
        }
        if (call.meth instanceof JCFieldAccess select) {
            return select.name.contentEquals("is") && isMc(select.selected);
        }
        return call.meth instanceof JCIdent ident && ident.name.contentEquals("is") && isByStaticImport && !shadowed;
    }

    boolean isIfMc(JCAnnotation annotation) {
        return names((JCExpression) annotation.annotationType, "IfMc", ifMcBySimpleName, IF_MC);
    }

    private static boolean names(JCExpression expr, String simple, boolean bySimpleName, String qualified) {
        if (expr instanceof JCIdent ident) {
            return bySimpleName && ident.name.contentEquals(simple);
        }
        if (expr instanceof JCFieldAccess) {
            Name full = TreeInfo.fullName(expr);
            return full != null && full.contentEquals(qualified);
        }
        return false;
    }
}
