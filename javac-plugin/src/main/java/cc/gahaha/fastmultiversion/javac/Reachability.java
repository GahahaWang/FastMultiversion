package cc.gahaha.fastmultiversion.javac;

import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCBreak;
import com.sun.tools.javac.tree.JCTree.JCCase;
import com.sun.tools.javac.tree.JCTree.JCCatch;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCContinue;
import com.sun.tools.javac.tree.JCTree.JCDefaultCaseLabel;
import com.sun.tools.javac.tree.JCTree.JCDoWhileLoop;
import com.sun.tools.javac.tree.JCTree.JCEnhancedForLoop;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCForLoop;
import com.sun.tools.javac.tree.JCTree.JCIf;
import com.sun.tools.javac.tree.JCTree.JCLabeledStatement;
import com.sun.tools.javac.tree.JCTree.JCLambda;
import com.sun.tools.javac.tree.JCTree.JCLiteral;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCSwitch;
import com.sun.tools.javac.tree.JCTree.JCSwitchExpression;
import com.sun.tools.javac.tree.JCTree.JCSynchronized;
import com.sun.tools.javac.tree.JCTree.JCTry;
import com.sun.tools.javac.tree.JCTree.JCWhileLoop;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Replaces pruned statements without changing how the code around them flows.
 *
 * <p>Pruning runs before javac's flow analysis, so simply deleting a branch could change what javac
 * considers reachable: deleting a branch that ends in {@code return} can turn into "missing return
 * statement", and deleting a {@code break outer;} can make the code after that loop unreachable. The
 * stand-in keeps the two properties flow analysis looks at (JLS 14.22): whether the statement can
 * complete normally, and which enclosing statements it can break out of or continue.
 *
 * <p>Constant conditions are only recognized as the literal {@code true}, so a loop on a
 * {@code static final boolean} is treated as one that may end. That matches valid code in every case
 * except a constant-true loop whose only exits sit in the pruned branch.
 */
final class Reachability {
    private Reachability() {
    }

    /** A statement with the same flow properties as {@code dead}, but no code. */
    static JCStatement standIn(TreeMaker make, JCStatement dead) {
        make.at(dead.pos);
        ListBuffer<JCStatement> stats = new ListBuffer<>();
        for (Jump jump : exits(dead)) {
            JCStatement statement = jump.isBreak() ? make.Break(jump.label()) : make.Continue(jump.label());
            stats.append(make.If(make.Parens(make.Literal(TypeTag.BOOLEAN, 1)), statement, null));
        }
        if (!canCompleteNormally(dead)) {
            stats.append(make.Throw(make.Literal(TypeTag.BOT, null)));
        }
        return make.Block(0, stats.toList());
    }

    /** JLS 14.22, for statements that compile. */
    static boolean canCompleteNormally(JCStatement s) {
        return canCompleteNormally(s, null);
    }

    private static boolean canCompleteNormally(JCStatement s, Name label) {
        return switch (s.getTag()) {
            case BLOCK -> {
                List<JCStatement> stats = ((JCBlock) s).stats;
                yield stats.isEmpty() || canCompleteNormally(stats.last(), null);
            }
            case LABELLED -> {
                JCLabeledStatement labeled = (JCLabeledStatement) s;
                yield canCompleteNormally(labeled.body, labeled.label) || breaksOut(labeled.body, labeled.label, true);
            }
            case IF -> {
                JCIf ifs = (JCIf) s;
                yield ifs.elsepart == null || canCompleteNormally(ifs.thenpart) || canCompleteNormally(ifs.elsepart);
            }
            case WHILELOOP -> {
                JCWhileLoop loop = (JCWhileLoop) s;
                yield !isTrue(loop.cond) || breaksOut(loop.body, label, false);
            }
            case DOLOOP -> {
                JCDoWhileLoop loop = (JCDoWhileLoop) s;
                boolean reachesCondition = canCompleteNormally(loop.body) || continues(loop.body, label);
                yield (reachesCondition && !isTrue(loop.cond)) || breaksOut(loop.body, label, false);
            }
            case FORLOOP -> {
                JCForLoop loop = (JCForLoop) s;
                yield (loop.cond != null && !isTrue(loop.cond)) || breaksOut(loop.body, label, false);
            }
            case SWITCH -> switchCanCompleteNormally((JCSwitch) s, label);
            case SYNCHRONIZED -> canCompleteNormally(((JCSynchronized) s).body);
            case TRY -> {
                JCTry t = (JCTry) s;
                boolean body = canCompleteNormally(t.body);
                for (JCCatch c : t.catchers) {
                    body |= canCompleteNormally(c.body);
                }
                yield body && (t.finalizer == null || canCompleteNormally(t.finalizer));
            }
            case BREAK, CONTINUE, RETURN, THROW, YIELD -> false;
            default -> true; // expression statements, declarations, local classes, empty statements, assert
        };
    }

