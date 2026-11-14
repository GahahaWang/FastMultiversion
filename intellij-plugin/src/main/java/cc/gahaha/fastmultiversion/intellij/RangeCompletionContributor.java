package cc.gahaha.fastmultiversion.intellij;

import cc.gahaha.fastmultiversion.versioning.McVersion;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.PlainPrefixMatcher;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Completion inside the range of {@code Mc.is("...")} and {@code @IfMc("...")}: the versions the project
 * builds, each alone, from it on, and before it, and the span from each to the next.
 */
public final class RangeCompletionContributor extends CompletionContributor {
    @Override
    public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        PsiElement position = parameters.getPosition();
        if (!(position.getParent() instanceof PsiLiteralExpression literal) || !(literal.getValue() instanceof String) || !isRange(literal)) {
            return;
        }
        MultiversionProject.State state = MultiversionProject.getInstance(position.getProject()).state();
        if (state == null) {
            return;
        }
        int start = literal.getTextRange().getStartOffset() + 1; // after the quote
        String typed = parameters.getOffset() >= start
                ? literal.getContainingFile().getText().substring(start, parameters.getOffset()) : "";
        CompletionResultSet ranges = result.withPrefixMatcher(new PlainPrefixMatcher(typed));
        List<McVersion> versions = state.db().versions();
        for (int i = 0; i < versions.size(); i++) {
            String v = versions.get(i).toString();
            ranges.addElement(LookupElementBuilder.create(v).withTailText("  only " + v, true));
            ranges.addElement(LookupElementBuilder.create("[" + v + ",)").withTailText("  " + v + " and newer", true));
            ranges.addElement(LookupElementBuilder.create("(," + v + ")").withTailText("  before " + v, true));
            if (i + 1 < versions.size()) {
                String next = versions.get(i + 1).toString();
                ranges.addElement(LookupElementBuilder.create("[" + v + "," + next + ")")
                        .withTailText("  " + v + " up to " + next + ", without it", true));
            }
        }
        result.stopHere();
    }

    /** The range argument of {@code Mc.is} or the value of {@code @IfMc}. */
    private static boolean isRange(PsiLiteralExpression literal) {
        PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(literal, PsiMethodCallExpression.class);
        if (call != null && Guards.mcIsRange(call) == literal) {
            return true;
        }
        PsiAnnotation annotation = PsiTreeUtil.getParentOfType(literal, PsiAnnotation.class);
        return annotation != null && Guards.ifMcRange(annotation) == literal;
    }
}
