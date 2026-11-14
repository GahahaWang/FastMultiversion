package cc.gahaha.fastmultiversion.javac;

import cc.gahaha.fastmultiversion.versioning.McVersion;
import cc.gahaha.fastmultiversion.versioning.VersionRange;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCAssign;
import com.sun.tools.javac.tree.JCTree.JCBinary;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCConditional;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCFieldAccess;
import com.sun.tools.javac.tree.JCTree.JCIdent;
import com.sun.tools.javac.tree.JCTree.JCIf;
import com.sun.tools.javac.tree.JCTree.JCLiteral;
import com.sun.tools.javac.tree.JCTree.JCMemberReference;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCParens;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCUnary;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.JCTree.Tag;
import com.sun.tools.javac.tree.TreeCopier;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.ListBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rewrites one parsed file into the program for the target version. Nothing is resolved yet, so
 * {@code Mc.is} and {@code @IfMc} are recognized by syntax ({@link McSyntax}), and a range must be a string
 * literal or a {@code String} constant declared in the same file.
 *
 * <p>With a list of versions (from the API database), it also works out which of those versions each
 * piece of kept code runs in, its guard, for {@link RangeChecker}. Guards are bit masks over the versions,
 * stored for the kept nodes where they narrow: a branch of an {@code Mc.is} condition, the right side of
 * {@code &&} or {@code ||}, an arm of {@code ?:}, a declaration with {@code @IfMc}, and the statements
 * after {@code if (!Mc.is(...)) return;}. A node's guard is that of its nearest recorded ancestor.
 */
final class SourcePruner extends TreeTranslator {
    /** A declaration removed by {@code @IfMc}, for the hint when kept code still refers to it. */
    record Removed(String name, String description) {
    }

    private final MultiversionListener host;
    private final JCCompilationUnit unit;
    private final McSyntax syntax;
    private final TreeMaker make;
    private final TreeCopier<Void> copier;
    private final Deque<JCClassDecl> classes = new ArrayDeque<>();
    private final List<Removed> removed = new ArrayList<>();

    private final List<McVersion> versions;
    private final long all;
    private final Map<JCTree, Long> guards;
    /** Guards for nodes about to be translated; applied and recorded by {@link #translate(JCTree)}. */
    private final Map<JCTree, Long> planned = new IdentityHashMap<>();
    private long guard;

    /**
     * @param versions the versions to compute guards over, or {@code null} not to compute them
     * @param guards   receives the guards of kept nodes
     */
    SourcePruner(MultiversionListener host, JCCompilationUnit unit, TreeMaker make, List<McVersion> versions,
                 Map<JCTree, Long> guards) {
        this.host = host;
        this.unit = unit;
        this.syntax = new McSyntax(unit);
        this.make = make;
        this.copier = new TreeCopier<>(make);
        this.versions = versions;
        this.all = versions == null ? 0 : versions.size() == 64 ? -1L : (1L << versions.size()) - 1;
        this.guards = guards;
        this.guard = all;
    }

    /** Prunes the file in place and returns what {@code @IfMc} removed. */
    List<Removed> prune() {
        ListBuffer<JCTree> defs = new ListBuffer<>();
        for (JCTree def : withoutRemovedDeclarations(unit.defs)) {
            defs.append(def instanceof JCClassDecl ? translate(def) : def);
        }
        unit.defs = defs.toList();
        return removed;
    }

    // ---- guards

    private boolean tracking() {
        return versions != null;
    }

    /** Plans a guard for {@code node}: the current guard narrowed to {@code mask}. */
    private void plan(JCTree node, long mask) {
        if (tracking() && node != null) {
            planned.put(node, guard & mask);
        }
    }

    @Override
    public <T extends JCTree> T translate(T tree) {
        if (tree == null) {
            return null;
        }
        Long narrowed = planned.remove(tree);
        if (narrowed == null) {
            return super.translate(tree);
        }
        long saved = guard;
        guard = narrowed;
        T result = super.translate(tree);
        guard = saved;
        if (result != null) {
            guards.put(result, narrowed);
        }
        return result;
    }

    /** {@link #split(JCExpression, List)} over the database's versions. */
    private long[] split(JCExpression cond) {
        return split(cond, versions);
    }