    private static boolean switchCanCompleteNormally(JCSwitch s, Name label) {
        if (s.cases.isEmpty()) {
            return true;
        }
        for (JCCase c : s.cases) {
            if (breaksOut(c, label, false)) {
                return true;
            }
        }
        // Without a default some value may match no case. Pattern switches can be exhaustive without
        // one, which this check does not see; it then errs towards "can complete".
        boolean hasDefault = s.cases.stream().anyMatch(c -> c.labels.stream().anyMatch(l -> l instanceof JCDefaultCaseLabel));
        if (!hasDefault) {
            return true;
        }
        if (s.cases.head.caseKind == JCCase.RULE) {
            return s.cases.stream().anyMatch(c -> c.stats.isEmpty() || canCompleteNormally(c.stats.last()));
        }
        List<JCStatement> last = s.cases.last().stats;
        return last.isEmpty() || canCompleteNormally(last.last());
    }

    private static boolean isTrue(JCExpression cond) {
        return TreeInfo.skipParens(cond) instanceof JCLiteral literal
                && literal.typetag == TypeTag.BOOLEAN && Objects.equals(literal.value, 1);
    }

    /**
     * Whether {@code body} breaks out to the statement that directly contains it. An unlabeled break
     * counts unless {@code labeledOnly}; a labeled one counts if it names {@code label}.
     */
    private static boolean breaksOut(JCTree body, Name label, boolean labeledOnly) {
        for (Jump jump : exits(body)) {
            if (jump.isBreak() && (jump.label() == null ? !labeledOnly : jump.label().equals(label))) {
                return true;
            }
        }
        return false;
    }

    private static boolean continues(JCTree body, Name label) {
        for (Jump jump : exits(body)) {
            if (!jump.isBreak() && (jump.label() == null || jump.label().equals(label))) {
                return true;
            }
        }
        return false;
    }

    /** A break or continue that leaves the statement it is found in. */
    record Jump(boolean isBreak, Name label) {
    }

    /** The breaks and continues inside {@code tree} whose target lies outside it. */
    static Set<Jump> exits(JCTree tree) {
        ExitScanner scanner = new ExitScanner();
        scanner.scan(tree);
        return scanner.jumps;
    }

    private static final class ExitScanner extends TreeScanner {
        final Set<Jump> jumps = new LinkedHashSet<>();
        private final Set<Name> labels = new HashSet<>();
        private int breakTargets;
        private int loops;

        @Override
        public void visitBreak(JCBreak tree) {
            if (tree.label == null ? breakTargets == 0 : !labels.contains(tree.label)) {
                jumps.add(new Jump(true, tree.label));
            }
        }

        @Override
        public void visitContinue(JCContinue tree) {
            if (tree.label == null ? loops == 0 : !labels.contains(tree.label)) {
                jumps.add(new Jump(false, tree.label));
            }
        }

        @Override
        public void visitLabelled(JCLabeledStatement tree) {
            labels.add(tree.label);
            super.visitLabelled(tree);
            labels.remove(tree.label);
        }

        @Override
        public void visitWhileLoop(JCWhileLoop tree) {
            loop(() -> super.visitWhileLoop(tree));
        }

        @Override
        public void visitDoLoop(JCDoWhileLoop tree) {
            loop(() -> super.visitDoLoop(tree));
        }

        @Override
        public void visitForLoop(JCForLoop tree) {
            loop(() -> super.visitForLoop(tree));
        }

        @Override
        public void visitForeachLoop(JCEnhancedForLoop tree) {
            loop(() -> super.visitForeachLoop(tree));
        }

        @Override
        public void visitSwitch(JCSwitch tree) {
            breakTargets++;
            super.visitSwitch(tree);
            breakTargets--;
        }

        private void loop(Runnable body) {
            breakTargets++;
            loops++;
            body.run();
            loops--;
            breakTargets--;
        }

        // Jumps cannot leave these, so their contents never exit the scanned statement.
        @Override
        public void visitLambda(JCLambda tree) {
        }

        @Override
        public void visitClassDef(JCClassDecl tree) {
        }

        @Override
        public void visitSwitchExpression(JCSwitchExpression tree) {
        }
    }
}
