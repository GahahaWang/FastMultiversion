package cc.gahaha.fastmultiversion.intellij;

import cc.gahaha.fastmultiversion.apidb.ApiDatabase;
import cc.gahaha.fastmultiversion.versioning.VersionRange;
import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiConditionalExpression;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIfStatement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiPrefixExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.util.PsiUtil;
import com.siyeh.ig.psiutils.ControlFlowUtils;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Which versions a piece of code runs in, by the same rules as the javac plugin: a branch of an
 * {@code Mc.is} condition, an operand after {@code &&} or {@code ||}, an arm of {@code ?:}, a declaration
 * with {@code @Mc.Is}, and the statements after {@code if (!Mc.is(...)) return;}. Versions are bit masks
 * over the API database's versions.
 */
final class Guards {
    static final String MC = "cc.gahaha.fastmultiversion.Mc";
    /** The annotation {@code @Mc.Is}, nested in {@code Mc}. */
    static final String MC_IS = "cc.gahaha.fastmultiversion.Mc.Is";

    private final ApiDatabase db;
    private final long all;

    Guards(ApiDatabase db) {
        this.db = db;
        this.all = db.allVersions();
    }

    /** The versions in which the code at {@code element} runs. */
    long of(PsiElement element) {
        long guard = own(element);
        PsiElement child = element;
        for (PsiElement parent = element.getParent(); parent != null && !(parent instanceof PsiFile);
             child = parent, parent = parent.getParent()) {
            guard &= narrow(parent, child) & own(parent);
        }
        return guard;
    }

    /**
     * The versions in which {@code child} runs, given that {@code parent} runs (top down:
     * {@code guard(child) = guard(parent) & narrow(parent, child) & own(child)}).
     */
    long narrow(PsiElement parent, PsiElement child) {
        long guard = all;
        switch (parent) {
            case PsiIfStatement ifs -> {
                if (child == ifs.getThenBranch()) {
                    guard &= split(ifs.getCondition())[0];
                } else if (child == ifs.getElseBranch()) {
                    guard &= split(ifs.getCondition())[1];
                }
            }
            case PsiConditionalExpression conditional -> {
                if (child == conditional.getThenExpression()) {
                    guard &= split(conditional.getCondition())[0];
                } else if (child == conditional.getElseExpression()) {
                    guard &= split(conditional.getCondition())[1];
                }
            }
            case PsiPolyadicExpression polyadic when isAndOr(polyadic) -> {
                // An operand runs only if the ones before it did not decide the result.
                boolean and = polyadic.getOperationTokenType() == JavaTokenType.ANDAND;
                for (PsiExpression operand : polyadic.getOperands()) {
                    if (operand == child) {
                        break;
                    }
                    guard &= split(operand)[and ? 0 : 1];
                }
            }
            case PsiCodeBlock block when child instanceof PsiStatement statement -> {
                for (PsiStatement before : block.getStatements()) {
                    if (before == statement) {
                        break;
                    }
                    guard &= passing(before);
                }
            }
            default -> {
            }
        }
        return guard;
    }

    /** The versions that keep {@code element} itself: its {@code @Mc.Is}, if it is a declaration. */
    long own(PsiElement element) {
        return element instanceof PsiMember member ? annotatedVersions(member) : all;
    }

    long all() {
        return all;
    }