    /**
     * Of the versions in {@code over}, those in which {@code cond} can be true, and those in which it can
     * be false. Anything but {@code Mc.is}, literals, {@code !}, {@code &&} and {@code ||} can be either.
     */
    private long[] split(JCExpression cond, List<McVersion> over) {
        long full = over.size() == 64 ? -1L : (1L << over.size()) - 1;
        return switch (cond) {
            case JCParens parens -> split(parens.expr, over);
            case JCUnary not when not.hasTag(Tag.NOT) -> {
                long[] inner = split(not.arg, over);
                yield new long[] {inner[1], inner[0]};
            }
            case JCBinary and when and.hasTag(Tag.AND) -> {
                long[] l = split(and.lhs, over);
                long[] r = split(and.rhs, over);
                yield new long[] {l[0] & r[0], l[1] | (l[0] & r[1])};
            }
            case JCBinary or when or.hasTag(Tag.OR) -> {
                long[] l = split(or.lhs, over);
                long[] r = split(or.rhs, over);
                yield new long[] {l[0] | (l[1] & r[0]), l[1] & r[1]};
            }
            case JCMethodInvocation call when isMcIs(call) -> {
                String text = constantString(call.args.head, 0);
                VersionRange range = text == null ? null : quietRange(text);
                if (range == null) {
                    yield new long[] {full, full};
                }
                long mask = mask(range, over);
                yield new long[] {mask, full & ~mask};
            }
            case JCLiteral literal when literal.typetag == TypeTag.BOOLEAN ->
                    Integer.valueOf(0).equals(literal.value) ? new long[] {0, full} : new long[] {full, 0};
            default -> new long[] {full, full};
        };
    }

    private long mask(VersionRange range) {
        return mask(range, versions);
    }

    private static long mask(VersionRange range, List<McVersion> over) {
        long mask = 0;
        for (int i = 0; i < over.size(); i++) {
            if (range.contains(over.get(i))) {
                mask |= 1L << i;
            }
        }
        return mask;
    }

    private static VersionRange quietRange(String text) {
        try {
            return VersionRange.parse(text);
        } catch (IllegalArgumentException e) {
            return null; // reported when the condition is evaluated
        }
    }

    // ---- @IfMc

    @Override
    public void visitClassDef(JCClassDecl tree) {
        classes.push(tree);
        tree.defs = withoutRemovedDeclarations(tree.defs);
        super.visitClassDef(tree);
        classes.pop();
    }

    @Override
    public void visitBlock(JCBlock tree) {
        tree.stats = withoutRemovedDeclarations(tree.stats); // local classes
        ListBuffer<JCStatement> stats = new ListBuffer<>();
        long current = guard;
        for (com.sun.tools.javac.util.List<JCStatement> rest = tree.stats; rest.nonEmpty(); rest = rest.tail) {
            JCStatement statement = rest.head;
            // Both computed on the statement as written, before translating changes it.
            long next = tracking() ? current & passingVersions(statement, versions, current) : current;
            McVersion target = host.rawTarget();
            boolean targetPasses = target == null || passingVersions(statement, List.of(target), 1) != 0;
            if (tracking() && current != guard) {
                Long own = planned.get(statement); // a local class with @IfMc
                planned.put(statement, own == null ? current : own & current);
            }
            stats.append(translate(statement));
            if (!targetPasses && rest.tail.nonEmpty()) {
                // After `if (!Mc.is(r)) return;` a build outside r never runs the rest of the block.
                stats.append(Reachability.standIn(make, make.at(rest.tail.head.pos).Block(0, rest.tail)));
                break;
            }
            current = next;
        }
        tree.stats = stats.toList();
        result = tree;
    }

    /**
     * Of the versions in {@code over}, those that can get past {@code statement} to the statements after
     * it: after {@code if (!Mc.is(r)) return;} only the versions in {@code r} do. Statements other than
     * {@code if} let every version through ({@code fallback} for an {@code if} nothing gets past, which
     * only happens when the rest of the block is unreachable anyway).
     */
    private long passingVersions(JCStatement statement, List<McVersion> over, long fallback) {
        long all = over.size() == 64 ? -1L : (1L << over.size()) - 1;
        if (!(statement instanceof JCIf ifs)) {
            return all;
        }
        boolean thenCompletes = Reachability.canCompleteNormally(ifs.thenpart);
        boolean elseCompletes = ifs.elsepart == null || Reachability.canCompleteNormally(ifs.elsepart);
        if (!thenCompletes && !elseCompletes) {
            return fallback; // no version gets past it whatever the condition: the rest is unreachable as written
        }
        long[] split = split(ifs.cond, over);
        return (thenCompletes ? split[0] : 0) | (elseCompletes ? split[1] : 0);
    }

    private <T extends JCTree> com.sun.tools.javac.util.List<T> withoutRemovedDeclarations(com.sun.tools.javac.util.List<T> defs) {
        ListBuffer<T> kept = new ListBuffer<>();
        for (T def : defs) {
            if (!isRemoved(def)) {
                kept.append(def);
            }
        }
        return kept.toList();
    }

    /** Whether {@code @IfMc} removes the declaration; a kept one gets its range planned as its guard. */
    private boolean isRemoved(JCTree def) {
        JCModifiers mods = switch (def) {
            case JCClassDecl c -> c.mods;
            case JCMethodDecl m -> m.mods;
            case JCVariableDecl v -> v.mods;
            default -> null;
        };
        if (mods == null) {
            return false;
        }
        for (JCAnnotation annotation : mods.annotations) {
            if (!syntax.isIfMc(annotation)) {
                continue;
            }
            JCExpression value = annotationValue(annotation);
            String text = value == null ? null : constantString(value, 0);
            if (text == null) {
                host.error(annotation, unit, "the range in @IfMc must be a string literal or a String constant "
                        + "declared in this file");
                return false;
            }
            VersionRange range = host.range(text, annotation, unit);
            McVersion target = host.target(annotation, unit);
            if (range == null || target == null) {
                return false;
            }
            if (range.contains(target)) {
                if (tracking()) {
                    plan(def, mask(range));
                }
                return false;
            }
            removed.add(new Removed(nameOf(def), describe(def) + " is declared with @IfMc(\"" + text + "\")"));
            return true;
        }
        return false;
    }

    private static JCExpression annotationValue(JCAnnotation annotation) {
        if (annotation.args.size() != 1) {
            return null;
        }
        JCExpression arg = annotation.args.head;
        if (arg instanceof JCAssign assign) {
            return assign.lhs instanceof JCIdent name && name.name.contentEquals("value") ? assign.rhs : null;
        }
        return arg;
    }

    private String nameOf(JCTree def) {
        return switch (def) {
            case JCClassDecl c -> c.name.toString();
            case JCMethodDecl m when m.name.contentEquals("<init>") -> classes.isEmpty() ? "<init>" : classes.peek().name.toString();
            case JCMethodDecl m -> m.name.toString();
            case JCVariableDecl v -> v.name.toString();
            default -> "?";
        };
    }

    private String describe(JCTree def) {
        String owner = classes.isEmpty() ? "" : classes.peek().name + ".";
        return switch (def) {
            case JCClassDecl c -> "class " + owner + c.name;
            case JCMethodDecl m when m.name.contentEquals("<init>") -> "a constructor of " + nameOf(def);
            case JCMethodDecl m -> "method " + owner + m.name + "(...)";
            case JCVariableDecl v when (v.mods.flags & Flags.RECORD) != 0 -> "record component " + owner + v.name;
            case JCVariableDecl v -> "field " + owner + v.name;
            default -> "declaration";
        };
    }

    // ---- Mc.is

    @Override
    public void visitIf(JCIf tree) {
        if (tracking()) {
            long[] split = split(tree.cond);
            plan(tree.thenpart, split[0]);
            plan(tree.elsepart, split[1]);
        }
        Cond cond = evaluate(tree.cond);
        if (cond.value == null) {
            tree.cond = cond.expr;
            tree.thenpart = translate(tree.thenpart);
            tree.elsepart = translate(tree.elsepart);
            result = tree;
            return;
        }
        boolean live = cond.value;
        if (cond.pure && !live && tree.elsepart == null) {
            planned.remove(tree.thenpart);
            result = make.at(tree.pos).Skip();
            return;
        }
        // Keep the if, with a literal condition: JLS 14.22 deliberately ignores constant if-conditions
        // when deciding reachability, and javac drops the dead side when it generates code.
        tree.cond = cond.pure ? make.at(tree.cond.pos).Parens(literal(live, tree.cond.pos)) : cond.expr;
        if (live) {
            tree.thenpart = translate(tree.thenpart);
            tree.elsepart = tree.elsepart == null ? null : standIn(tree.elsepart);
        } else {
            tree.thenpart = standIn(tree.thenpart);
            tree.elsepart = translate(tree.elsepart);
        }
        result = tree;
    }