    /**
     * Adds the newest version of each piece of code below {@code element} that does not run in the active
     * version ({@code activeBit}); {@code guard} is the versions in which {@code element} runs.
     */
    void inactiveVersions(PsiElement element, long guard, long activeBit, Set<Integer> versions) {
        if (guard == 0) {
            return;
        }
        if ((guard & activeBit) == 0) {
            versions.add(MultiversionProject.State.newest(guard));
        }
        for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            inactiveVersions(child, guard & narrow(element, child) & own(child), activeBit, versions);
        }
    }

    /**
     * Adds the elements below {@code element} (children first, as the IDE visits them) that do not run in the
     * active version and whose newest version is {@code version}; {@code guard} as above.
     */
    void inactiveElements(PsiElement element, long guard, int version, long activeBit, List<PsiElement> elements) {
        if ((guard & (1L << version)) == 0) {
            return; // guards only narrow
        }
        for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            inactiveElements(child, guard & narrow(element, child) & own(child), version, activeBit, elements);
        }
        if ((guard & activeBit) == 0 && MultiversionProject.State.newest(guard) == version) {
            elements.add(element);
        }
    }

    /** The versions that get past {@code statement}: after {@code if (!Mc.is(r)) return;} only those in r. */
    private long passing(PsiStatement statement) {
        if (!(statement instanceof PsiIfStatement ifs)) {
            return all;
        }
        boolean thenCompletes = ifs.getThenBranch() == null || ControlFlowUtils.statementMayCompleteNormally(ifs.getThenBranch());
        boolean elseCompletes = ifs.getElseBranch() == null || ControlFlowUtils.statementMayCompleteNormally(ifs.getElseBranch());
        if (!thenCompletes && !elseCompletes) {
            return all;
        }
        long[] split = split(ifs.getCondition());
        return (thenCompletes ? split[0] : 0) | (elseCompletes ? split[1] : 0);
    }

    /** The versions in which {@code condition} can be true, and those in which it can be false. */
    long[] split(PsiExpression condition) {
        PsiExpression e = PsiUtil.skipParenthesizedExprDown(condition);
        switch (e) {
            case null -> {
                return new long[] {all, all};
            }
            case PsiPrefixExpression not when not.getOperationTokenType() == JavaTokenType.EXCL -> {
                long[] inner = split(not.getOperand());
                return new long[] {inner[1], inner[0]};
            }
            case PsiPolyadicExpression polyadic when isAndOr(polyadic) -> {
                boolean and = polyadic.getOperationTokenType() == JavaTokenType.ANDAND;
                long whenTrue = and ? all : 0;
                long whenFalse = and ? 0 : all;
                for (PsiExpression operand : polyadic.getOperands()) {
                    long[] s = split(operand);
                    if (and) {
                        whenFalse |= whenTrue & s[1];
                        whenTrue &= s[0];
                    } else {
                        whenTrue |= whenFalse & s[0];
                        whenFalse &= s[1];
                    }
                }
                return new long[] {whenTrue, whenFalse};
            }
            case PsiMethodCallExpression call -> {
                Long mask = mcIs(call);
                return mask == null ? new long[] {all, all} : new long[] {mask, all & ~mask};
            }
            case PsiLiteralExpression literal when literal.getValue() instanceof Boolean value -> {
                return value ? new long[] {all, 0} : new long[] {0, all};
            }
            default -> {
                return new long[] {all, all};
            }
        }
    }

    /** The versions of an {@code Mc.is(range)} call, or {@code null} if {@code call} is something else. */
    Long mcIs(PsiMethodCallExpression call) {
        PsiExpression range = mcIsRange(call);
        return range == null ? null : mask(rangeText(range));
    }

    /** The argument of an {@code Mc.is(range)} call, or {@code null} if {@code call} is something else. */
    static @Nullable PsiExpression mcIsRange(PsiMethodCallExpression call) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length != 1 || !"is".equals(call.getMethodExpression().getReferenceName())) {
            return null;
        }
        PsiMethod method = call.resolveMethod();
        PsiClass owner = method == null ? null : method.getContainingClass();
        return owner != null && MC.equals(owner.getQualifiedName()) ? args[0] : null;
    }

    /** The value of an {@code @Mc.Is(range)} annotation, or {@code null} if {@code annotation} is something else. */
    static @Nullable PsiAnnotationMemberValue annotationRange(PsiAnnotation annotation) {
        return MC_IS.equals(annotation.getQualifiedName()) ? annotation.findDeclaredAttributeValue("value") : null;
    }

    /**
     * The text of a range as the javac plugin reads it, before anything is resolved: a string literal, or a
     * {@code String} constant declared in the same file, or a concatenation of those; {@code null} otherwise.
     */
    static @Nullable String rangeText(@Nullable PsiElement range) {
        return rangeText(range, 0);
    }

    private static @Nullable String rangeText(@Nullable PsiElement range, int depth) {
        if (depth > 16 || !(range instanceof PsiExpression expression)) {
            return null;
        }
        switch (PsiUtil.skipParenthesizedExprDown(expression)) {
            case PsiLiteralExpression literal when literal.getValue() instanceof String text -> {
                return text;
            }
            case PsiPolyadicExpression plus when plus.getOperationTokenType() == JavaTokenType.PLUS -> {
                StringBuilder text = new StringBuilder();
                for (PsiExpression operand : plus.getOperands()) {
                    String part = rangeText(operand, depth + 1);
                    if (part == null) {
                        return null;
                    }
                    text.append(part);
                }
                return text.toString();
            }
            case PsiReferenceExpression reference when reference.resolve() instanceof PsiField field
                    && field.getInitializer() != null && isConstant(field)
                    && field.getContainingFile().getOriginalFile() == reference.getContainingFile().getOriginalFile() -> {
                return rangeText(field.getInitializer(), depth + 1);
            }
            case null, default -> {
                return null;
            }
        }
    }

    private static boolean isConstant(PsiField field) {
        PsiClass owner = field.getContainingClass();
        return owner != null && owner.isInterface()
                || field.hasModifierProperty(PsiModifier.STATIC) && field.hasModifierProperty(PsiModifier.FINAL);
    }

    /** The versions in {@code range}; {@code null} if it is not a valid range. */
    @Nullable Long versions(String range) {
        return mask(range);
    }

    /** The versions keeping a declaration with {@code @Mc.Is(range)}; all versions without one. */
    long annotatedVersions(PsiMember member) {
        PsiAnnotation annotation = AnnotationUtil.findAnnotation(member, MC_IS);
        if (annotation == null) {
            return all;
        }
        PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
        Long mask = mask(rangeText(value));
        return mask == null ? all : mask;
    }

    private Long mask(Object range) {
        if (!(range instanceof String text)) {
            return null;
        }
        VersionRange parsed;
        try {
            parsed = VersionRange.parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        long mask = 0;
        for (int i = 0; i < db.versions().size(); i++) {
            if (parsed.contains(db.versions().get(i))) {
                mask |= 1L << i;
            }
        }
        return mask;
    }

    static boolean isAndOr(PsiPolyadicExpression expression) {
        return expression.getOperationTokenType() == JavaTokenType.ANDAND || expression.getOperationTokenType() == JavaTokenType.OROR;
    }
}