    private JCStatement standIn(JCStatement dead) {
        planned.remove(dead);
        return Reachability.standIn(make, dead);
    }

    @Override
    public void visitConditional(JCConditional tree) {
        if (tracking()) {
            long[] split = split(tree.cond);
            plan(tree.truepart, split[0]);
            plan(tree.falsepart, split[1]);
        }
        Cond cond = evaluate(tree.cond);
        if (cond.value == null) {
            tree.cond = cond.expr;
            tree.truepart = translate(tree.truepart);
            tree.falsepart = translate(tree.falsepart);
            result = tree;
            return;
        }
        planned.remove(cond.value ? tree.falsepart : tree.truepart);
        JCExpression live = translate(cond.value ? tree.truepart : tree.falsepart);
        if (cond.pure) {
            result = make.at(tree.pos).Parens(live);
            return;
        }
        // The condition has side effects (e.g. `check() && Mc.is(...) ? a : b`): keep evaluating it, and
        // put the live arm on both sides so the dead one is gone.
        tree.cond = cond.expr;
        tree.truepart = live;
        tree.falsepart = copier.copy(live);
        if (tracking()) {
            guards.put(tree.falsepart, 0L); // the same code as the live arm: nothing more to check
        }
        result = tree;
    }

    @Override
    public void visitApply(JCMethodInvocation tree) {
        if (isMcIs(tree)) {
            result = literal(evaluateMcIs(tree), tree.pos);
        } else {
            super.visitApply(tree);
        }
    }

    @Override
    public void visitBinary(JCBinary tree) {
        if (tree.hasTag(Tag.AND) || tree.hasTag(Tag.OR)) {
            result = evaluate(tree).expr;
        } else {
            super.visitBinary(tree);
        }
    }

    @Override
    public void visitUnary(JCUnary tree) {
        if (tree.hasTag(Tag.NOT)) {
            result = evaluate(tree).expr;
        } else {
            super.visitUnary(tree);
        }
    }

    @Override
    public void visitReference(JCMemberReference tree) {
        if (tree.name.contentEquals("is") && syntax.isMc(tree.expr)) {
            host.error(tree, unit, "Mc can only be used as a direct call Mc.is(\"<range>\"); a method reference "
                    + "would stay in the bytecode and throw at runtime");
        }
        super.visitReference(tree);
    }

    /**
     * A partly evaluated boolean expression.
     *
     * @param value the value it always has on the target, or {@code null} if that depends on runtime state
     * @param expr  the rewritten expression; for a pure expression, a literal
     * @param pure  whether it consists only of literals and {@code Mc.is}, so dropping it loses nothing
     */
    private record Cond(Boolean value, JCExpression expr, boolean pure) {
    }

    /** Evaluates {@code e} for the target, applying a guard planned for it, like {@link #translate}. */
    private Cond evaluate(JCExpression e) {
        Long narrowed = planned.remove(e);
        if (narrowed == null) {
            return evaluateUnguarded(e);
        }
        long saved = guard;
        guard = narrowed;
        Cond cond = evaluateUnguarded(e);
        guard = saved;
        guards.put(cond.expr, narrowed);
        return cond;
    }

    private Cond evaluateUnguarded(JCExpression e) {
        switch (e) {
            case JCParens parens -> {
                Cond inner = evaluate(parens.expr);
                if (inner.pure) {
                    return inner;
                }
                parens.expr = inner.expr;
                return new Cond(inner.value, parens, false);
            }
            case JCUnary not when not.hasTag(Tag.NOT) -> {
                Cond inner = evaluate(not.arg);
                Boolean value = inner.value == null ? null : !inner.value;
                if (inner.pure) {
                    return pure(value, e.pos);
                }
                not.arg = inner.expr;
                return new Cond(value, not, false);
            }
            case JCBinary binary when binary.hasTag(Tag.AND) || binary.hasTag(Tag.OR) -> {
                return evaluateShortCircuit(binary);
            }
            case JCMethodInvocation call when isMcIs(call) -> {
                return pure(evaluateMcIs(call), e.pos);
            }
            case JCLiteral literal when literal.typetag == TypeTag.BOOLEAN -> {
                return pure(!Integer.valueOf(0).equals(literal.value), e.pos);
            }
            default -> {
                return new Cond(null, translate(e), false);
            }
        }
    }

    private Cond evaluateShortCircuit(JCBinary tree) {
        // The left value that decides the result without evaluating the right side.
        boolean decisive = tree.hasTag(Tag.OR);
        if (tracking()) {
            long[] split = split(tree.lhs);
            plan(tree.rhs, decisive ? split[1] : split[0]); // the right side runs when the left is not decisive
        }
        Cond left = evaluate(tree.lhs);
        if (left.value != null && left.value == decisive) {
            planned.remove(tree.rhs);
            if (left.pure) {
                return pure(decisive, tree.pos);
            }
            // The right side never runs: drop it, but keep the left side's effects.
            tree.lhs = left.expr;
            tree.rhs = literal(decisive, tree.rhs.pos);
            return new Cond(decisive, tree, false);
        }
        Cond right = evaluate(tree.rhs);
        if (left.pure) {
            return right; // the left side is the neutral value: true && x, false || x
        }
        tree.lhs = left.expr;
        tree.rhs = right.expr;
        Boolean value;
        if (right.value != null && right.value == decisive) {
            value = decisive;
        } else {
            value = left.value != null ? right.value : null;
        }
        return new Cond(value, tree, false);
    }

    private Cond pure(boolean value, int pos) {
        return new Cond(value, literal(value, pos), true);
    }

    private JCExpression literal(boolean value, int pos) {
        return make.at(pos).Literal(TypeTag.BOOLEAN, value ? 1 : 0);
    }

    private boolean isMcIs(JCMethodInvocation call) {
        return syntax.isMcIs(call, declaresIsMethod());
    }

    /** A method named {@code is} in an enclosing class hides a static import of {@code Mc.is}. */
    private boolean declaresIsMethod() {
        for (JCClassDecl c : classes) {
            for (JCTree def : c.defs) {
                if (def instanceof JCMethodDecl m && m.name.contentEquals("is")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean evaluateMcIs(JCMethodInvocation call) {
        JCExpression arg = call.args.head;
        String text = constantString(arg, 0);
        if (text == null) {
            host.error(arg, unit, "the range passed to Mc.is must be a string literal or a String constant "
                    + "declared in this file");
            return false;
        }
        VersionRange range = host.range(text, arg, unit);
        McVersion target = host.target(call, unit);
        return range != null && target != null && range.contains(target);
    }

    // ---- constants

    /**
     * The value of a String constant expression made of literals, {@code +}, and constants declared in
     * this file ({@code NAME} in an enclosing class, or {@code Type.NAME} for a class in this file).
     */
    private String constantString(JCExpression e, int depth) {
        if (depth > 16) {
            return null;
        }
        return switch (e) {
            case JCLiteral literal when literal.value instanceof String s -> s;
            case JCParens parens -> constantString(parens.expr, depth + 1);
            case JCBinary plus when plus.hasTag(Tag.PLUS) -> {
                String left = constantString(plus.lhs, depth + 1);
                String right = constantString(plus.rhs, depth + 1);
                yield left == null || right == null ? null : left + right;
            }
            case JCIdent ident -> {
                for (JCClassDecl c : classes) {
                    String value = constantField(c, ident.name.toString(), depth);
                    if (value != null) {
                        yield value;
                    }
                }
                yield null;
            }
            case JCFieldAccess select when select.selected instanceof JCIdent owner -> {
                JCClassDecl c = findClass(owner.name.toString());
                yield c == null ? null : constantField(c, select.name.toString(), depth);
            }
            default -> null;
        };
    }

    private String constantField(JCClassDecl c, String name, int depth) {
        boolean inInterface = (c.mods.flags & Flags.INTERFACE) != 0;
        for (JCTree def : c.defs) {
            if (def instanceof JCVariableDecl v && v.name.contentEquals(name) && v.init != null
                    && (inInterface || (v.mods.flags & (Flags.STATIC | Flags.FINAL)) == (Flags.STATIC | Flags.FINAL))) {
                return constantString(v.init, depth + 1);
            }
        }
        return null;
    }

    private JCClassDecl findClass(String simpleName) {
        Deque<JCTree> pending = new ArrayDeque<>(unit.defs);
        while (!pending.isEmpty()) {
            if (pending.pop() instanceof JCClassDecl c) {
                if (c.name.contentEquals(simpleName)) {
                    return c;
                }
                pending.addAll(c.defs);
            }
        }
        return null;
    }
}
